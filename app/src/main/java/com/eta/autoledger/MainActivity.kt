package com.eta.autoledger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.eta.autoledger.service.HealthJobService
import com.eta.autoledger.ui.LedgerApp
import com.eta.autoledger.ui.AppRoot

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_AI_SETTINGS = "open_ai_settings"
        private const val REQ_POST_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 幂等：每次冷启动确认自愈心跳还在（系统清理后台任务后会把它丢掉）
        HealthJobService.schedule(this)
        // 定时云备份（未配置 WebDAV 时任务空转，代价可忽略）
        com.eta.autoledger.service.BackupJobService.schedule(this)
        // 幂等重排提醒：开机接收器之外再兜一层，避免闹钟被系统静默清空后长期失效
        com.eta.autoledger.reminder.ReminderScheduler.rescheduleAll(this)
        // 周期记账：先排每日任务，再立刻补跑一次（用户可能几天没开 App）
        com.eta.autoledger.service.RecurringWorker.schedule(this)
        com.eta.autoledger.service.RecurringWorker.runNow(this)
        // Android 13+ 通知权限：不在这里申请的话，自动记账/提醒通知会被静默丢弃
        ensureNotificationPermission()
        setContent {
            AppRoot {
                LedgerApp(openAiSettings = intent.getBooleanExtra(EXTRA_OPEN_AI_SETTINGS, false))
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }
}