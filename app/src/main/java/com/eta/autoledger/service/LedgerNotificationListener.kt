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
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.NotifyBill
import com.eta.autoledger.data.NotifyChannel
import com.eta.autoledger.data.NotificationRules
import com.eta.autoledger.data.NotifyRaw
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
 *   -> [NotificationRules.parse] 用正则与关键词提金额、判收支、猜分类
 *   -> 投递到 IO 协程，`LedgerRepository.addImported`（externalId 唯一索引去重）入库
 *   -> 入库成功且未开启「不提示」时，发一条可撤销的通知
 */
class LedgerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_SEEN
    }
    private var appPrefs: AppPrefs? = null

    /**
     * 120 秒窗口内「渠道 + 金额 + 方向」相同的账单只入一次。
     *
     * 折叠摘要到达时会把同组子通知捞一遍解析，随后系统自己也会把子通知逐个 post 过来；
     * 两条路径看到的是同一笔钱，靠这个窗口归并，而不是靠通知 key（每条 key 都不同）。
     */
    private val merged = HashMap<String, Long>()

    private fun prefs(): AppPrefs =
        appPrefs ?: AppPrefs(this).also { appPrefs = it }

    override fun onCreate() {
        super.onCreate()
        appPrefs = AppPrefs(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "notification listener disconnected")
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
        )
        if (raw.title.isBlank() && raw.text.isBlank() && raw.bigText.isBlank()) return

        val seenKey = NotificationRules.dedupeKey(raw)
        if (isDuplicate(seenKey)) return

        val bill = NotificationRules.parse(
            raw = raw,
            expenseCats = CategoryStore.names(this, 0),
            incomeCats = CategoryStore.names(this, 1),
            otherCats = CategoryStore.names(this, 2),
        ) ?: run {
            Log.d(TAG, "未识别为账单：${raw.title} / ${raw.text}")
            return
        }

        // 摘要补全与系统逐条回调可能把同一笔各送一次，短窗口内按内容归并
        val mergeKey = mergeKeyOf(bill)
        if (isRecentlyMerged(mergeKey)) {
            Log.d(TAG, "窗口内重复，跳过：$mergeKey")
            return
        }

        // 主键交给 Room 自增（传 0L），别用时间戳，否则同毫秒两笔会被 REPLACE 覆盖
        val entry = bill.toEntry(0L)
        scope.launch {
            val added = runCatching {
                LedgerRepository.addImported(this@LedgerNotificationListener, listOf(entry))
            }.getOrElse {
                Log.w(TAG, "通知账单入库失败", it)
                0
            }
            if (added > 0) {
                prefs().lastNotifyAt = System.currentTimeMillis()
                markSeen(seenKey)
                markMerged(mergeKey)
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

        /** 折叠补全的归并窗口：摘要与子通知几乎同秒到达，10s 既能兜住慢机型，也不会误吞连刷的同额真账。 */
        private const val MERGE_WINDOW_MS = 10_000L

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