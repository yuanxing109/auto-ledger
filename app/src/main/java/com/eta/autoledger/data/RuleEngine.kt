package com.eta.autoledger.data

import android.util.Log
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * 一条规则的命中结果。只描述「规则说了什么」，不负责落库 —— 落库是 [ParsePipeline] 的事。
 *
 * [category] 可能是 null（规则没写分类，或写的分类用户已经删掉了），
 * 由调用方按当前分类表兜底。
 */
data class RuleHit(
    val ruleId: String,
    val ruleName: String,
    val amount: Double,
    val type: String,
    val category: String?,
    val autoRecord: Boolean,
    val confidence: Float,
)

/**
 * 规则引擎：把 `rule` 表里的 `pattern_json` 编译成可执行对象，在内存里缓存并按优先级匹配。
 *
 * 三级防线（缺一不可）：
 * 1. **静态闸门** [RuleSafety]：编译期就拒绝 `(a+)+` 这类回溯炸弹，坏正则只丢自己。
 * 2. **运行期预算**：每条规则 [REGEX_BUDGET_MS] 的 `withTimeout`，外加每次 `match` 前的
 *    截止时间检查。`Matcher` 不响应线程中断，单靠 `withTimeout` 停不住已经在跑的回溯，
 *    所以这里是在**多次匹配之间**检查，配合第 1 条把损失封在单条正则上。
 * 3. **熔断**：某条规则触发预算后进 [poisoned]，本次进程生命周期内不再尝试，
 *    避免每来一条通知都重跑一次慢正则。
 *
 * 缓存失效靠 [invalidate]：规则增删改之后调用它，下一次 [ensureLoaded] 会重查数据库。
 * 没有用 `Flow` 订阅表变化，是因为解析发生在广播/通知回调里，必须同步拿结果，
 * 而且规则表很小，重查成本可忽略。
 */
object RuleEngine {

    private const val TAG = "AutoLedger"

    /** 单条规则的匹配预算。正常规则是 2~4 条正则，1ms 都用不到；100ms 已经极其宽松。 */
    const val REGEX_BUDGET_MS = 100L

    /** `pattern_json.direction` 的合法取值；其余写法一律忽略，回落到词表投票。 */
    private val DIRECTIONS = setOf("income", "expense", "other")

    /** 当前内核能够验证的必填能力；未知名称不能假装已经满足。 */
    private val KNOWN_NECESSARY_FIELDS = setOf("amount", "direction")

    /** 缓存的规则列表，user 在前 system 在后，已按 [RuleEntity] 的查询顺序排好。 */
    @Volatile
    private var cached: List<CompiledRule> = emptyList()

    /** 缓存对应的版本号；-1 表示从未加载。 */
    @Volatile
    private var loadedVersion: Long = -1L

    /** 数据版本号。任何写操作后自增，使缓存失效。 */
    private val version = java.util.concurrent.atomic.AtomicLong(0L)

    /** 熔断集合：命中过预算的规则 id。 */
    private val poisoned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 规则表被改动后调用，下一次解析会重新加载。 */
    fun invalidate() {
        version.incrementAndGet()
    }

    /** 仅供测试：清空缓存与熔断状态。 */
    fun resetForTest() {
        cached = emptyList()
        loadedVersion = -1L
        version.set(0L)
        poisoned.clear()
    }

    /** 当前缓存的规则条数（诊断/日志用）。 */
    val cachedCount: Int get() = cached.size

    /**
     * 保证缓存与数据库一致。已是最新则直接返回，不查库。
     *
     * 先查 user 再查 system 然后拼接，而不是一条 `getAll()` 再排序：
     * 排序键在 SQL 里，读出来是什么顺序就用什么顺序，避免 Kotlin 侧再排一次时
     * 因为 `creator DESC` 的字典序（system > user）翻车。
     */
    suspend fun ensureLoaded(dao: RuleDao) {
        val v = version.get()
        // 只看版本号，不看 cached 是否为空：规则表被用户清空之后 cached 就是空表，
        // 若把「空」也当成「没加载过」，每来一条通知都会重查两次数据库。
        if (loadedVersion == v) return
        var ok = true
        val loaded = runCatching {
            val users = dao.getEnabledByCreator(RuleEntity.CREATOR_USER)
            val systems = dao.getEnabledByCreator(RuleEntity.CREATOR_SYSTEM)
            (users + systems).mapNotNull { compile(it) }
        }.getOrElse {
            Log.w(TAG, "加载规则失败，回退到内置解析", it)
            ok = false
            emptyList()
        }
        cached = loaded
        // 读失败时**不**记版本号，让下一次解析再试一遍：数据库被锁、磁盘临时不可读
        // 这类故障是可恢复的，一旦把失败也缓存成「已加载空表」，用户要等重启才能恢复规则。
        // 规则表本来就在本地，重试成本只有两次主键扫描。
        if (ok) loadedVersion = v
    }

    /**
     * 匹配一条文本。
     *
     * @param type  [RuleType.NOTICE] / [RuleType.SMS] / [RuleType.OCR]
     * @param app   包名；短信传发送方号码（规则里 `app` 为 null 时代表任意来源）
     * @param body  已经归一化（NFKC）并压过空白的正文
     * @return 第一条命中的规则结果；全部未命中返回 null，调用方回退到内置规则
     */
    suspend fun match(type: String, app: String?, body: String): RuleHit? {
        if (body.isBlank()) return null
        val rules = cached
        if (rules.isEmpty()) return null
        for (rule in rules) {
            if (rule.type != type) continue
            if (rule.app != null && rule.app != app) continue
            if (rule.id in poisoned) continue
            val hit = runCatching { rule.match(body) }.getOrElse {
                Log.w(TAG, "规则 ${rule.name} 匹配异常，熔断", it)
                poisoned.add(rule.id)
                null
            } ?: continue
            return hit
        }
        return null
    }

    /**
     * 把一行 [RuleEntity] 编译成可执行规则。JSON 坏了、正则全被拒 → 返回 null（整条规则跳过）。
     */
    private fun compile(entity: RuleEntity): CompiledRule? {
        val json = runCatching { JSONObject(entity.patternJson) }.getOrElse {
            Log.w(TAG, "规则 ${entity.name} 的 JSON 解析失败，跳过", it)
            return null
        }
        val necessaryFields = json.necessaryFields()
        val unknownFields = necessaryFields.toSet() - KNOWN_NECESSARY_FIELDS
        if (unknownFields.isNotEmpty()) {
            Log.w(TAG, "规则 ${entity.name} 含未知必填能力，跳过：${unknownFields.joinToString()}")
            return null
        }
        val amountPatterns = json.stringList("amount").mapNotNull { raw ->
            RuleSafety.compile(raw).also { compiled ->
                if (compiled == null) {
                    val reason = RuleSafety.unsafeReason(raw) ?: "正则无法编译"
                    Log.w(TAG, "规则 ${entity.name} 的金额正则被拒绝：$reason")
                }
            }
        }
        // 一条可用的金额正则都没有，这条规则不可能产出账单，直接丢弃
        if (amountPatterns.isEmpty()) return null

        val expenseWords = json.stringList("expense")
        val incomeWords = json.stringList("income")
        val forcedDirection = json.optString("direction").takeIf { it in DIRECTIONS }
        if ("direction" in necessaryFields &&
            forcedDirection == null &&
            expenseWords.isEmpty() &&
            incomeWords.isEmpty()
        ) {
            Log.w(TAG, "规则 ${entity.name} 缺少方向证据，跳过")
            return null
        }

        return CompiledRule(
            id = entity.id,
            name = entity.name,
            app = entity.app,
            type = entity.type,
            autoRecord = entity.autoRecord,
            amountPatterns = amountPatterns,
            expenseWords = expenseWords,
            incomeWords = incomeWords,
            blockWords = json.stringList("block"),
            categories = json.stringListMap("cats"),
            forcedDirection = forcedDirection,
        )
    }

    /** 已编译的规则。字段全部不可变，多线程共享安全。 */
    private class CompiledRule(
        val id: String,
        val name: String,
        val app: String?,
        val type: String,
        val autoRecord: Boolean,
        val amountPatterns: List<Regex>,
        val expenseWords: List<String>,
        val incomeWords: List<String>,
        val blockWords: List<String>,
        val categories: Map<String, List<String>>,
        /**
         * 规则显式声明的方向（`pattern_json` 的 `direction` 字段）。
         *
         * 词表投票解决不了「向您支付了…服务费」这类一句话里同时出现收支动词的句式：
         * 支出词永远比收入词多。这类规则直接写死方向，词表只用来要求「至少命中一处证据」。
         */
        val forcedDirection: String?,
    ) {

        /**
         * 命中判定。顺序即语义，改动前先读注释。
         *
         * 1. block 词命中 → 直接不匹配（营销/验证码/账单提醒）。
         * 2. 词表投票定方向；没有强制方向且词表零命中时视为证据不足。
         * 3. 金额正则逐条试，取第一条能解析出合法金额的。
         * 4. 分类从 `cats` 里按命中词数挑，多者优先。
         */
        suspend fun match(body: String): RuleHit? {
            if (blockWords.any { body.contains(it) }) return null

            val expenseHits = expenseWords.count { body.contains(it) }
            val incomeHits = incomeWords.count { body.contains(it) }
            if (forcedDirection == null && expenseHits == 0 && incomeHits == 0) {
                // 没有强制方向，正文也没有任何收支词：不得默认成支出。
                return null
            }
            val direction = forcedDirection ?: when {
                incomeHits > expenseHits -> "income"
                expenseHits > incomeHits -> "expense"
                else -> "expense"
            }

            val amount = extractAmount(body) ?: return null
            val category = pickCategory(body, direction)
            val confidence = when {
                amountPatterns.isNotEmpty() && (expenseHits + incomeHits) > 0 -> 0.9f
                else -> 0.75f
            }
            return RuleHit(
                ruleId = id,
                ruleName = name,
                amount = amount,
                type = direction,
                category = category,
                autoRecord = autoRecord,
                confidence = confidence,
            )
        }

        /**
         * 抠金额。整个循环包在 [REGEX_BUDGET_MS] 的预算里，且在每条正则之间检查耗时。
         *
         * 为什么不是简单 `withTimeout`：`Regex.find` 是纯 CPU 同步调用，不检查协程取消，
         * 一旦真的回溯起来 `withTimeout` 也不会返回。这里靠 [RuleSafety] 在编译期把
         * 回溯炸弹挡在外面，运行期再给一个「已经超时了就别再试下一条」的止损。
         */
        suspend fun extractAmount(body: String): Double? = withTimeout(REGEX_BUDGET_MS) {
            val deadline = System.nanoTime() + REGEX_BUDGET_MS * 1_000_000L
            for (pattern in amountPatterns) {
                if (System.nanoTime() > deadline) break
                val group = pattern.find(body)?.groupValues?.getOrNull(1) ?: continue
                val value = group.replace(",", "").toDoubleOrNull() ?: continue
                if (value > 0.0 && value <= 1_000_000.0) return@withTimeout value
            }
            null
        }

        /** 分类：按命中词数打分，最多者胜；同分取名字短的，保证结果稳定可测。 */
        fun pickCategory(body: String, direction: String): String? {
            if (categories.isEmpty()) return null
            var best: String? = null
            var bestScore = 0
            for ((name, words) in categories) {
                val score = words.count { body.contains(it) }
                if (score > bestScore || (score == bestScore && score > 0 && name.length < (best?.length ?: Int.MAX_VALUE))) {
                    best = name
                    bestScore = score
                }
            }
            return best
        }
    }
}

/** `JSONObject.getJSONArray(name)` → `List<String>`，缺失或类型不符返回空表。 */
private fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val value = array.optString(i, "")
        if (value.isNotBlank()) out.add(value)
    }
    return out
}

/** 旧 JSON 没有 necessaryFields 时沿用 v2 默认契约。 */
private fun JSONObject.necessaryFields(): List<String> {
    val array = optJSONArray("necessaryFields") ?: return RuleSeed.DEFAULT_NECESSARY_FIELDS
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        array.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let(out::add)
    }
    return out
}

/** `cats` 是 `{分类名: [关键词...]}`，org.json 里是嵌套 JSONObject。 */
private fun JSONObject.stringListMap(name: String): Map<String, List<String>> {
    val obj = optJSONObject(name) ?: return emptyMap()
    val out = LinkedHashMap<String, List<String>>()
    for (key in obj.keys()) {
        out[key] = obj.stringList(key)
    }
    return out
}
