package com.eta.autoledger.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.eta.autoledger.data.LedgerRepository

/** 自动记账通知上的「撤销」按钮：删掉刚才自动入库的那一笔。 */
class UndoBillReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return
        // 自动入库走的是 addImported（REPLACE 插入，不回填主键），所以按 externalId 定位而不是 id
        val externalId = intent.getStringExtra(EXTRA_EXTERNAL_ID).orEmpty()
        if (externalId.isEmpty()) return
        val removed = runCatching { LedgerRepository.deleteByExternalId(context, externalId) }
            .onFailure { Log.w(TAG, "撤销自动账单失败", it) }
            .getOrDefault(0)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID)
        val text = if (removed > 0) "已撤销这笔自动记账" else "这笔账单已不存在"
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AutoLedger"
        const val ACTION_UNDO = "com.eta.autoledger.action.UNDO_BILL"
        const val EXTRA_EXTERNAL_ID = "external_id"
        const val NOTIFY_ID = 9100
    }
}