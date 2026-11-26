package com.eta.autoledger.data

import kotlin.math.abs

/** 转账成对识别的纯规则，不依赖 Android/Room，便于严格单测。 */
object TransferPairMatcher {

    const val WINDOW_MS = 5L * 60_000L

    private val strongKeywords = listOf(
        "还款",
        "转入",
        "转出",
        "信用卡",
        "微信转账",
    )

    private val negativeKeywords = listOf(
        "退款",
        "撤销",
        "冲正",
        "失败",
        "关闭",
    )

    fun hasTransferSemantics(entry: LedgerEntry): Boolean {
        val text = entry.note + " " + entry.category
        return strongKeywords.any(text::contains) && negativeKeywords.none(text::contains)
    }

    /** 转账分类本身也属于强语义；这样“信用卡还款”等标题落进分类时不会漏配。 */
    fun hasTransferCategory(entry: LedgerEntry): Boolean = entry.category == "转账"

    fun isOppositeType(first: LedgerEntry, second: LedgerEntry): Boolean =
        (first.type == "expense" && second.type == "income") ||
            (first.type == "income" && second.type == "expense")

    /**
     * 判定两笔账单是否构成内部转账。
     *
     * 金额必须精确到分，方向必须相反，时间差不超过 5 分钟，并且至少一笔含强语义词。
     * 两笔都带同一卡尾时拒绝配对，避免把同一账户的退款/冲正误认成跨账户转账。
     */
    fun matches(first: LedgerEntry, second: LedgerEntry): Boolean {
        if (first.id == second.id || first.isTransfer || second.isTransfer) return false
        if (first.isRecurring || second.isRecurring) return false
        if (first.type !in TRANSFER_TYPES || second.type !in TRANSFER_TYPES) return false
        if (!isOppositeType(first, second)) return false
        if (abs(FingerprintCodec.amountFen(first.amount)) !=
            abs(FingerprintCodec.amountFen(second.amount))
        ) return false
        if (first.timeMillis <= 0L || second.timeMillis <= 0L) return false
        if (abs(first.timeMillis - second.timeMillis) > WINDOW_MS) return false
        if (!hasTransferSemantics(first) && !hasTransferSemantics(second) &&
            !hasTransferCategory(first) && !hasTransferCategory(second)
        ) return false
        if (negativeKeywords.any { it in first.note || it in first.category } ||
            negativeKeywords.any { it in second.note || it in second.category }
        ) return false
        val firstTail = ParseDedup.accountTailFromText(first.note)
        val secondTail = ParseDedup.accountTailFromText(second.note)
        if (firstTail != null && firstTail == secondTail) return false
        return true
    }

    /**
     * 选出唯一候选。时间差最小优先，id 最小作为稳定次序；
     * 若不同候选具有相同的最小时间差，宁可放弃，也不随机猜测。
     */
    fun chooseCandidate(entry: LedgerEntry, candidates: List<LedgerEntry>): LedgerEntry? {
        val eligible = candidates.filter { matches(entry, it) }
        if (eligible.isEmpty()) return null
        val ordered = eligible.sortedWith(
            compareBy<LedgerEntry> { abs(it.timeMillis - entry.timeMillis) }.thenBy { it.id },
        )
        val bestGap = abs(ordered.first().timeMillis - entry.timeMillis)
        val tied = ordered.count { abs(it.timeMillis - entry.timeMillis) == bestGap }
        return if (tied == 1) ordered.first() else null
    }

    /** 规划一批新记录的内部配对；同批候选优先于库内旧记录。 */
    fun chooseLocalPairs(rows: List<LedgerEntry>): List<Pair<LedgerEntry, LedgerEntry>> {
        val pending = rows.toMutableList()
        val pairs = mutableListOf<Pair<LedgerEntry, LedgerEntry>>()
        while (pending.isNotEmpty()) {
            val first = pending.removeAt(0)
            val matched = chooseCandidate(first, pending) ?: continue
            pending.removeAll { it.id == matched.id }
            pairs += first to matched
        }
        return pairs
    }

    private val TRANSFER_TYPES = setOf("expense", "income")
}
