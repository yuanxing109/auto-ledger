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
import com.eta.autoledger.data.NotifyChannel
import com.eta.autoledger.data.NotificationRules
import com.eta.autoledger.data.NotifyRaw
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
 *   -> 投递到 IO 协程：[AutoLedgerIngestor.process] 统一去重、解析、入库和通知
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
    private var appPrefs: AppPrefs? = null

    /** 上次尝试重绑的时刻，用于 [REBIND_THROTTLE_MS] 节流。只在主线程（回调线程）读写。 */
    private var lastRebindAt = 0L

    private val ingestor by lazy { AutoLedgerIngestor(this) }

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
            children.forEach(::handleOne)
            return
        }
        handleOne(sbn)
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

    private fun handleOne(sbn: StatusBarNotification) {
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

        scope.launch {
            ingestor.process(
                sender = raw.pkg,
                text = raw.text,
                sourceType = IngestSourceType.NOTIFICATION,
                postTime = raw.postTime,
                title = raw.title,
                bigText = raw.bigText,
                subText = raw.subText,
            )
        }
    }

    private fun isChannelEnabled(prefs: AppPrefs, channel: NotifyChannel): Boolean = when (channel) {
        NotifyChannel.ALIPAY -> prefs.alipayEnabled
        NotifyChannel.WECHAT -> prefs.wechatEnabled
        NotifyChannel.SMS -> prefs.smsEnabled
        NotifyChannel.BANK, NotifyChannel.OTHER -> prefs.otherEnabled
    }

    companion object {
        private const val TAG = "AutoLedger"

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
