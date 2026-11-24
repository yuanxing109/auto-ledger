package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class RecurringExternalIdsTest {

    @Test
    fun `日期与模板 id 使用同一个唯一键格式`() {
        val calendar = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.NOVEMBER, 24, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }

        assertEquals("rec_42_20261124", RecurringExternalIds.generate(42L, calendar))
    }

    @Test
    fun `同一模板同一天跨调用稳定`() {
        val calendar = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.NOVEMBER, 24, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val first = RecurringExternalIds.generate(7L, calendar)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        val second = RecurringExternalIds.generate(7L, calendar)

        assertEquals(first, second)
    }
}
