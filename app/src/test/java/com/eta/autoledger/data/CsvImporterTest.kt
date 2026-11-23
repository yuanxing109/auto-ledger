package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付宝 CSV 解析回归测试。
 *
 * 背景：真实账单导出后出现过「金额显示成天文数字」「备注整片乱码」
 * 「不计收支被记成支出」。根因有三层，逐层用断言钉住：
 *
 * 1. 编码：支付宝 CSV 常为 GBK，按 UTF-8 硬读 → 中文表头全变替换字符
 *    → 表头匹配失败 → 掉进逐行正则兜底 → 订单号被当金额。
 * 2. 表头：真实文件前面有 20 多行说明，表头不在第一行。
 * 3. 金额：订单号是 20 位数字，`toDouble()` 直接得到天文数字。
 */
class CsvImporterTest {

    /** 模拟支付宝真实导出：前面 20 多行说明 + 表头 + 明细 + 尾部统计。 */
    private val alipayCsv = """
        支付宝交易明细查询
        ------------------------------------------------
        账号：138****8888
        起始时间：[2026-10-01 00:00:00]    终止时间：[2026-10-31 23:59:59]
        导出时间：[2026-11-01 10:22:33]
        ------------------------------------------------
        收入：100.00 元
        支出：60.66 元
        不计收支：3000.00 元
        ------------------------------------------------
        交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
        2026-10-08 12:31:07,餐饮美食,老王牛肉面,wxid_abc,牛肉面,支出,55.30,余额,交易成功,2026100822001234567890123456,,
        2026-10-09 08:15:22,交通出行,滴滴出行,,快车行程,支出,0.94,花呗,交易成功,2026100922001234567890123457,,
        2026-10-10 19:44:03,日用百货,便利蜂,,矿泉水,支出,4.42,余额,交易成功,2026101022001234567890123458,,
        2026-10-11 09:00:00,信用借还,花呗,,花呗还款,不计收支,3000.00,余额,交易成功,2026101122001234567890123459,,
        2026-10-12 10:00:00,转账,张三,,转账,不计收支,500.00,余额,交易成功,2026101222001234567890123460,,
        2026-10-13 11:00:00,日用百货,某商城,,退款商品,支出,88.00,余额,还款失败,2026101322001234567890123461,,
        2026-10-14 12:00:00,日用百货,某商城,,关闭订单,支出,66.00,余额,交易关闭,2026101422001234567890123462,,
        ------------------------------------------------
        共 7 笔记录
    """.trimIndent()

    @Test
    fun `表头不在第一行也能定位`() {
        val entries = CsvImporter.parse(alipayCsv)
        // 7 行明细里剔除 2 条不计收支 + 1 条还款失败 + 1 条交易关闭 = 3 条
        assertEquals(3, entries.size)
    }

    @Test
    fun `金额取自金额列而不是订单号`() {
        val entries = CsvImporter.parse(alipayCsv)
        assertEquals(listOf(55.30, 0.94, 4.42), entries.map { it.amount })
    }

    @Test
    fun `金额不会溢出成天文数字`() {
        val entries = CsvImporter.parse(alipayCsv)
        entries.forEach { entry ->
            assertTrue("金额 ${entry.amount} 超出合理范围", entry.amount < 1_000_000.0)
            assertTrue("金额 ${entry.amount} 非正数", entry.amount > 0.0)
            assertFalse("金额 ${entry.amount} 是 NaN", entry.amount.isNaN())
        }
    }

    @Test
    fun `不计收支与失败状态被剔除`() {
        val entries = CsvImporter.parse(alipayCsv)
        val notes = entries.map { it.note }
        assertFalse("花呗还款不该入账", notes.any { it.contains("花呗还款") })
        assertFalse("转账不该入账", notes.any { it.contains("转账") })
        assertFalse("还款失败不该入账", notes.any { it.contains("退款商品") })
        assertFalse("交易关闭不该入账", notes.any { it.contains("关闭订单") })
    }

    @Test
    fun `备注是商品说明加交易对方`() {
        val entries = CsvImporter.parse(alipayCsv)
        assertEquals("牛肉面 - 老王牛肉面", entries[0].note)
        assertEquals("快车行程 - 滴滴出行", entries[1].note)
        assertEquals("矿泉水 - 便利蜂", entries[2].note)
    }

    @Test
    fun `收支方向按收或支列判定`() {
        val entries = CsvImporter.parse(alipayCsv)
        entries.forEach { assertEquals("expense", it.type) }
    }

    @Test
    fun `收入行记为正数收入`() {
        val csv = """
            支付宝交易明细查询
            交易时间,交易分类,交易对方,商品说明,收/支,金额,交易状态,交易订单号
            2026-10-15 10:00:00,转账,李四,收款,收入,120.00,交易成功,2026101522001234567890123470
        """.trimIndent()
        val entries = CsvImporter.parse(csv)
        assertEquals(1, entries.size)
        assertEquals(120.00, entries[0].amount, 0.001)
        assertEquals("income", entries[0].type)
    }

    @Test
    fun `订单号作为去重键`() {
        val entries = CsvImporter.parse(alipayCsv)
        assertEquals("ali:2026100822001234567890123456", entries[0].externalId)
        assertEquals(entries.size, entries.mapNotNull { it.externalId }.distinct().size)
    }

    @Test
    fun `分类映射到本应用分类名`() {
        val entries = CsvImporter.parse(alipayCsv)
        assertEquals("餐饮", entries[0].category)
        assertEquals("交通", entries[1].category)
        assertEquals("购物", entries[2].category)
    }

    @Test
    fun `分隔行被跳过且不产生空记录`() {
        val entries = CsvImporter.parse(alipayCsv)
        assertTrue(entries.none { it.note.contains("----") })
        assertTrue(entries.all { it.amount > 0.0 })
    }

    @Test
    fun `长串数字不会污染金额`() {
        // 金额列被错位塞入 24 位订单号：必须整行丢弃，而不是记成天文数字
        val csv = """
            交易时间,交易分类,交易对方,商品说明,收/支,金额,交易状态,交易订单号
            2026-10-16 10:00:00,餐饮,某店,午餐,支出,2026101622001234567890123456,交易成功,2026101622001234567890123456
        """.trimIndent()
        val entries = CsvImporter.parse(csv)
        assertTrue("24 位数字必须被拦掉，实际解析出 ${entries.map { it.amount }}", entries.isEmpty())
    }

    @Test
    fun `GBK 编码的账单能正确解码`() {
        val gbkBytes = alipayCsv.toByteArray(charset("GBK"))
        val decoded = CsvImporter.decode(gbkBytes)
        assertTrue("GBK 解码后应含中文表头", decoded.contains("交易时间"))
        assertTrue("GBK 解码后不应有替换字符", !decoded.contains('\uFFFD'))

        val entries = CsvImporter.parse(decoded)
        assertEquals(3, entries.size)
        assertEquals(listOf(55.30, 0.94, 4.42), entries.map { it.amount })
    }

    @Test
    fun `UTF8 BOM 的账单能正确解码`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + alipayCsv.toByteArray(Charsets.UTF_8)
        val decoded = CsvImporter.decode(bytes)
        assertFalse("BOM 必须被剥掉", decoded.startsWith("\uFEFF"))
        assertEquals(3, CsvImporter.parse(decoded).size)
    }

    @Test
    fun `空输入不崩溃`() {
        assertTrue(CsvImporter.parse("").isEmpty())
        assertTrue(CsvImporter.parse("   \n\n  ").isEmpty())
        assertEquals("", CsvImporter.decode(ByteArray(0)))
    }

    @Test
    fun `微信账单仍走原分支`() {
        // 约束：不得影响微信解析。微信表头含「交易单号」「交易类型」，无「交易分类」。
        val wechat = """
            微信支付账单明细
            ------------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-10-01 09:12:00,商户消费,美团,外卖,支出,¥28.50,零钱,支付成功,wx001,m001,
            2026-10-02 10:00:00,转账,张三,转账,收入,¥100.00,零钱,已存入零钱,wx002,m002,
        """.trimIndent()
        val entries = CsvImporter.parse(wechat)
        assertEquals(2, entries.size)
        assertEquals(28.50, entries[0].amount, 0.001)
        assertEquals("expense", entries[0].type)
        assertEquals(100.00, entries[1].amount, 0.001)
        assertEquals("income", entries[1].type)
        assertNotNull(entries[0].externalId)
        assertTrue("微信来源不应被改成支付宝", entries[0].source.contains("微信"))
    }
}
