package com.eta.autoledger.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.BackupRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 定时云备份。
 *
 * 用 JobScheduler 而不是 WorkManager：项目没有引入 WorkManager 依赖，
 * 而且这里只需要「周期跑一次、失败就算了」的语义，JobService 足够。
 * 周期设 6 小时，真正是否上传由 [BackupRunner.autoBackupIfDue] 判断（≥20 小时才传），
 * 这样系统把任务提前叫醒也不会把云端写满。
 */
class BackupJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        scope.launch {
            val result = runCatching {
                BackupRunner.autoBackupIfDue(app, AppPrefs(app))
            }.getOrNull()
            result?.let { if (!it.ok) Log.w(TAG, "定时备份失败：${it.message}") }
            params?.let { jobFinished(it, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "AutoLedger"
        private const val JOB_ID = 4302
        private const val PERIOD_MS = 6L * 60 * 60 * 1000

        /** 幂等；每次启动 App 调一次，系统清理后台任务后这里会补回来。 */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .build()
            runCatching { scheduler.schedule(job) }
        }
    }
}