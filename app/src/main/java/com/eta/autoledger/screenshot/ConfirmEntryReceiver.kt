package com.eta.autoledger.screenshot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.PendingIntent
import com.eta.autoledger.service.BillNotifier
import com.eta.autoledger.service.UndoBillReceiver
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
                val newId = runCatching { LedgerRepository.add(context, entry) }
                    .onFailure { Log.w(TAG, "截屏记账入库失败", it) }
                    .getOrDefault(-1L)
                manager.cancel(ScreenshotCaptureService.RESULT_ID)
                if (newId > 0L) {
                    showConfirmed(context, manager, entry, newId)
                } else {
                    Log.w(TAG, "截屏记账未入库（失败或被 IGNORE），不发通知")
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 入库成功后发「已入账」通知，带「撤销」动作。
     *
     * 通知 id 固定为 [CONFIRMED_ID]：连续截图记账会覆盖同一条通知，
     * 不会在通知栏堆积。撤销 PendingIntent 带 `entry_id`，由 [UndoBillReceiver] 按 id 删。
     *
     * Android 13+ 未授予 POST_NOTIFICATIONS 时通知发不出去，退回 Toast。
     */
    private fun showConfirmed(
        context: Context,
        manager: NotificationManager,
        entry: LedgerEntry,
        entryId: Long,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Toast.makeText(context, "已入账", Toast.LENGTH_SHORT).show()
            return
        }
        BillNotifier.ensureChannel(context)

        val undoIntent = Intent(context, UndoBillReceiver::class.java).apply {
            action = UndoBillReceiver.ACTION_UNDO
            putExtra(UndoBillReceiver.EXTRA_ENTRY_ID, entryId)
            putExtra(UndoBillReceiver.EXTRA_NOTIFY_ID, CONFIRMED_ID)
        }
        val undoPending = PendingIntent.getBroadcast(
            context,
            REQ_UNDO,
            undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = "${entry.category} ¥ %,.2f".format(entry.amount)
        val notification = NotificationCompat.Builder(context, BillNotifier.CHANNEL_AUTO)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("已入账")
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "撤销", undoPending)
            .build()
        runCatching { manager.notify(CONFIRMED_ID, notification) }
            .onFailure { Log.w(TAG, "已入账通知发送失败", it) }
    }

    companion object {
        private const val TAG = "AutoLedger"
        /** 已入账通知的固定 id：连续截图会覆盖同一条，不堆积。 */
        const val CONFIRMED_ID = ScreenshotCaptureService.RESULT_ID + 1
        /** 撤销 PendingIntent 的 requestCode。 */
        private const val REQ_UNDO = 9101
        const val ACTION_CONFIRM = "com.eta.autoledger.CONFIRM_ENTRY"
        const val ACTION_CANCEL = "com.eta.autoledger.CANCEL_ENTRY"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}