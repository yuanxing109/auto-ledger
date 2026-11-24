package com.eta.autoledger.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class PendingMapTest {

    @Before
    fun setUp() = PendingMap.clearForTest()

    @After
    fun tearDown() = PendingMap.clearForTest()

    @Test
    fun `五分钟边界前有效且恰好到期失效`() {
        val at = 1_000L
        val bill = pendingBill(note = "五分钟边界")
        PendingMap.put(bill, at)

        assertEquals(
            bill,
            PendingMap.consume(bill.packageName, bill.amountCents, at + PendingMap.TTL_MS - 1L),
        )

        PendingMap.put(bill, at)
        assertNull(PendingMap.consume(bill.packageName, bill.amountCents, at + PendingMap.TTL_MS))
    }

    @Test
    fun `过期记录被 consume 静默删除`() {
        val bill = pendingBill(note = "过期")
        PendingMap.put(bill, now = 10_000L)

        assertNull(
            PendingMap.consume(
                packageName = bill.packageName,
                amountCents = bill.amountCents,
                now = 10_000L + PendingMap.TTL_MS,
            ),
        )
        assertEquals(0, PendingMap.size(10_000L + PendingMap.TTL_MS))
    }

    @Test
    fun `同键新写入覆盖旧值并重置 TTL`() {
        val first = pendingBill(note = "旧值", subCategory = null)
        val second = first.copy(note = "新值", subCategory = "外卖")

        PendingMap.put(first, now = 20_000L)
        PendingMap.put(second, now = 20_000L + PendingMap.TTL_MS - 1L)

        val consumed = PendingMap.consume(
            packageName = second.packageName,
            amountCents = second.amountCents,
            now = 20_000L + PendingMap.TTL_MS,
        )
        assertSame(second, consumed)
        assertEquals("外卖", consumed?.subCategory)
    }

    private fun pendingBill(
        note: String,
        subCategory: String? = null,
    ): PendingBill = PendingBill(
        packageName = "com.tencent.mm",
        amountCents = 1_940L,
        type = "income",
        category = "转账",
        note = note,
        timeMillis = 1_700_000_000_000L,
        source = "微信通知",
        channel = NotifyChannel.WECHAT,
        subCategory = subCategory,
    )
}
