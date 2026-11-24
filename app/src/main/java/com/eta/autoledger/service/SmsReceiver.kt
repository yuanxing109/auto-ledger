package com.eta.autoledger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.eta.autoledger.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 短信记账：银行 955xx 服务号、信用卡消费短信、转账到账短信 -> 解析 -> 去重入库。
 *
 * 数据流：
 * `SMS_RECEIVED`（系统广播）
 *   -> 读「短信通知」开关（[AppPrefs.smsEnabled]），关闭直接返回
 *   -> 同一次广播里的多条 PDU 按发送方合并（长短信会被拆成多段，必须拼回去）
 *   -> [AutoLedgerIngestor.process]：统一去重、解析、入库和通知
 *
 * 广播里不能用主线程做 IO，用 `goAsync()` 把处理挪到 IO 协程，处理完必须 `finish()`，
 * 否则系统会判定广播超时（前台 10 秒）。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = AppPrefs.get(context)
        if (!prefs.smsEnabled) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?.filterNotNull()
            .orEmpty()
        if (messages.isEmpty()) return

        val appContext = context.applicationContext
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        scope.launch {
            try {
                handle(appContext, messages)
            } catch (t: Throwable) {
                Log.w(TAG, "短信记账失败", t)
            } finally {
                if (finished.compareAndSet(false, true)) pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, messages: List<SmsMessage>) {
        val ingestor = AutoLedgerIngestor(context)

        messages
            .groupBy { it.originatingAddress.orEmpty() }
            .forEach { (sender, parts) ->
                val body = parts.mapNotNull { it.messageBody }.joinToString("")
                if (body.isBlank()) return@forEach
                val postTime = parts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                ingestor.process(
                    sender = sender,
                    text = body,
                    sourceType = IngestSourceType.SMS,
                    postTime = postTime,
                )
            }
    }

    private companion object {
        private const val TAG = "AutoLedger"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
