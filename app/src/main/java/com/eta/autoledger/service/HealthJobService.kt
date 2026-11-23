package com.eta.autoledger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.eta.autoledger.R
import com.eta.autoledger.data.LedgerDatabase
import com.eta.autoledger.util.SystemAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自愈心跳。
 *
 * 做两件很便宜的事：
 *  1. **静默写回**：如果用户用 adb / Shizuku 授予过 `WRITE_SECURE_SETTINGS`，
 *     就把被 ROM 关掉的「通知使用权」「无障碍」重新写回 Secure 设置。
 *  2. **只读复检**：没有写权限时，仍然检查「通知使用权」是否被关掉了。
 *     关掉了只发一条通知提示用户去修复，**绝不偷偷写回** ——
 *     没有授权却反复弹提示已经够烦，再绕过用户改系统设置就越界了。
 *
 * 为什么用 JobScheduler 而不是常驻前台服务：
 * 1. 前台服务要挂一条常驻通知，用户会烦；JobScheduler 不需要。
 * 2. 进程被系统杀掉后，JobScheduler 仍会在下个周期重新拉起本进程，
 *    顺带让 NotificationListenerService 重新绑定一次 —— 等于免费的“复活机会”。
 */
class HealthJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            val healed = runCatching { SystemAccess.ensureGrants(this@HealthJobService) }
                .getOrElse { false to false }
            if (healed.first || healed.second) {
                Log.i(
                    TAG,
                    "自愈完成：通知使用权=${healed.first}，无障碍=${healed.second}",
                )
            }
            // 写回失败（没权限）或被 ROM 再次关掉时，退一步做只读复检并提示用户
            runCatching { checkListenerOnce(this@HealthJobService) }
                .onFailure { Log.w(TAG, "通知使用权复检失败", it) }
            // 持久化去重表的过期清理。放在心跳里而不是每次写入时清：
            // 写入路径要尽量短（通知回调有 10 秒预算），删除是低频、可延迟的事。
            runCatching { LedgerDatabase.pruneSeenKeys(this@HealthJobService) }
                .onSuccess { if (it > 0) Log.i(TAG, "清理过期去重键 $it 条") }
                .onFailure { Log.w(TAG, "清理去重键失败", it) }
            params?.let { jobFinished(it, false) }
        }
        // true = 交给后台线程，稍后自己调 jobFinished
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    /**
     * 覆盖安装后立刻复检一次「通知使用权」。
     *
     * 为什么需要它：覆盖安装会让部分 ROM 把本应用从 `enabled_notification_listeners`
     * 里摘掉（尤其是把安装当成「新包」处理的机型），而 JobScheduler 的下一次心跳
     * 最远要等 15 分钟 —— 这段时间所有通知都收不到，用户完全无感。
     * 监听 `MY_PACKAGE_REPLACED` 把这段空窗压到安装完成的瞬间。
     *
     * 做成 [HealthJobService] 的嵌套类（Kotlin 的嵌套类默认是 static），
     * 这样不用为一个 10 行的检查新建文件，Manifest 里写 `$` 就能引用。
     */
    class PackageReplacedReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val app = context.applicationContext
            runCatching { checkListenerOnce(app) }
                .onFailure { Log.w(TAG, "覆盖安装后复检通知使用权失败", it) }
        }
    }

    companion object {
        private const val TAG = "AutoLedger"
        private const val JOB_ID = 4301

        /** 权限提示通知渠道。与「自动记账」分渠道，用户不想看可以单独关掉，不牵连账单通知。 */
        private const val CHANNEL_ALERT = "permission_alert"

        /** 提示通知 id。固定 id，重复提示只覆盖不堆叠。 */
        private const val ALERT_ID = 4401

        /**
         * 两次提示之间的最小间隔。
         *
         * 15 分钟一次心跳、每次都提示等于每刻钟骚扰一遍；12 小时既能保证用户在
         * 隔天打开手机时看到，又不会让人想把应用卸掉。
         */
        private const val ALERT_INTERVAL_MS = 12 * 60 * 60 * 1000L

        /** 记录「用户曾经授予过通知使用权」，用来区分「从没授权」和「授权后被关掉」。 */
        private const val SP_NAME = "health_watch"
        private const val KEY_EVER_GRANTED = "listener_ever_granted"
        private const val KEY_LAST_ALERT = "listener_last_alert_at"

        /**
         * 只读复检「通知使用权」。
         *
         * 三种情况：
         *  - 当前已授予 → 记下「曾经授权过」，并清掉已发的提示，什么都不做；
         *  - 从未授予过 → 什么都不做（新用户还没走到授权引导，提示只会造成误解）；
         *  - 授权过但现在被关掉 → 发一条可点击修复的通知（12 小时内最多一条）。
         */
        fun checkListenerOnce(context: Context) {
            val granted = SystemAccess.notificationListenerGranted(context)
            val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            if (granted) {
                if (!sp.getBoolean(KEY_EVER_GRANTED, false)) {
                    sp.edit().putBoolean(KEY_EVER_GRANTED, true).apply()
                }
                // 恢复了就把残留提示撤掉，别让用户点进一个已经不需要修的页面
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(ALERT_ID)
                return
            }
            if (!sp.getBoolean(KEY_EVER_GRANTED, false)) return

            val now = System.currentTimeMillis()
            if (now - sp.getLong(KEY_LAST_ALERT, 0L) < ALERT_INTERVAL_MS) return
            sp.edit().putLong(KEY_LAST_ALERT, now).apply()
            Log.w(TAG, "通知使用权已被关闭，发送修复提示")
            postAlert(context)
        }

        /** 发一条「通知权限被关闭，点击修复」的提示。点通知直达系统的通知使用权列表。 */
        private fun postAlert(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ALERT,
                        "权限提示",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "通知使用权被关闭时的提醒" },
                )
            }
            val fix = PendingIntent.getActivity(
                context,
                ALERT_ID,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("通知权限被关闭")
                .setContentText("自动记账收不到 App 通知了，点这里重新开启")
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "系统或省电策略关掉了「通知使用权」，这段时间的支付宝 / 微信 / 银行通知不会被记账。\n" +
                            "点这条通知直达设置页，重新勾选本应用即可；已经漏掉的账可以手动补记。",
                    ),
                )
                .setContentIntent(fix)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(ALERT_ID, notification) }
                .onFailure { Log.w(TAG, "权限提示通知发送失败", it) }
        }

        /** JobScheduler 允许的最小周期就是 15 分钟，再短系统会拉长。 */
        private const val PERIOD_MS = 15 * 60 * 1000L

        /**
         * 幂等调度：重复调用只会保留同一个任务，不会堆积。
         * 在 [com.eta.autoledger.MainActivity.onCreate] 里调一次即可 ——
         * 进程被拉起时不依赖 Application 类，避免为了这点事多一个类。
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, HealthJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setRequiresCharging(false)
                .setPersisted(false)
                .build()
            runCatching { scheduler.schedule(info) }
                .onFailure { Log.w(TAG, "心跳任务调度失败", it) }
        }
    }
}