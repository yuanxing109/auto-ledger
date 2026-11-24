package com.eta.autoledger.service

import android.content.Context
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.FingerprintCodec
import com.eta.autoledger.data.LedgerDatabase
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.NotificationRules
import com.eta.autoledger.data.NotifyBill
import com.eta.autoledger.data.NotifyRaw
import com.eta.autoledger.data.ParsePipeline
import com.eta.autoledger.data.SeenStore

enum class IngestSourceType {
    SMS,
    NOTIFICATION,
}

/** 短信与通知共用的自动记账用例，统一去重、解析、入库和结果通知。 */
class AutoLedgerIngestor(context: Context) {

    private val context = context.applicationContext

    suspend fun process(
        sender: String,
        text: String,
        sourceType: IngestSourceType,
        postTime: Long = System.currentTimeMillis(),
        title: String = sender,
        bigText: String = "",
        subText: String = "",
    ) {
        val raw = NotifyRaw(
            pkg = sender,
            title = title,
            text = if (sourceType == IngestSourceType.SMS) NotificationRules.normalize(text) else text,
            bigText = bigText,
            postTime = postTime,
            subText = subText,
        )
        val seenKey = NotificationRules.dedupeKey(raw)
        if (isSeen(seenKey)) return
        if (SeenStore.contains(context, seenKey)) return

        val prefs = AppPrefs.get(context)
        val bill = when (sourceType) {
            IngestSourceType.SMS -> ParsePipeline.parseSms(
                sender = sender,
                body = raw.text,
                postTime = postTime,
                expenseCats = CategoryStore.names(context, 0),
                incomeCats = CategoryStore.names(context, 1),
                otherCats = CategoryStore.names(context, 2),
                ignoreKeywords = prefs.ignoreKeywords,
                forceKeywords = prefs.forceKeywords,
                dao = LedgerDatabase.get(context).ruleDao(),
            )

            IngestSourceType.NOTIFICATION -> ParsePipeline.parse(
                raw = raw,
                expenseCats = CategoryStore.names(context, 0),
                incomeCats = CategoryStore.names(context, 1),
                otherCats = CategoryStore.names(context, 2),
                ignoreKeywords = prefs.ignoreKeywords,
                forceKeywords = prefs.forceKeywords,
                dao = LedgerDatabase.get(context).ruleDao(),
            )
        } ?: return

        val mergeKey = mergeKeyOf(bill)
        if (sourceType == IngestSourceType.NOTIFICATION && isRecentlyMerged(mergeKey)) return

        if (!bill.autoRecord) {
            markSeen(seenKey)
            if (sourceType == IngestSourceType.NOTIFICATION) markMerged(mergeKey)
            SeenStore.remember(context, seenKey)
            return
        }

        val added = runCatching {
            LedgerRepository.addAutoParsed(context, listOf(bill.toEntry(0L)))
        }.getOrElse {
            Log.w(TAG, "自动账单入库失败", it)
            0
        }
        if (added <= 0) return

        when (sourceType) {
            IngestSourceType.SMS -> prefs.lastSmsAt = System.currentTimeMillis()
            IngestSourceType.NOTIFICATION -> prefs.lastNotifyAt = System.currentTimeMillis()
        }
        markSeen(seenKey)
        if (sourceType == IngestSourceType.NOTIFICATION) markMerged(mergeKey)
        SeenStore.remember(context, seenKey)
        BillNotifier.notifyAdded(context, bill)
    }

    private fun mergeKeyOf(bill: NotifyBill): String =
        "${bill.channel}|${bill.type}|${FingerprintCodec.amountFenToken(bill.amount)}|${bill.note.take(12)}"

    private fun isSeen(key: String): Boolean = synchronized(seen) { seen.containsKey(key) }

    private fun markSeen(key: String) {
        synchronized(seen) { seen[key] = System.currentTimeMillis() }
    }

    private fun isRecentlyMerged(key: String): Boolean = synchronized(merged) {
        val at = merged[key] ?: return false
        if (System.currentTimeMillis() - at > MERGE_WINDOW_MS) {
            merged.remove(key)
            false
        } else {
            true
        }
    }

    private fun markMerged(key: String) {
        val now = System.currentTimeMillis()
        synchronized(merged) {
            merged.entries.removeAll { now - it.value > MERGE_WINDOW_MS }
            merged[key] = now
        }
    }

    private companion object {
        const val TAG = "AutoLedger"
        const val MAX_SEEN = 200
        const val MERGE_WINDOW_MS = 10_000L

        val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(entry: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > MAX_SEEN
        }
        val merged = HashMap<String, Long>()
    }
}
