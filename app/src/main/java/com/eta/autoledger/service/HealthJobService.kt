package com.eta.autoledger.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.eta.autoledger.util.SystemAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自愈心跳。
 *
 * 只做一件很便宜的事：如果用户用 adb / Shizuku 授予过 `WRITE_SECURE_SETTINGS`，
 * 就把被 ROM 关掉的「通知使用权」「无障碍」重新写回 Secure 设置。
 * 没授权就什么都不做 —— 所以它不会耗电，也不会在没有自愈能力时刷日志。
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
            params?.let { jobFinished(it, false) }
        }
        // true = 交给后台线程，稍后自己调 jobFinished
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "AutoLedger"
        private const val JOB_ID = 4301

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