package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 去重粒度与文本归一化的纯函数断言。
 *
 * 对应当前自动记账规则（docs/auto-accounting.md 的“跨来源去重”）：
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
    fun 指纹金额以分编码且不含本地化小数() {
        val fingerprint = ParseDedup.fingerprint(19.40, "便利店", 1_700_000_000_000)
        assertEquals("1940|便利店|1700000000", fingerprint)
    }

    @Test
    fun `指纹不随设备 Locale 改变`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val german = ParseDedup.fingerprint(19.40, "便利店", 1_700_000_000_000)
            Locale.setDefault(Locale.US)
            val us = ParseDedup.fingerprint(19.40, "便利店", 1_700_000_000_000)
            assertEquals(german, us)
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun 金额稳定换算为分() {
        assertEquals(1940L, ParseDedup.amountFen(19.40))
        assertEquals(30L, ParseDedup.amountFen(0.1 + 0.2))
        assertEquals(268L, ParseDedup.amountFen(2.675))
    }

    @Test
    fun `微信和银行一分钟内同额同向合并并保留银行详情`() {
        val bank = mergeEntry(
            id = 1L,
            timeMillis = 1_700_000_000_000L,
            source = "招商银行通知",
            note = "招商银行 您账户0306于17:50在财付通-微信支付-商户 消费22.00元",
            category = "其他",
        )
        val wechat = mergeEntry(
            id = 0L,
            timeMillis = bank.timeMillis + 60_000L,
            source = "微信通知",
            note = "微信支付：已支付¥22.00",
        )

        assertTrue(ParseDedup.shouldMerge(bank, wechat))
        val merged = ParseDedup.mergeRecords(bank, wechat)
        assertEquals(bank.id, merged.id)
        assertTrue(merged.note.contains("账户0306"))
        assertTrue(merged.source.contains("招商银行通知"))
        assertTrue(merged.source.contains("微信通知"))
        assertEquals("消费", merged.category)
    }

    @Test
    fun `钱包和银行五分钟内同额同向也可合并`() {
        val bank = mergeEntry(1L, 1_700_000_000_000L, "银行通知")
        val wallet = mergeEntry(2L, bank.timeMillis + 4 * 60_000L, "支付宝通知")

        assertTrue(ParseDedup.shouldMerge(bank, wallet))
    }

    @Test
    fun `银行短信来源即使只写短信记账也能按正文识别银行`() {
        val bank = mergeEntry(
            id = 1L,
            timeMillis = 1_700_000_000_000L,
            source = "短信记账",
            note = "招商银行 您账户0306于17:50消费22.00元",
        )
        val wallet = mergeEntry(2L, bank.timeMillis + 3 * 60_000L, "微信通知")

        assertTrue(ParseDedup.shouldMerge(bank, wallet))
    }

    @Test
    fun `同源同额超过一分钟不合并`() {
        val first = mergeEntry(1L, 1_700_000_000_000L, "微信通知")
        val second = mergeEntry(2L, first.timeMillis + 61_000L, "微信通知")

        assertFalse(ParseDedup.shouldMerge(first, second))
    }

    @Test
    fun `超过五分钟即使钱包和银行也不合并`() {
        val bank = mergeEntry(1L, 1_700_000_000_000L, "银行通知")
        val wallet = mergeEntry(2L, bank.timeMillis + 5 * 60_000L + 1, "微信通知")

        assertFalse(ParseDedup.shouldMerge(bank, wallet))
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

    private fun mergeEntry(
        id: Long,
        timeMillis: Long,
        source: String,
        note: String = "消费22.00元",
        category: String = "消费",
    ): LedgerEntry = LedgerEntry(
        id = id,
        amount = 22.0,
        type = "expense",
        category = category,
        time = "2026-11-26 17:50",
        timeMillis = timeMillis,
        note = note,
        source = source,
    )

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
