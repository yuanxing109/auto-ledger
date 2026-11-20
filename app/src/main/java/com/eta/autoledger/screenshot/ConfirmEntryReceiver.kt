package com.eta.autoledger.screenshot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ConfirmEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (intent.action == ACTION_CANCEL) {
            manager.cancel(ScreenshotCaptureService.RESULT_ID)
            return
        }
        if (intent.action != ACTION_CONFIRM) return

        val time = intent.getStringExtra("time").orEmpty().ifBlank { LedgerRepository.formatTime() }
        val entry = LedgerEntry(
            id = 0L,
            amount = intent.getDoubleExtra("amount", 0.0),
            type = intent.getStringExtra("type") ?: "expense",
            category = intent.getStringExtra("category") ?: "其他",
            time = time,
            note = intent.getStringExtra("note") ?: "",
            source = "截屏记账",
        )
        // 入库是磁盘 IO，不能卡在广播主线程上。
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { LedgerRepository.add(context, entry) }
                    .onFailure { Log.w(TAG, "截屏记账入库失败", it) }
                manager.cancel(ScreenshotCaptureService.RESULT_ID)
                Toast.makeText(context, "已入账", Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoLedger"
        const val ACTION_CONFIRM = "com.eta.autoledger.CONFIRM_ENTRY"
        const val ACTION_CANCEL = "com.eta.autoledger.CANCEL_ENTRY"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}