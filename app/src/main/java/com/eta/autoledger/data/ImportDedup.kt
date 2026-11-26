package com.eta.autoledger.data

import android.content.Context
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.network.AiClient
import kotlin.math.abs
import kotlin.math.max

/**
 * 导入去重：先按「金额相同 + 时间相近」在本地挑出疑似重复，再用 AI 语义判定，
 * 最后交给调用方在预览页选择保留 / 跳过 / 合并。
 */
object ImportDedup {

    /** 时间窗：通知入账与 CSV 导出之间通常差几分钟，30 分钟足够覆盖又不至于误伤。 */
    const val WINDOW_MS = 30L * 60L * 1000L

    private const val AMOUNT_EPS = 0.005
    private const val NAME_THRESHOLD = 0.45

    /**
     * 宽松规则：金额相同 + 时间差 5 分钟以内 → 无条件判为疑似重复，不看商户名。
     *
     * 依据是「同一笔钱被两条通道各记一次」的真实形态：微信/支付宝的通知先入账，
     * 导出的 CSV 里时间只精确到秒甚至分钟，两者差几十秒到几分钟；
     * 但商户名一边是通知标题（「微信支付」）、一边是账单商品说明（「美团」），
     * 靠名字相似度根本对不上，只能靠「同金额 + 挨得很近」兜住。
     */
    private const val LOOSE_WINDOW_MS = 5L * 60L * 1000L

    /** 宽松规则只在最近 30 天的账目里比对，避免把几个月前的同额消费误判成重复。 */
    private const val LOOSE_LOOKBACK_MS = 30L * 24L * 60L * 60L * 1000L
    private const val AI_LIMIT = 12
    private const val AI_CONFIDENT_SCORE = 0.75

    enum class Action { KEEP, SKIP, MERGE }

    data class Suspect(
        val incoming: LedgerEntry,
        val existing: LedgerEntry,
        val minutesApart: Int,
        val nameScore: Double,
        val aiVerdict: Boolean? = null,
        val aiNote: String? = null,
    )

    data class Plan(
        val fresh: List<LedgerEntry>,
        val suspects: List<Suspect>,
        /** 解析出来但直接丢掉的条数：外部单号已在库里，或同一文件内重复。 */
        val skipped: Int = 0,
    ) {
        /** 文件里一共解析出多少条（含被跳过的）。 */
        val total: Int get() = skipped + fresh.size + suspects.size
        val duplicateCount: Int get() = suspects.size
        val isEmpty: Boolean get() = total == 0

        /** 预览弹窗顶部那句话，措辞固定，便于用户一眼看出三个数各自含义。 */
        val summary: String
            get() = "共解析 " + total + " 条，跳过老数据 " + skipped + " 条，" +
                "发现 " + suspects.size + " 条疑似与其他来源重复"
    }

    data class CommitResult(val added: Int, val merged: Int, val skipped: Int)

    /** 时间戳缺失的账目先补上，否则时间窗比较永远对不上。 */
    fun stamp(entry: LedgerEntry): LedgerEntry {
        if (entry.timeMillis > 0L) return entry
        val cal = LedgerRepository.parseTime(entry.time)
        val millis = cal?.timeInMillis ?: System.currentTimeMillis()
        return entry.copy(timeMillis = millis)
    }

    suspend fun plan(context: Context, incoming: List<LedgerEntry>, aiCheck: Boolean = true): Plan {
        val dao = LedgerRepository.dao(context)
        val fresh = mutableListOf<LedgerEntry>()
        val suspects = mutableListOf<Suspect>()
        val claimed = HashSet<Long>()
        val seen = HashSet<String>()
        // 库里已有的外部单号：CSV 再导一遍时这些行是「老数据」，不必进候选比对。
        val existingIds = runCatching {
            dao.importRows().mapNotNull { it.externalId }.toHashSet()
        }.getOrDefault(HashSet())
        val looseSince = System.currentTimeMillis() - LOOSE_LOOKBACK_MS
        var skipped = 0
        for (raw in incoming) {
            val entry = stamp(raw)
            val bucket = (entry.timeMillis / 60_000L).toString()
            val fingerprint = entry.type + "|" + FingerprintCodec.amountFenToken(entry.amount) + "|" + bucket
            // 老数据：外部单号已经在库里，直接跳过（重复导入同一份 CSV 就靠这条兜住）
            val ext = entry.externalId
            if (!ext.isNullOrBlank() && existingIds.contains(ext)) {
                skipped++
                continue
            }
            // 同一批文件里自己重复的行，只留第一条
            if (!seen.add(fingerprint)) {
                skipped++
                continue
            }
            val candidates = dao.nearbySameAmount(
                entry.type,
                entry.amount - AMOUNT_EPS,
                entry.amount + AMOUNT_EPS,
                entry.timeMillis - WINDOW_MS,
                entry.timeMillis + WINDOW_MS,
            )
            var best: LedgerEntry? = null
            var bestRank = Double.NEGATIVE_INFINITY
            var bestScore = 0.0
            for (candidate in candidates) {
                if (claimed.contains(candidate.id)) continue
                val gap = abs(candidate.timeMillis - entry.timeMillis)
                val score = nameScore(entry.note, candidate.note) +
                    nameScore(entry.category, candidate.category) * 0.2
                // 宽松规则优先：近 30 天内、金额相同、时间差 5 分钟内，无条件算疑似。
                val loose = gap <= LOOSE_WINDOW_MS && candidate.timeMillis >= looseSince
                if (!loose && score < NAME_THRESHOLD) continue
                val rank = score + (if (loose) 1.0 else 0.0) - gap.toDouble() / WINDOW_MS * 0.1
                if (rank > bestRank) {
                    bestRank = rank
                    bestScore = score
                    best = candidate
                }
            }
            if (best == null) {
                fresh.add(entry)
            } else {
                claimed.add(best.id)
                suspects.add(
                    Suspect(
                        incoming = entry,
                        existing = best,
                        minutesApart = (abs(entry.timeMillis - best.timeMillis) / 60_000L).toInt(),
                        nameScore = bestScore,
                    ),
                )
            }
        }
        if (aiCheck && suspects.isNotEmpty()) aiVerdicts(context, suspects)
        return Plan(fresh = fresh, suspects = suspects, skipped = skipped)
    }

    /** 只把「本地判断不够确定」的几条交给模型，避免一次导入打满请求。 */
    private fun aiVerdicts(context: Context, suspects: MutableList<Suspect>) {
        val prefs = AppPrefs.get(context)
        if (prefs.aiBaseUrl.isBlank() || prefs.aiModel.isBlank()) return
        var used = 0
        for (index in suspects.indices) {
            if (used >= AI_LIMIT) break
            val suspect = suspects[index]
            if (suspect.nameScore >= AI_CONFIDENT_SCORE && suspect.minutesApart <= 5) continue
            used++
            val verdict = askModel(prefs, suspect) ?: continue
            suspects[index] = suspect.copy(aiVerdict = verdict.first, aiNote = verdict.second)
        }
    }

    private fun askModel(prefs: AppPrefs, suspect: Suspect): Pair<Boolean, String>? {
        val prompt = "判断这两条记录是否属于同一笔消费。只回复 YES 或 NO。\n" +
            "A：" + suspect.existing.time + " · " + suspect.existing.note + " · " +
            suspect.existing.category + " · " + suspect.existing.amount + " 元（" +
            suspect.existing.source + "）\n" +
            "B：" + suspect.incoming.time + " · " + suspect.incoming.note + " · " +
            suspect.incoming.category + " · " + suspect.incoming.amount + " 元（" +
            suspect.incoming.source + "）"
        val answer = runCatching { AiClient.textCompletion(prefs, prompt, 8) }.getOrNull() ?: return null
        val text = answer.uppercase()
        val verdict = when {
            text.contains("YES") -> true
            text.contains("NO") -> false
            text.contains("是") -> true
            text.contains("否") -> false
            else -> return null
        }
        return Pair(verdict, answer.trim().take(40))
    }

    suspend fun commit(context: Context, plan: Plan, decisions: List<Action>): CommitResult {
        val keep = mutableListOf<LedgerEntry>()
        keep.addAll(plan.fresh)
        var merged = 0
        var skipped = 0
        plan.suspects.forEachIndexed { index, suspect ->
            when (decisions.getOrElse(index) { Action.SKIP }) {
                Action.KEEP -> keep.add(suspect.incoming)
                Action.SKIP -> skipped++
                Action.MERGE -> {
                    LedgerRepository.update(context, mergeInto(suspect.existing, suspect.incoming))
                    merged++
                }
            }
        }
        val added = if (keep.isEmpty()) 0 else LedgerRepository.addImported(context, keep)
        LedgerRepository.releaseInvalidTransferPairs(context)
        return CommitResult(added = added, merged = merged, skipped = skipped)
    }

    /** 合并时保留库里那条的身份（id / 来源 / 时间），只把更完整的描述补进去。 */
    fun mergeInto(existing: LedgerEntry, incoming: LedgerEntry): LedgerEntry {
        val note = if (incoming.note.length > existing.note.length) incoming.note else existing.note
        val category = when {
            existing.category.isBlank() || existing.category == "其他" -> incoming.category
            incoming.category.isBlank() || incoming.category == "其他" -> existing.category
            else -> existing.category
        }
        return existing.copy(
            note = note,
            category = category,
            externalId = existing.externalId ?: incoming.externalId,
            time = if (existing.time.isBlank()) incoming.time else existing.time,
            timeMillis = if (existing.timeMillis <= 0L) incoming.timeMillis else existing.timeMillis,
        )
    }

    fun decisions(plan: Plan, action: Action): List<Action> = List(plan.suspects.size) { action }

    /** 商户名相似度：包含关系给高分，否则用二元组重合率。 */
    fun nameScore(a: String, b: String): Double {
        val x = normalizeName(a)
        val y = normalizeName(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val short = if (x.length <= y.length) x else y
        val long = if (x.length <= y.length) y else x
        if (long.contains(short)) return 0.1 + 0.9 * short.length.toDouble() / long.length
        val bx = bigrams(x)
        val by = bigrams(y)
        if (bx.isEmpty() || by.isEmpty()) return 0.0
        val common = bx.count { by.contains(it) }
        return common.toDouble() / max(bx.size, by.size)
    }

    private fun normalizeName(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val set = HashSet<String>()
        for (i in 0 until text.length - 1) set.add(text.substring(i, i + 2))
        return set
    }
}
