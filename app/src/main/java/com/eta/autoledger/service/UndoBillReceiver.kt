package com.eta.autoledger.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.eta.autoledger.data.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 自动记账通知上的「撤销」按钮：删掉刚才自动入库的那一笔。 */
class UndoBillReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return

        // 两条链路：
        //  1) 自动记账通知 —— 按 external_id 删（addImported 按单号去重，没有自增主键可用）
        //  2) 截图记账通知 —— 按自增主键 entry_id 删（截图记账的 LedgerEntry 没有 externalId）
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        if (entryId > 0L) {
            undoByEntryId(context, intent, entryId)
            return
        }
        val externalId = intent.getStringExtra(EXTRA_EXTERNAL_ID).orEmpty()
        if (externalId.isEmpty()) return

        val pending = goAsync()
        scope.launch {
            try {
                val removed = runCatching { LedgerRepository.deleteByExternalId(context, externalId) }
                    .onFailure { Log.w(TAG, "撤销自动账单失败", it) }
                    .getOrDefault(0)
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(intent.getIntExtra(EXTRA_NOTIFY_ID, NOTIFY_ID))
                val text = if (removed > 0) "已撤销这笔自动记账" else "这笔账单已不存在"
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }

    /** 截图记账的撤销：按自增主键删，取消 [ConfirmEntryReceiver.CONFIRMED_ID] 那条通知。 */
    private fun undoByEntryId(context: Context, intent: Intent, entryId: Long) {
        val pending = goAsync()
        scope.launch {
            try {
                val removed = runCatching { LedgerRepository.delete(context, entryId) }
                    .onFailure { Log.w(TAG, "撤销截图记账失败", it) }
                    .getOrNull()
                val ok = removed != null
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(intent.getIntExtra(EXTRA_NOTIFY_ID, NOTIFY_ID))
                val text = if (ok) "已撤销这笔截图记账" else "这笔账单已不存在"
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoLedger"
        const val ACTION_UNDO = "com.eta.autoledger.action.UNDO_BILL"
        const val EXTRA_EXTERNAL_ID = "external_id"
        /** 截图记账撤销用：自增主键（截图记账的 LedgerEntry 没有 externalId）。 */
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_NOTIFY_ID = "notify_id"
        /** 通知 id 的兜底常量（也用于 0 hash 冲突时）。 */
        const val NOTIFY_ID = 9100
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
