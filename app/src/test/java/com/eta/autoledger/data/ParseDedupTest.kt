package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
