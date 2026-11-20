package com.eta.autoledger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.NotificationRules
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
 *   -> [NotificationRules.parseSms] 过滤营销/验证码、提金额、判收支、猜分类
 *   -> `LedgerRepository.addImported`（externalId 唯一索引去重）入库
 *   -> 入库成功时发一条可撤销的通知（走 [BillNotifier]，与通知监听同渠道）
 *
 * 广播里不能用主线程做 IO，用 `goAsync()` 把处理挪到 IO 协程，处理完必须 `finish()`，
 * 否则系统会判定广播超时（前台 10 秒）。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = AppPrefs(context)
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
        val expenseCats = CategoryStore.names(context, 0)
        val incomeCats = CategoryStore.names(context, 1)
        val otherCats = CategoryStore.names(context, 2)

        messages
            .groupBy { it.originatingAddress.orEmpty() }
            .forEach { (sender, parts) ->
                val body = parts.mapNotNull { it.messageBody }.joinToString("")
                if (body.isBlank()) return@forEach
                val postTime = parts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                val bill = NotificationRules.parseSms(
                    sender = sender,
                    body = body,
                    postTime = postTime,
                    expenseCats = expenseCats,
                    incomeCats = incomeCats,
                    otherCats = otherCats,
                ) ?: run {
                    Log.d(TAG, "短信未识别为账单：$sender / $body")
                    return@forEach
                }

                // 主键交给 Room 自增（传 0L），别用时间戳，否则同毫秒两笔会被 REPLACE 覆盖
                val added = runCatching {
                    LedgerRepository.addImported(context, listOf(bill.toEntry(0L)))
                }.getOrElse {
                    Log.w(TAG, "短信账单入库失败", it)
                    0
                }
                if (added > 0) {
                    AppPrefs(context).lastSmsAt = System.currentTimeMillis()
                    Log.i(TAG, "短信记账：${bill.source} ${bill.amount} ${bill.category}")
                    BillNotifier.notifyAdded(context, bill)
                }
            }
    }

    private companion object {
        private const val TAG = "AutoLedger"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}