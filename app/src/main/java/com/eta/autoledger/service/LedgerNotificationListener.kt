package com.eta.autoledger.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerDatabase
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.NotifyBill
import com.eta.autoledger.data.NotifyChannel
import com.eta.autoledger.data.NotificationRules
import com.eta.autoledger.data.NotifyRaw
import com.eta.autoledger.data.ParsePipeline
import com.eta.autoledger.data.SeenStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知监听：支付宝 / 微信 / 银行 / 短信 / 其他支付 App 的账单通知 -> 解析 -> 去重入库。
 *
 * 数据流：
 * onNotificationPosted（主线程）
 *   -> 过滤自身通知、常驻通知、通知组摘要
 *   -> 按包名归类，检查对应开关（支付宝 / 微信 / 短信 / 其他）
 *   -> 投递到 IO 协程：[SeenStore] 查持久化去重表（进程被杀后重启仍有效）
 *   -> [ParsePipeline.parse]：先跑 `rule` 表里的动态规则，未命中回退内置正则与关键词
 *   -> `LedgerRepository.addAutoParsed`（externalId 唯一索引 + 窗口去重）入库
 *   -> 入库成功且未开启「不提示」时，发一条可撤销的通知
 */
class LedgerNotificationListener : NotificationListenerService() {

    /**
     * 解析协程的异常边界。
     *
     * [SupervisorJob] 只保证一个子任务失败不会取消兄弟任务；它不会替根协程吞掉异常。
     * 外层 `onNotificationPosted` 的 `runCatching` 只能覆盖主线程上的同步部分，覆盖不到
     * [launch] 之后才执行的规则加载与正则匹配。没有这个处理器时，边界正则抛出的
     * [StackOverflowError] 等异常仍会交给线程默认处理器，可能直接终止 App 进程。
     */
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                Log.w(TAG, "通知处理协程异常，已隔离；监听器继续接收后续通知", error)
            },
    )
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_SEEN
    }
    private var appPrefs: AppPrefs? = null

    /** 上次尝试重绑的时刻，用于 [REBIND_THROTTLE_MS] 节流。只在主线程（回调线程）读写。 */
    private var lastRebindAt = 0L

    /**
     * [MERGE_WINDOW_MS] 窗口内「渠道 + 金额 + 方向 + 摘要片段」相同的账单只入一次。
     *
     * 折叠摘要到达时会把同组子通知捞一遍解析，随后系统自己也会把子通知逐个 post 过来；
     * 两条路径看到的是同一笔钱，靠这个窗口归并，而不是靠通知 key（每条 key 都不同）。
     */
    private val merged = HashMap<String, Long>()

    private fun prefs(): AppPrefs =
        appPrefs ?: AppPrefs.get(this).also { appPrefs = it }

    override fun onCreate() {
        super.onCreate()
        appPrefs = AppPrefs.get(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
    }

    /**
     * 通道被系统或 ROM 断开后的自愈。
     *
     * 触发场景：用户在设置里切换过一次通知使用权、ROM 省电策略清理了绑定、
     * 或本进程被冻结后恢复。断开期间所有通知都收不到，等于整条采集通道失聪，
     * 而系统不会自动把监听器绑回来 —— 必须自己调 [requestRebind]。
     *
     * 用 30 秒节流防死循环：ROM 反复拒绝重绑时，最坏每分钟重试两次，
     * 而不是形成「断开→重绑→断开」的紧循环。
     */
    override fun onListenerDisconnected() {
        val now = System.currentTimeMillis()
        if (now - lastRebindAt < REBIND_THROTTLE_MS) {
            Log.w(TAG, "通知监听断开，${REBIND_THROTTLE_MS / 1000}s 内已重试过，跳过本次重绑")
            return
        }
        lastRebindAt = now
        val component = ComponentName(this, LedgerNotificationListener::class.java)
        // requestRebind 是 void：调用本身不报错不代表重绑成功，ROM 可能静默拒绝，
        // 所以结果只能靠下一次 onListenerConnected 观察，这里只记录「已请求」。
        val error = runCatching { requestRebind(component) }.exceptionOrNull()
        if (error != null) {
            Log.w(TAG, "通知监听重绑失败：$component", error)
        } else {
            Log.i(TAG, "通知监听断开，已请求重绑：$component")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        runCatching { handle(sbn) }.onFailure { Log.w(TAG, "通知解析异常", it) }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val channel = NotificationRules.channelOf(sbn.packageName) ?: return
        val prefs = prefs()
        if (!isChannelEnabled(prefs, channel)) return

        // 折叠摘要本身往往只剩「你有 2 笔新消息」，金额在同一个 group 的子通知上。
        // 子通知通常紧跟摘要到达，所以这里主动把同组通知捞一遍，而不是干等下一次回调。
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            val children = collectGroupChildren(sbn)
            if (children.isEmpty()) {
                Log.d(TAG, "摘要到达时同组还没有子通知：${sbn.packageName}")
                return
            }
            children.forEach { child -> handleOne(child, prefs) }
            return
        }
        handleOne(sbn, prefs)
    }

    /** 同 group 下仍在通知栏里的其它通知；`activeNotifications` 由 NMS 实时维护。 */
    private fun collectGroupChildren(sbn: StatusBarNotification): List<StatusBarNotification> {
        // StatusBarNotification.getGroup() 是 @hide，取公开的 Notification.getGroup()
        val group = sbn.notification?.group ?: return emptyList()
        if (group.isEmpty()) return emptyList()
        val actives = runCatching { activeNotifications.toList() }.getOrElse { return emptyList() }
        return actives.filter {
            it.packageName == sbn.packageName && it.notification?.group == group && it.key != sbn.key
        }
    }

    private fun handleOne(sbn: StatusBarNotification, prefs: AppPrefs) {
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val raw = NotifyRaw(
            pkg = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            postTime = sbn.postTime,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
        )
        if (raw.title.isBlank() && raw.text.isBlank() && raw.bigText.isBlank() &&
            raw.subText.isBlank()
        ) return

        val seenKey = NotificationRules.dedupeKey(raw)
        if (isDuplicate(seenKey)) return

        val appPrefs = prefs()
        // 解析要读 rule 表（动态规则），只能在协程里做；解析之后的归并、入库、落 seen_key
        // 本来就已经在协程里，所以整段搬进来不改变原有语义，只是把同步部分缩到最小。
        scope.launch {
            // 进程被杀后重启、系统补投积压通知，这些场景内存 LRU 是空的，
            // 只能靠持久化表兜住，否则同一条扣款会入两笔。
            if (SeenStore.contains(this@LedgerNotificationListener, seenKey)) {
                Log.d(TAG, "持久化去重命中，跳过：${raw.title}")
                return@launch
            }

            val bill = ParsePipeline.parse(
                raw = raw,
                expenseCats = CategoryStore.names(this@LedgerNotificationListener, 0),
                incomeCats = CategoryStore.names(this@LedgerNotificationListener, 1),
                otherCats = CategoryStore.names(this@LedgerNotificationListener, 2),
                ignoreKeywords = appPrefs.ignoreKeywords,
                forceKeywords = appPrefs.forceKeywords,
                dao = LedgerDatabase.get(this@LedgerNotificationListener).ruleDao(),
            ) ?: run {
                Log.d(TAG, "未识别为账单：${raw.title} / ${raw.text}")
                return@launch
            }

            // 摘要补全与系统逐条回调可能把同一笔各送一次，短窗口内按内容归并
            val mergeKey = mergeKeyOf(bill)
            if (isRecentlyMerged(mergeKey)) {
                Log.d(TAG, "窗口内重复，跳过：$mergeKey")
                return@launch
            }

            // 规则被设成「只识别不记账」：认得出来，但不入库。
            // 仍然记去重键，否则同一条通知每次回调都会重新走一遍解析与日志。
            if (!bill.autoRecord) {
                Log.i(TAG, "规则要求不自动记账：${bill.source} ${bill.amount} ${bill.category}")
                markSeen(seenKey)
                markMerged(mergeKey)
                SeenStore.remember(this@LedgerNotificationListener, seenKey)
                return@launch
            }

            // 主键交给 Room 自增（传 0L），别用时间戳，否则同毫秒两笔会被 REPLACE 覆盖
            // 短信通道可能刚把同一笔扣费记过：入库前做跨渠道窗口去重
            val entry = bill.toEntry(0L)
            if (runCatching {
                LedgerRepository.isRecentDuplicate(
                    this@LedgerNotificationListener,
                    bill.amount,
                    bill.type,
                    bill.source,
                    bill.timeMillis,
                )
            }.getOrElse { false }) {
                Log.d(TAG, "窗口内已有同额自动账单，跳过：${bill.amount}")
                return@launch
            }
            val added = runCatching {
                LedgerRepository.addAutoParsed(this@LedgerNotificationListener, listOf(entry))
            }.getOrElse {
                Log.w(TAG, "通知账单入库失败", it)
                0
            }
            if (added > 0) {
                prefs().lastNotifyAt = System.currentTimeMillis()
                markSeen(seenKey)
                markMerged(mergeKey)
                // 只在真入库之后才落去重键：解析失败或被窗口挡掉的文本不该占表空间，
                // 也让 10 分钟 TTL 只覆盖真正记过账的原文。
                SeenStore.remember(this@LedgerNotificationListener, seenKey)
                BillNotifier.notifyAdded(this@LedgerNotificationListener, bill)
            }
        }
    }

    private fun isChannelEnabled(prefs: AppPrefs, channel: NotifyChannel): Boolean = when (channel) {
        NotifyChannel.ALIPAY -> prefs.alipayEnabled
        NotifyChannel.WECHAT -> prefs.wechatEnabled
        NotifyChannel.SMS -> prefs.smsEnabled
        NotifyChannel.BANK, NotifyChannel.OTHER -> prefs.otherEnabled
    }

    private fun isDuplicate(key: String): Boolean = synchronized(seen) { seen.containsKey(key) }

    private fun markSeen(key: String) {
        synchronized(seen) { seen[key] = System.currentTimeMillis() }
    }

    /**
     * 归并键：渠道 + 方向 + 金额 + 摘要片段。
     *
     * 带上 note 是必须的——同渠道 1 分钟内连点两笔同额外卖是常态，
     * 只按「渠道+金额」归并会把第二笔真账吞掉；同一条通知在不同路径上内容相同，note 也就相同。
     */
    private fun mergeKeyOf(bill: NotifyBill): String =
        "${bill.channel}|${bill.type}|${"%.2f".format(bill.amount)}|${bill.note.take(12)}"

    private fun isRecentlyMerged(key: String): Boolean = synchronized(merged) {
        val at = merged[key] ?: return false
        if (System.currentTimeMillis() - at > MERGE_WINDOW_MS) {
            merged.remove(key)
            false
        } else {
            true
        }
    }

    private fun markMerged(key: String) {
        val now = System.currentTimeMillis()
        synchronized(merged) {
            merged.entries.removeAll { now - it.value > MERGE_WINDOW_MS }
            merged[key] = now
        }
    }

    companion object {
        private const val TAG = "AutoLedger"
        private const val MAX_SEEN = 200

        /**
         * 折叠补全的归并窗口：摘要与子通知几乎同秒到达，10s 既能兜住慢机型，
         * 也不会误吞连刷的同额真账。
         *
         * 注意：类头 KDoc 早期写的是「120 秒窗口」，与这里的 10 秒矛盾 —— 以本常量为准。
         */
        private const val MERGE_WINDOW_MS = 10_000L

        /**
         * 重绑节流：两次 [requestRebind] 之间至少间隔这么久。
         *
         * 部分 ROM（EMUI / ColorOS 的省电策略）会接受 requestRebind 后立刻再次断开，
         * 不加节流就会变成「断开 → 重绑 → 立刻断开 → 重绑」的死循环，白烧电还刷日志。
         * 30 秒意味着最坏情况下每分钟只重试两次，而一次成功的重绑能让通道恢复数小时。
         */
        private const val REBIND_THROTTLE_MS = 30_000L

        /** 通知使用权是否已经授予本应用。 */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val expected = ComponentName(context, LedgerNotificationListener::class.java)
            return flat.split(':').any { entry ->
                entry.isNotBlank() &&
                    ComponentName.unflattenFromString(entry)?.let { it == expected } == true
            }
        }

        /** 跳到系统的「通知使用权」列表。 */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "打开通知使用权设置失败", it) }
        }
    }
}
