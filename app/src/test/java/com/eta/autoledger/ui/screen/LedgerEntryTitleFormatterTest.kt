package com.eta.autoledger.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerEntryTitleFormatterTest {
    @Test
    fun `银行短信优先提取商户标签`() {
        val note = "招商银行 您账户0306于11月28日发生快捷支付扣款人民币40.00元，商户：京东商城平台商户"

        assertEquals("京东商城平台商户", formatLedgerEntryTitle(note, "购物"))
    }

    @Test
    fun `无商户标签时保留在消费之间的商户`() {
        val note = "招商银行 您账户0306于17:50在财付通-微信支付-商户 消费22.00元"

        assertEquals("财付通-微信支付-商户", formatLedgerEntryTitle(note, "购物"))
    }

    @Test
    fun `无法提取商户时清掉银行短信噪音`() {
        val note = "【招商银行】您账户0306于11月28日快捷支付扣款人民币22.00元"

        assertEquals("购物", formatLedgerEntryTitle(note, "购物"))
    }

    @Test
    fun `余额信息不会被当成商户`() {
        val note = "招商银行 您账户0306发生快捷支付扣款人民币22.00元，商户：余额100.00元"

        assertEquals("购物", formatLedgerEntryTitle(note, "购物"))
    }

    @Test
    fun `向您支付不会被截成您`() {
        val note = "公司向您支付服务费19.40元"

        assertEquals(note, formatLedgerEntryTitle(note, "其他"))
    }

    @Test
    fun `空备注使用分类兜底`() {
        assertEquals("餐饮", formatLedgerEntryTitle("   ", "餐饮"))
    }
}
