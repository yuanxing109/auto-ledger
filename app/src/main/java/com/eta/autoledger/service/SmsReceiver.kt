package com.eta.autoledger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerDatabase
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.NotificationRules
import com.eta.autoledger.data.NotifyRaw
import com.eta.autoledger.data.ParsePipeline
import com.eta.autoledger.data.SeenStore
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
 *   -> [SeenStore] 查持久化去重表，命中直接跳过
 *   -> [ParsePipeline.parseSms]：先跑 `rule` 表里的动态规则，未命中回退内置短信规则
 *   -> `LedgerRepository.addAutoParsed`（externalId 唯一索引 + 窗口去重）入库
 *   -> 入库成功时发一条可撤销的通知（走 [BillNotifier]，与通知监听同渠道）
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
        val expenseCats = CategoryStore.names(context, 0)
        val incomeCats = CategoryStore.names(context, 1)
        val otherCats = CategoryStore.names(context, 2)

        messages
            .groupBy { it.originatingAddress.orEmpty() }
            .forEach { (sender, parts) ->
                // 归一化必须在「拼完正文的第一现场」做：全角 `１，０００．００元`
                // 会被金额正则整条漏掉。放在这里还能保证后续算 externalId 时
                // 用的就是归一化后的文本 —— 归一化若发生在指纹之后，同一笔钱会得到
                // 两个 externalId，去重直接失效。
                val body = NotificationRules.normalize(
                    parts.mapNotNull { it.messageBody }.joinToString(""),
                )
                if (body.isBlank()) return@forEach
                val postTime = parts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                val prefs = AppPrefs.get(context)

                // 持久化去重的键必须是**原文**，且要包含 postTime：
                // 银行催收、额度提醒这类模板短信每月都会重复一次，只按正文算键会把
                // 下个月的真实扣费也当成重复丢掉。
                val seenKey = NotificationRules.dedupeKey(
                    NotifyRaw(
                        pkg = sender,
                        title = sender,
                        text = body,
                        bigText = "",
                        postTime = postTime,
                    ),
                )
                if (SeenStore.contains(context, seenKey)) {
                    Log.d(TAG, "持久化去重命中，跳过短信：$sender")
                    return@forEach
                }

                val bill = ParsePipeline.parseSms(
                    sender = sender,
                    body = body,
                    postTime = postTime,
                    expenseCats = expenseCats,
                    incomeCats = incomeCats,
                    otherCats = otherCats,
                    ignoreKeywords = prefs.ignoreKeywords,
                    forceKeywords = prefs.forceKeywords,
                    dao = LedgerDatabase.get(context).ruleDao(),
                ) ?: run {
                    Log.d(TAG, "短信未识别为账单：$sender / $body")
                    return@forEach
                }

                // 规则被设成「只识别不记账」：认得出来，但不入库，仍记去重键避免重复解析
                if (!bill.autoRecord) {
                    Log.i(TAG, "规则要求不自动记账：${bill.source} ${bill.amount} ${bill.category}")
                    SeenStore.remember(context, seenKey)
                    return@forEach
                }

                // 主键交给 Room 自增（传 0L），别用时间戳，否则同毫秒两笔会被 REPLACE 覆盖
                // 短信与通知通道可能把同一笔扣费各送一次：入库前先做跨渠道窗口去重
                val duplicate = runCatching {
                    LedgerRepository.isRecentDuplicate(context, bill.amount, bill.type, bill.source, bill.timeMillis)
                }.getOrElse { false }
                if (duplicate) {
                    Log.d(TAG, "窗口内已有同额自动账单，跳过：${bill.source} ${bill.amount}")
                    return@forEach
                }
                val added = runCatching {
                    LedgerRepository.addAutoParsed(context, listOf(bill.toEntry(0L)))
                }.getOrElse {
                    Log.w(TAG, "短信账单入库失败", it)
                    0
                }
                if (added > 0) {
                    AppPrefs.get(context).lastSmsAt = System.currentTimeMillis()
                    Log.i(TAG, "短信记账：${bill.source} ${bill.amount} ${bill.category}")
                    // 只在真入库之后落键，与通知通道保持一致
                    SeenStore.remember(context, seenKey)
                    BillNotifier.notifyAdded(context, bill)
                }
            }
    }

    private companion object {
        private const val TAG = "AutoLedger"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}