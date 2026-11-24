package com.eta.autoledger.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** 全应用共享的不可变日期格式，线程安全，禁止调用方重复构造 formatter。 */
object AppDateFormats {

    val dateTime: DateTimeFormatter = formatter("yyyy-MM-dd HH:mm")
    val dateTimeSeconds: DateTimeFormatter = formatter("yyyy-MM-dd HH:mm:ss")
    val date: DateTimeFormatter = formatter("yyyy-MM-dd")
    val basicDate: DateTimeFormatter = formatter("yyyyMMdd")
    val slashDateTime: DateTimeFormatter = formatter("yyyy/M/d H:mm")
    val slashDate: DateTimeFormatter = formatter("yyyy/M/d")
    val monthDayTime: DateTimeFormatter = formatter("MM月dd日 HH:mm")
    val todayTime: DateTimeFormatter = formatter("'今天' HH:mm")
    val timeMinute: DateTimeFormatter = formatter("HH:mm")
    val dotDate: DateTimeFormatter = formatter("yyyy.MM.dd")
    val dotMonthDay: DateTimeFormatter = formatter("MM.dd")
    val yearMonthChinese: DateTimeFormatter = formatter("yyyy年M月")
    val yearChinese: DateTimeFormatter = formatter("yyyy年")
    val monthDayChinese: DateTimeFormatter = formatter("M月d日")
    val dashMonthDayTime: DateTimeFormatter = formatter("MM-dd HH:mm")
    val fileStamp: DateTimeFormatter = formatter("yyyyMMdd_HHmmss")

    fun format(
        epochMillis: Long,
        formatter: DateTimeFormatter,
    ): String = formatter.format(Instant.ofEpochMilli(epochMillis))

    fun format(calendar: Calendar, formatter: DateTimeFormatter): String =
        format(calendar.timeInMillis, formatter)

    /** 兼容历史账本文本解析，但复用不可变 formatter，不在调用点构造。 */
    fun parse(raw: String): Calendar? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val zone = ZoneId.systemDefault()

        val dateTimes = listOf(dateTimeSeconds, dateTime, slashDateTime)
        dateTimes.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(value, format) }.getOrNull()
        }?.let { return calendarOf(it.atZone(zone).toInstant()) }

        val dates = listOf(date, slashDate)
        dates.firstNotNullOfOrNull { format ->
            runCatching { LocalDate.parse(value, format) }.getOrNull()
        }?.let { return calendarOf(it.atStartOfDay(zone).toInstant()) }

        TODAY_TIME.matchEntire(value)?.let { match ->
            val time = LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            return calendarOf(LocalDate.now(zone).atTime(time).atZone(zone).toInstant())
        }
        MONTH_DAY_TIME.matchEntire(value)?.let { match ->
            val date = LocalDate.of(
                LocalDate.now(zone).year,
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
            )
            val time = LocalTime.of(match.groupValues[3].toInt(), match.groupValues[4].toInt())
            return calendarOf(date.atTime(time).atZone(zone).toInstant())
        }
        return null
    }

    private fun calendarOf(instant: Instant): Calendar =
        Calendar.getInstance().apply { timeInMillis = instant.toEpochMilli() }

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(ZoneId.systemDefault())

    private val TODAY_TIME = Regex("""^今天\s+(\d{1,2}):(\d{2})$""")
    private val MONTH_DAY_TIME = Regex("""^(\d{1,2})月(\d{1,2})日\s+(\d{1,2}):(\d{2})$""")
}
