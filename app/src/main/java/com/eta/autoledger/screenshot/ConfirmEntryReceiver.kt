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
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.CategoryInference
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.MatchSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class ConfirmEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (intent.action == ACTION_CANCEL) {
            manager.cancel(ScreenshotCaptureService.RESULT_ID)
            return
        }
        if (intent.action != ACTION_CONFIRM) return

        val parsed = intent.getStringExtra(EXTRA_ENTRIES_JSON)
            ?.let(VisionHelper::decodeEntries)
            ?.takeIf { it.isNotEmpty() }
            ?: legacyEntry(intent)
                ?.let(::listOf)
                .orEmpty()
        if (parsed.isEmpty()) {
            manager.cancel(ScreenshotCaptureService.RESULT_ID)
            return
        }
        val token = intent.getStringExtra(EXTRA_CONFIRM_TOKEN).orEmpty().ifBlank {
            "legacy:" + UUID.randomUUID().toString()
        }
        val entries = VisionHelper.buildLedgerEntries(
            entries = parsed.map { normalizeEntry(context, it) },
            externalIdPrefix = "screenshot:$token",
        )
        // 先移除待确认通知，快速双击时第二下也不会再显示旧操作入口。
        manager.cancel(ScreenshotCaptureService.RESULT_ID)
        // 入库是磁盘 IO，不能卡在广播主线程上。
        val pending = goAsync()
        scope.launch {
            try {
                val ids = runCatching { LedgerRepository.addAll(context, entries) }
                    .onFailure { Log.w(TAG, "截屏记账入库失败", it) }
                    .getOrDefault(emptyList())
                val inserted = entries.zip(ids)
                    .filter { (_, id) -> id > 0L }
                val entryIds = inserted.map { (_, id) -> id }
                manager.cancel(ScreenshotCaptureService.RESULT_ID)
                if (inserted.isNotEmpty()) {
                    showConfirmed(context, manager, inserted.map { it.first }, entryIds)
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
     * 不会在通知栏堆积。撤销 PendingIntent 携带整组 `entry_ids`，由 [UndoBillReceiver] 批量删除。
     *
     * Android 13+ 未授予 POST_NOTIFICATIONS 时通知发不出去，退回 Toast。
     */
    private fun showConfirmed(
        context: Context,
        manager: NotificationManager,
        entries: List<LedgerEntry>,
        entryIds: List<Long>,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Toast.makeText(context, "已入账", Toast.LENGTH_SHORT).show()
            return
        }
        BillNotifier.ensureChannel(context)

        val undoIntent = Intent(context, UndoBillReceiver::class.java).apply {
            action = UndoBillReceiver.ACTION_UNDO
            putExtra(UndoBillReceiver.EXTRA_ENTRY_IDS, entryIds.toLongArray())
            putExtra(UndoBillReceiver.EXTRA_NOTIFY_ID, CONFIRMED_ID)
        }
        val undoPending = PendingIntent.getBroadcast(
            context,
            REQ_UNDO,
            undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = if (entries.size == 1) {
            val entry = entries.single()
            val categoryLabel = entry.subCategory?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { "${entry.category} · $it" }
                ?: entry.category
            "$categoryLabel ¥ %,.2f".format(entry.amount)
        } else {
            "${entries.size} 笔账单，共 ¥ %,.2f".format(entries.sumOf { it.amount })
        }
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
        const val EXTRA_ENTRIES_JSON = "entries_json"
        const val EXTRA_CONFIRM_TOKEN = "confirm_token"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun normalizeEntry(context: Context, parsed: ParsedEntry): ParsedEntry {
            val type = if (parsed.type == "income") "income" else "expense"
            val available = CategoryStore.names(context, typeIndex(type))
            val inferred = CategoryInference.infer(parsed.note, available)
            val category = if (inferred.source != MatchSource.FALLBACK) {
                inferred.category
            } else {
                parsed.category.takeIf { it in available }
                    ?: available.firstOrNull { it == "其他" }
                    ?: available.firstOrNull()
                    ?: "其他"
            }
            val subCategory = if (inferred.source != MatchSource.FALLBACK) {
                CategoryStore.validSubCategory(category, inferred.subCategory)
            } else {
                CategoryStore.validSubCategory(category, parsed.subCategory)
            }
            return parsed.copy(
                amount = parsed.amount,
                type = type,
                category = category,
                subCategory = subCategory,
                time = parsed.time.ifBlank { LedgerRepository.formatTime() },
                note = parsed.note,
            )
        }

        private fun legacyEntry(intent: Intent): ParsedEntry? {
            val amount = intent.getDoubleExtra("amount", 0.0)
            if (!amount.isFinite() || amount <= 0.0) return null
            return ParsedEntry(
                amount = amount,
                type = intent.getStringExtra("type") ?: "expense",
                category = intent.getStringExtra("category") ?: "其他",
                subCategory = intent.getStringExtra("subCategory"),
                time = intent.getStringExtra("time").orEmpty(),
                note = intent.getStringExtra("note").orEmpty(),
            )
        }

        private fun typeIndex(type: String): Int = when (type) {
            "income" -> 1
            "other" -> 2
            else -> 0
        }
    }
}
