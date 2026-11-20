package com.eta.autoledger.screenshot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository

class ConfirmEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (intent.action == ACTION_CANCEL) {
            manager.cancel(ScreenshotCaptureService.RESULT_ID)
            return
        }
        if (intent.action == ACTION_CONFIRM) {
            val time = intent.getStringExtra("time").orEmpty().ifBlank { LedgerRepository.formatTime() }
            LedgerRepository.add(
                context,
                LedgerEntry(
                    id = System.currentTimeMillis(),
                    amount = intent.getDoubleExtra("amount", 0.0),
                    type = intent.getStringExtra("type") ?: "expense",
                    category = intent.getStringExtra("category") ?: "其他",
                    time = time,
                    note = intent.getStringExtra("note") ?: "",
                    source = "截屏记账",
                ),
            )
            manager.cancel(ScreenshotCaptureService.RESULT_ID)
            Toast.makeText(context, "已入账", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_CONFIRM = "com.eta.autoledger.CONFIRM_ENTRY"
        const val ACTION_CANCEL = "com.eta.autoledger.CANCEL_ENTRY"
    }
}