package com.eta.autoledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.eta.autoledger.service.HealthJobService
import com.eta.autoledger.ui.LedgerApp

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_AI_SETTINGS = "open_ai_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 幂等：每次冷启动确认自愈心跳还在（系统清理后台任务后会把它丢掉）
        HealthJobService.schedule(this)
        // 定时云备份（未配置 WebDAV 时任务空转，代价可忽略）
        com.eta.autoledger.service.BackupJobService.schedule(this)
        setContent {
            LedgerApp(openAiSettings = intent.getBooleanExtra(EXTRA_OPEN_AI_SETTINGS, false))
        }
    }
}