package com.eta.autoledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar

class AppDateFormatsTest {

    @Test
    fun `常用展示格式复用同一组 formatter`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 24, 8, 5, 6)
            set(Calendar.MILLISECOND, 0)
        }

        assertEquals("2026-11-24 08:05", AppDateFormats.format(calendar, AppDateFormats.dateTime))
        assertEquals("2026-11-24 08:05:06", AppDateFormats.format(calendar, AppDateFormats.dateTimeSeconds))
        assertEquals("20261124", AppDateFormats.format(calendar, AppDateFormats.basicDate))
        assertEquals("11月24日", AppDateFormats.format(calendar, AppDateFormats.monthDayChinese))
        assertEquals("11-24 08:05", AppDateFormats.format(calendar, AppDateFormats.dashMonthDayTime))
    }

    @Test
    fun `历史文本解析不再依赖 SimpleDateFormat`() {
        val values = listOf(
            "2026-11-24 08:05:06",
            "2026-11-24 08:05",
            "2026-11-24",
            "2026/11/24 8:05",
            "2026/11/24",
            "11月24日 08:05",
        )

        values.forEach { value ->
            val parsed = AppDateFormats.parse(value)
            assertNotNull(value, parsed)
            assertEquals(11, parsed!!.get(Calendar.MONTH) + 1)
            assertEquals(24, parsed.get(Calendar.DAY_OF_MONTH))
        }

        val today = AppDateFormats.parse("今天 08:05")
        assertNotNull(today)
        assertEquals(8, today!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, today.get(Calendar.MINUTE))
    }
}
