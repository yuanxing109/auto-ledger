package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRepositoryTest {

    private val baseTime = 1_700_000_000_000L

    @Test
    fun `时间差2分钟金额相同方向相反且含信用卡还款时合并`() {
        val expense = entry(1L, "expense", 1000.0, baseTime, "信用卡还款")
        val income = entry(2L, "income", 1000.0, baseTime + 2 * 60_000L, "还款入账")

        assertTrue(TransferPairMatcher.matches(expense, income))
        assertEquals(income, TransferPairMatcher.chooseCandidate(expense, listOf(income)))
    }

    @Test
    fun `时间差10分钟不合并`() {
        val expense = entry(1L, "expense", 1000.0, baseTime, "信用卡还款")
        val income = entry(2L, "income", 1000.0, baseTime + 10 * 60_000L, "还款入账")

        assertFalse(TransferPairMatcher.matches(expense, income))
        assertNull(TransferPairMatcher.chooseCandidate(expense, listOf(income)))
    }

    @Test
    fun `时间近金额同但语义不符不合并`() {
        val loan = entry(1L, "expense", 1000.0, baseTime, "借给朋友")
        val salary = entry(2L, "income", 1000.0, baseTime + 2 * 60_000L, "工资")

        assertFalse(TransferPairMatcher.matches(loan.copy(category = "其他"), salary.copy(category = "薪资")))
    }

    @Test
    fun `同一卡尾不能当作跨账户转账`() {
        val expense = entry(1L, "expense", 1000.0, baseTime, "尾号1234信用卡还款")
        val income = entry(2L, "income", 1000.0, baseTime + 60_000L, "尾号1234还款入账")

        assertFalse(TransferPairMatcher.matches(expense, income))
    }

    @Test
    fun `同最小时间差出现多个候选时放弃自动配对`() {
        val expense = entry(1L, "expense", 1000.0, baseTime, "信用卡还款")
        val first = entry(2L, "income", 1000.0, baseTime + 60_000L, "还款入账")
        val second = entry(3L, "income", 1000.0, baseTime - 60_000L, "信用卡还款入账")

        assertNull(TransferPairMatcher.chooseCandidate(expense, listOf(first, second)))
    }

    @Test
    fun `批量规划优先使用同批内部配对`() {
        val expense = entry(1L, "expense", 1000.0, baseTime, "信用卡还款")
        val sameBatchIncome = entry(2L, "income", 1000.0, baseTime + 60_000L, "信用卡还款入账")

        val pairs = TransferPairMatcher.chooseLocalPairs(listOf(expense, sameBatchIncome))

        assertEquals(1, pairs.size)
        assertEquals(expense.id, pairs.single().first.id)
        assertEquals(sameBatchIncome.id, pairs.single().second.id)
    }

    @Test
    fun `退款冲正失败记录不参与转账配对`() {
        val expense = entry(1L, "expense", 1000.0, baseTime, "信用卡还款失败")
        val income = entry(2L, "income", 1000.0, baseTime + 60_000L, "信用卡还款入账")

        assertFalse(TransferPairMatcher.matches(expense, income))
    }

    private fun entry(
        id: Long,
        type: String,
        amount: Double,
        timestamp: Long,
        note: String,
    ): LedgerEntry = LedgerEntry(
        id = id,
        amount = amount,
        type = type,
        category = "转账",
        time = "2023-11-15 06:13",
        timeMillis = timestamp,
        note = note,
        source = "test",
    )
}
