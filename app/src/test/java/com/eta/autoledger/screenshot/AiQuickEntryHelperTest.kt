package com.eta.autoledger.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AiQuickEntryHelperTest {

    private val expenseCategories = listOf("其他", "餐饮", "交通")
    private val incomeCategories = listOf("其他", "薪资", "退款")
    private val nowMillis = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 24, 8, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `昨天打车18点5解析为交通并回退到昨天`() {
        var promptSeen = ""
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "昨天打车18.5",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) { prompt ->
            promptSeen = prompt
            """{"amount":18.5,"type":"expense","category":"交通","note":"打车","date_offset":-1}"""
        }

        assertNotNull(result)
        assertEquals(18.5, result!!.amount, 0.001)
        assertEquals("expense", result.type)
        assertEquals("交通", result.category)
        assertEquals("打车", result.note)
        assertEquals(-1, result.dateOffset)
        assertEquals(-1L, calendarDayDistance(result.timeMillis, nowMillis))
        assertTrue(promptSeen.contains("昨天打车18.5"))
        assertTrue(promptSeen.contains("只输出一个 JSON 对象"))
        assertTrue(promptSeen.contains("\"date_offset\":-1"))
    }

    @Test
    fun `Markdown 包裹的 JSON 可以正常解析`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "今天早餐12",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) {
            """
                ```json
                {"amount":12,"type":"expense","category":"餐饮","note":"早餐","date_offset":0}
                ```
            """.trimIndent()
        }

        assertNotNull(result)
        assertEquals(12.0, result!!.amount, 0.001)
        assertEquals("餐饮", result.category)
        assertEquals(0, result.dateOffset)
        assertEquals(0L, calendarDayDistance(result.timeMillis, nowMillis))
    }

    @Test
    fun `非法金额返回 null`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "买东西",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) {
            """{"amount":0,"type":"expense","category":"其他","note":"买东西","date_offset":0}"""
        }

        assertNull(result)
    }

    @Test
    fun `非法类型返回 null`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "转账一百",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) {
            """{"amount":100,"type":"transfer","category":"其他","note":"转账","date_offset":0}"""
        }

        assertNull(result)
    }

    @Test
    fun `无效分类回退到对应列表的其他`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "收到退款20",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) {
            """{"amount":20,"type":"income","category":"餐饮","note":"退款","date_offset":0}"""
        }

        assertNotNull(result)
        assertEquals("income", result!!.type)
        assertEquals("其他", result.category)
    }

    private fun calendarDayDistance(fromMillis: Long, toMillis: Long): Long {
        fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return TimeUnit.MILLISECONDS.toDays(startOfDay(fromMillis) - startOfDay(toMillis))
    }
}
