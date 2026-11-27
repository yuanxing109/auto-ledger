package com.eta.autoledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.eta.autoledger.service.HealthJobService
import com.eta.autoledger.ui.LedgerApp
import com.eta.autoledger.ui.AppRoot
import com.eta.autoledger.ui.share.ReportShareExporter

class MainActivity : ComponentActivity() {

    private var openAddRequest by mutableIntStateOf(0)

    companion object {
        const val EXTRA_OPEN_AI_SETTINGS = "open_ai_settings"
        const val EXTRA_OPEN_ADD = "open_add"
        private const val REQ_POST_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 清理上次进程异常结束时遗留的过期分享图片。
        ReportShareExporter.cleanup(this)
        // 加密存储（AI Key / WebDAV 密码）首次初始化要走 Android Keystore，
        // 提前在后台线程预热，避免用户点进设置页时才在主线程付这笔开销。
        SecurePrefs.warmUp(this)
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
        consumeOpenAdd(intent)
        setContent {
            AppRoot {
                LedgerApp(
                    openAiSettings = intent.getBooleanExtra(EXTRA_OPEN_AI_SETTINGS, false),
                    openAddRequest = openAddRequest,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenAdd(intent)
    }

    override fun onDestroy() {
        ReportShareExporter.cleanup(this)
        super.onDestroy()
    }

    private fun consumeOpenAdd(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true) {
            openAddRequest++
            // 这是一次性导航事件；清理后 Activity 重建不会再次把用户切到「记一笔」。
            intent.removeExtra(EXTRA_OPEN_ADD)
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
