package com.eta.autoledger.data

/**
 * 正则安全闸门：在规则进内存之前挡掉会导致灾难性回溯（ReDoS）的写法。
 *
 * 为什么必须有这一层：`RuleEntity.patternJson` 里的正则是**用户可写**的。
 * 一个 `(a+)+$` 配上一条 40 字的短信正文，就能让 JVM 的 `java.util.regex`
 * 在单线程上回溯几十秒 —— 而 `Matcher` 不响应线程中断，也不检查协程取消。
 * 也就是说 `withTimeout(100)` 在 JVM 上**停不住**已经在跑的回溯。
 *
 * 所以真正的防线是这里：宁可让一条写坏的正则失效（记日志、规则其余部分照用），
 * 也不能让它在解析热路径上被触发。运行期还有一道 [RuleEngine.REGEX_BUDGET_MS]
 * 的超时与熔断，但那是兜底，不是主防线。
 */
object RuleSafety {

    /** 单条正则的长度上限。正常金额正则 60~120 字，400 已经非常宽松。 */
    const val MAX_PATTERN_LENGTH = 400

    /** `{n}` / `{n,}` / `{n,m}` 里 n 的上限，防止 `a{100000}` 这类内存炸弹。 */
    const val MAX_REPEAT = 100

    /**
     * 组内已有量词、组外又套量词 —— `(a+)+`、`(\d*)*`、`(x+){2,}` 全是经典回溯炸弹。
     */
    private val NESTED_QUANTIFIER = Regex("""\([^()]*[*+][^()]*\)\s*[*+{]""")

    /**
     * 分支重复 —— `(a|aa)+`、`(a|a?)+`。分支前缀重叠 + 无界重复，指数级回溯。
     */
    private val BRANCH_REPEAT = Regex("""\([^()]*\|[^()]*\)\s*[*+]""")

    /** 单字符或短字符集的重复内嵌重复，如 `[\s\S]*` 后面又跟 `.*`，配合组重复会炸。 */
    private val DOT_STAR_IN_GROUP = Regex("""\([^()]*\.\*[^()]*\)\s*[*+]""")

    private val REPEAT_BOUND = Regex("""\{\s*(\d+)\s*(?:,\s*\d*)?}""")

    /**
     * 检查一条正则是否可以安全编译执行。
     *
     * @return null 表示安全；否则返回人类可读的拒绝理由（写进日志给用户看）。
     */
    fun unsafeReason(pattern: String): String? {
        if (pattern.isBlank()) return "正则为空"
        if (pattern.length > MAX_PATTERN_LENGTH) {
            return "正则过长（${pattern.length} > $MAX_PATTERN_LENGTH 字符）"
        }
        if (NESTED_QUANTIFIER.containsMatchIn(pattern)) return "组内组外重复嵌套（如 (a+)+）"
        if (BRANCH_REPEAT.containsMatchIn(pattern)) return "分支重复（如 (a|aa)+）"
        if (DOT_STAR_IN_GROUP.containsMatchIn(pattern)) return "组内 .* 又重复"
        REPEAT_BOUND.findAll(pattern).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (n > MAX_REPEAT) return "重复次数过大（{$n}）"
        }
        return null
    }

    /**
     * 编译一条正则，不安全或语法错误都返回 null 而不是抛异常。
     *
     * 编译失败只影响这一条 pattern，不影响规则里其它 pattern —— 用户手写正则写错一个
     * 括号，不该让整条规则消失。
     */
    fun compile(pattern: String): Regex? {
        if (unsafeReason(pattern) != null) return null
        return runCatching { Regex(pattern) }.getOrNull()
    }
}
