package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则安全闸门的断言。
 *
 * 这一层是 ReDoS 的**主防线**，不是锦上添花：`java.util.regex` 的 `Matcher` 不响应线程中断，
 * 也不检查协程取消，所以 `withTimeout(100)` 在 JVM 上停不住一条已经跑起来的回溯。
 * 唯一可靠的办法就是不让它在编译期进内存。
 *
 * 反例都是真实的回溯炸弹写法，不是凭空构造的：
 *  - `(a+)+` / `(\d*)*`：组内组外重复嵌套，40 字正文就能让单核跑几十秒；
 *  - `(a|aa)+`：分支前缀重叠 + 无界重复，指数级；
 *  - `a{100000}`：不是回溯，是内存炸弹，编译期就要分配巨大的状态表。
 */
class RuleSafetyTest {

    // ── 必须被拒绝的写法 ──────────────────────────────────────────────

    @Test
    fun 组内组外重复嵌套被拒绝() {
        assertNotNull(RuleSafety.unsafeReason("(a+)+"))
        assertNotNull(RuleSafety.unsafeReason("(\\d*)*"))
        assertNotNull(RuleSafety.unsafeReason("(x+){2,}"))
        // 真实场景：有人想把「金额后面跟一串数字」写成 (\\d+\\s*)+
        assertNotNull(RuleSafety.unsafeReason("(\\d+\\s*)+"))
    }

    @Test
    fun 分支重复被拒绝() {
        assertNotNull(RuleSafety.unsafeReason("(a|aa)+"))
        assertNotNull(RuleSafety.unsafeReason("(a|a?)+"))
    }

    @Test
    fun 组内点星又重复被拒绝() {
        assertNotNull(RuleSafety.unsafeReason("(.*)+"))
        assertNotNull(RuleSafety.unsafeReason("(foo.*bar)*"))
    }

    @Test
    fun 重复次数过大被拒绝() {
        assertNotNull(RuleSafety.unsafeReason("a{100000}"))
        // 恰好等于上限应当放行，边界不能写反
        assertNull(RuleSafety.unsafeReason("a{100}"))
        assertNotNull(RuleSafety.unsafeReason("a{101}"))
    }

    @Test
    fun 超长正则被拒绝() {
        val long = "a".repeat(RuleSafety.MAX_PATTERN_LENGTH + 1)
        assertNotNull(RuleSafety.unsafeReason(long))
        assertEquals(null, RuleSafety.unsafeReason("a".repeat(RuleSafety.MAX_PATTERN_LENGTH)))
    }

    @Test
    fun 空正则有明确理由() {
        assertNotNull(RuleSafety.unsafeReason(""))
        assertNotNull(RuleSafety.unsafeReason("   "))
    }

    // ── 必须放行的写法（内置规则与常见手写规则都在这里）────────────────

    @Test
    fun 内置规则的正则全部放行() {
        // 这些正是 RuleSeed 里真实使用的正则，一条都不能被误杀
        val seeds = listOf(
            """微信支付\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""",
            """[¥￥]\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
            """(?:付款|支付|消费|收款)金额\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""",
            """(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?:元|圆)""",
            """(?:消费|支付|付款|支出|扣款)[^\d¥￥]{0,12}(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
        )
        seeds.forEach { pattern ->
            assertNull("内置正则被误杀：$pattern", RuleSafety.unsafeReason(pattern))
        }
    }

    @Test
    fun 有界重复与交替分组仍然放行() {
        // `{0,12}` 有界、`(?:a|b)` 后面没有量词 —— 都不是炸弹
        assertNull(RuleSafety.unsafeReason("[^\\d]{0,12}"))
        assertNull(RuleSafety.unsafeReason("(?:消费|支付|付款)金额"))
        assertNull(RuleSafety.unsafeReason("\\d{1,3}(?:,\\d{3})+"))
    }

    // ── compile 的语义 ────────────────────────────────────────────────

    @Test
    fun compile对不安全的正则返回null而不是抛异常() {
        assertNull(RuleSafety.compile("(a+)+"))
        assertNotNull(RuleSafety.compile("""[¥￥]\s*(\d+(?:\.\d{1,2})?)"""))
    }

    @Test
    fun compile统一启用DOTALL支持跨行匹配() {
        val regex = RuleSafety.compile("""消费.*?金额\s*(\d+\.\d{2})""")
        assertNotNull(regex)
        assertTrue(regex!!.containsMatchIn("消费成功\n金额 19.40"))
    }

    @Test
    fun compile对语法错误的正则返回null() {
        // 用户手写正则写错括号：只丢这一条，不能让解析热路径崩
        assertNull(RuleSafety.compile("([0-9"))
        assertNull(RuleSafety.compile("*abc"))
    }

    @Test
    fun 拒绝理由是给人看的() {
        val reason = RuleSafety.unsafeReason("(a+)+")
        assertNotNull(reason)
        assertTrue("理由要能贴进日志给用户看：$reason", (reason?.length ?: 0) > 4)
    }
}
