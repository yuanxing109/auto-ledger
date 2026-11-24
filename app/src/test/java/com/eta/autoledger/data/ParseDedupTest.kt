package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 去重粒度与文本归一化的纯函数断言。
 *
 * 对应第一期落地（docs/12 §A2 / §B2）：
 *  - 指纹从分钟级改成秒级，同一分钟内的两笔真实消费不能再被并成一笔；
 *  - 跨秒边界的重复投递仍要被 2 秒促发窗口挡住；
 *  - 全角金额必须能被归一化后识别。
 */
class ParseDedupTest {

    @Before
    fun reset() = ParseDedup.clear()

    // ── 指纹粒度 ──────────────────────────────────────────────────────

    @Test
    fun 同一秒内的同一笔指纹相同() {
        val a = ParseDedup.fingerprint(9.90, "便利店", 1_700_000_000_100)
        val b = ParseDedup.fingerprint(9.90, "便利店", 1_700_000_000_900)
        assertEquals(a, b)
    }

    @Test
    fun 同一分钟内不同秒的指纹不同() {
        // 这是「分钟级指纹」修掉的核心缺陷：便利店连刷两次 9.9 不能被并成一笔
        val a = ParseDedup.fingerprint(9.90, "便利店", 1_700_000_001_000)
        val b = ParseDedup.fingerprint(9.90, "便利店", 1_700_000_020_000)
        assertNotEquals(a, b)
    }

    @Test
    fun 金额精度不影响指纹() {
        assertEquals(
            ParseDedup.fingerprint(46.9, "美团", 1_700_000_000_000),
            ParseDedup.fingerprint(46.90, "美团", 1_700_000_000_000),
        )
    }

    @Test
    fun 金额稳定换算为分() {
        assertEquals(1940L, ParseDedup.amountFen(19.40))
        assertEquals(30L, ParseDedup.amountFen(0.1 + 0.2))
        assertEquals(268L, ParseDedup.amountFen(2.675))
    }

    @Test
    fun 强指纹使用分钟桶并且金额精度稳定() {
        val a = ParseDedup.strongFingerprint(19.40, "expense", 1_700_000_000_000, "1234")
        val b = ParseDedup.strongFingerprint(19.4, "expense", 1_700_000_020_000, "1234")
        assertEquals(a, b)
        assertEquals(40, a.length)
    }

    @Test
    fun 强指纹区分方向卡尾和分钟() {
        val base = ParseDedup.strongFingerprint(19.40, "expense", 1_700_000_000_000, "1234")
        assertNotEquals(base, ParseDedup.strongFingerprint(19.40, "income", 1_700_000_000_000, "1234"))
        assertNotEquals(base, ParseDedup.strongFingerprint(19.40, "expense", 1_700_000_000_000, "5678"))
        assertNotEquals(base, ParseDedup.strongFingerprint(19.40, "expense", 1_700_000_060_000, "1234"))
    }

    @Test
    fun 卡尾支持中文标签和掩码格式() {
        assertEquals("1234", ParseDedup.accountTailFromText("您尾号1234的卡消费19.40元"))
        assertEquals("5678", ParseDedup.accountTailFromText("信用卡后四位：5678"))
        assertEquals("9012", ParseDedup.accountTailFromText("末四位 9012"))
        assertEquals("2468", ParseDedup.accountTailFromText("6222********2468"))
        assertEquals("1357", ParseDedup.accountTailFromText("卡号 **** 1357"))
        assertNull(ParseDedup.accountTailFromText("没有卡号信息"))
    }

    @Test
    fun 来源按加号去重追加() {
        assertEquals("微信通知 + 短信记账", ParseDedup.mergeSources("微信通知", "短信记账"))
        assertEquals("微信通知 + 短信记账", ParseDedup.mergeSources("微信通知 + 短信记账", "微信通知"))
        assertEquals("微信通知 + 短信记账", ParseDedup.mergeSources("微信通知", " 短信记账 "))
        assertEquals("微信通知", ParseDedup.mergeSources("微信通知", "   "))
    }

    @Test
    fun 强指纹只合并不同来源且同分钟同卡尾的账单() {
        val existing = LedgerEntry(
            id = 1L,
            amount = 19.40,
            type = "expense",
            category = "餐饮",
            time = "2023-11-14 22:13",
            timeMillis = 1_700_000_000_000L,
            note = "尾号1234消费19.40元",
            source = "银行通知",
        )
        val incoming = existing.copy(
            id = 0L,
            timeMillis = existing.timeMillis + 30_000L,
            source = "短信记账",
        )
        assertNotNull(LedgerRepository.findStrongMergeTarget(incoming, listOf(existing)))

        val sameSource = incoming.copy(source = "银行通知")
        assertNull(LedgerRepository.findStrongMergeTarget(sameSource, listOf(existing)))

        val differentMinute = incoming.copy(timeMillis = existing.timeMillis + 60_000L)
        assertNull(LedgerRepository.findStrongMergeTarget(differentMinute, listOf(existing)))

        val differentTail = incoming.copy(note = "尾号5678消费19.40元")
        assertNull(LedgerRepository.findStrongMergeTarget(differentTail, listOf(existing)))
    }

    // ── 促发窗口 ──────────────────────────────────────────────────────

    @Test
    fun 同一秒内重复投递被丢弃() {
        val fp = ParseDedup.fingerprint(46.94, "美团", 1_700_000_000_000)
        assertTrue(ParseDedup.accept(fp, 1_700_000_000_000))
        assertFalse(ParseDedup.accept(fp, 1_700_000_000_500))
    }

    @Test
    fun 跨秒边界的重复投递仍被促发窗口挡住() {
        // 12:00:00.998 与 12:00:01.002 落在两个时间桶里，但仍是同一笔钱
        val a = ParseDedup.fingerprint(46.94, "美团", 1_700_000_000_998)
        val b = ParseDedup.fingerprint(46.94, "美团", 1_700_000_001_002)
        assertNotEquals(a, b)
        assertTrue(ParseDedup.accept(a, 1_700_000_000_998))
        assertFalse(ParseDedup.accept(b, 1_700_000_001_002))
    }

    @Test
    fun 相隔较远的两笔同额真账都能入库() {
        val a = ParseDedup.fingerprint(9.90, "便利店", 1_700_000_000_000)
        val b = ParseDedup.fingerprint(9.90, "便利店", 1_700_000_020_000)
        assertTrue(ParseDedup.accept(a, 1_700_000_000_000))
        assertTrue(ParseDedup.accept(b, 1_700_000_020_000))
    }

    @Test
    fun 不同商户不互相影响() {
        val a = ParseDedup.fingerprint(9.90, "便利店A", 1_700_000_000_000)
        val b = ParseDedup.fingerprint(9.90, "便利店B", 1_700_000_000_000)
        assertTrue(ParseDedup.accept(a, 1_700_000_000_000))
        assertTrue(ParseDedup.accept(b, 1_700_000_000_000))
    }

    // ── NFKC 归一化 ───────────────────────────────────────────────────

    @Test
    fun 全角数字与逗号被摊平成半角() {
        assertEquals("1,000.00元", NotificationRules.normalize("１，０００．００元"))
    }

    @Test
    fun 归一化幂等() {
        val once = NotificationRules.normalize("￥１，２３４．５６")
        assertEquals(once, NotificationRules.normalize(once))
    }

    @Test
    fun 汉字与英文不受影响() {
        assertEquals("您尾号1234的卡消费", NotificationRules.normalize("您尾号1234的卡消费"))
    }

    @Test
    fun 全角金额归一化后可被短信解析() {
        val bill = NotificationRules.parseSms(
            sender = "95555",
            body = "您尾号1234的卡于11月20日12:30消费人民币１，０００．００元，余额5,000.00元",
            postTime = 1_700_000_000_000,
            expenseCats = listOf("消费"),
            incomeCats = listOf("收入"),
            otherCats = listOf("其他"),
        )
        assertEquals(1000.00, bill?.amount ?: 0.0, 0.001)
        assertEquals("expense", bill?.type)
    }
}
