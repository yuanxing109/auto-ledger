package com.eta.autoledger.screenshot

import com.eta.autoledger.network.AiResponseFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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
            """{"amount":18.5,"type":"expense","category":"交通","subCategory":"打车租车","time":"昨天 18:05","note":"打车"}"""
        }

        assertNotNull(result)
        assertEquals(18.5, result!!.amount, 0.001)
        assertEquals("expense", result.type)
        assertEquals("交通", result.category)
        assertEquals("打车租车", result.subCategory)
        assertEquals("打车", result.note)
        assertEquals(-1, result.dateOffset)
        assertEquals(-1L, calendarDayDistance(result.timeMillis, nowMillis))
        assertTrue(promptSeen.contains("昨天打车18.5"))
        assertTrue(promptSeen.contains("只输出一个合法的 JSON 对象"))
        assertTrue(promptSeen.contains("\"time\": \"昨天\""))
        assertTrue(promptSeen.contains("向您支付"))
        assertTrue(promptSeen.contains("向我支付"))
        assertTrue(promptSeen.contains("\"subCategory\": \"奶茶咖啡\""))
        assertTrue(promptSeen.contains("餐饮：早中晚餐、外卖、水果零食、奶茶咖啡"))
        assertTrue(promptSeen.contains("人情社交"))
        assertTrue(promptSeen.contains("宠物、住房、代付、其他"))
        assertTrue(!promptSeen.contains("date_offset"))
        assertTrue(!promptSeen.contains("confidence"))
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
                {"amount":12,"type":"expense","category":"餐饮","subCategory":"早中晚餐","time":"今天","note":"早餐"}
                ```
            """.trimIndent()
        }

        assertNotNull(result)
        assertEquals(12.0, result!!.amount, 0.001)
        assertEquals("餐饮", result.category)
        assertEquals("早中晚餐", result.subCategory)
        assertEquals(0, result.dateOffset)
        assertEquals(0L, calendarDayDistance(result.timeMillis, nowMillis))
    }

    @Test
    fun `非法金额抛出统一格式异常`() {
        assertThrows(AiResponseFormatException::class.java) {
            AiQuickEntryHelper.resolveWithModelCall(
                text = "买东西",
                expenseCategories = expenseCategories,
                incomeCategories = incomeCategories,
                nowMillis = nowMillis,
            ) {
                """{"amount":0,"type":"expense","category":"其他","time":"今天","note":"买东西"}"""
            }
        }
    }

    @Test
    fun `非法类型抛出统一格式异常`() {
        assertThrows(AiResponseFormatException::class.java) {
            AiQuickEntryHelper.resolveWithModelCall(
                text = "转账一百",
                expenseCategories = expenseCategories,
                incomeCategories = incomeCategories,
                nowMillis = nowMillis,
            ) {
                """{"amount":100,"type":"transfer","category":"其他","time":"今天","note":"转账"}"""
            }
        }
    }

    @Test
    fun `无效分类回退到对应列表的其他`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "收到退款20",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) {
            """{"amount":20,"type":"income","category":"餐饮","subCategory":"奶茶咖啡","time":"今天","note":"退款"}"""
        }

        assertNotNull(result)
        assertEquals("income", result!!.type)
        assertEquals("其他", result.category)
        assertEquals(null, result.subCategory)
    }

    @Test
    fun `向您支付文本解析为收入并保留昨天时间`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "昨天14:26 天津新元信息技术有限公司向我支付了项目服务费19.40元",
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            nowMillis = nowMillis,
        ) {
            """好的，已完成。{"amount":19.40,"type":"income","category":"其他","time":"昨天 14:26","note":"天津新元"}以上。"""
        }

        assertNotNull(result)
        assertEquals(19.40, result!!.amount, 0.001)
        assertEquals("income", result.type)
        assertEquals("其他", result.category)
        assertEquals(-1, result.dateOffset)
        assertTrue(result.time.endsWith("14:26"))
    }

    @Test
    fun `无可匹配契约分类时回退其他而不是自定义分类`() {
        val result = AiQuickEntryHelper.resolveWithModelCall(
            text = "自定义消费 20",
            expenseCategories = listOf("我的自定义分类"),
            incomeCategories = listOf("其他"),
            nowMillis = nowMillis,
        ) {
            """{"amount":20,"type":"expense","category":"我的自定义分类","subCategory":"","time":"今天","note":"自定义消费"}"""
        }

        assertNotNull(result)
        assertEquals("其他", result!!.category)
        assertEquals(null, result.subCategory)
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
