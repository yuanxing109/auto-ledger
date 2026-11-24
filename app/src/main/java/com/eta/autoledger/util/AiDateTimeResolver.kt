package com.eta.autoledger.util

import java.util.Calendar
import java.util.concurrent.TimeUnit

internal data class ResolvedAiTime(
    val dateOffset: Int,
    val display: String,
    val timeMillis: Long,
)

/** 将 AI 返回的相对日期、绝对日期和原文时间归一为可落账的分钟级时间。 */
internal object AiDateTimeResolver {

    fun resolve(rawTime: String, fallbackText: String, nowMillis: Long): ResolvedAiTime {
        val normalized = rawTime.trim()
        val parsedAbsolute = AppDateFormats.parse(normalized)
        if (parsedAbsolute != null) {
            parsedAbsolute.set(Calendar.SECOND, 0)
            parsedAbsolute.set(Calendar.MILLISECOND, 0)
            val offset = dayDistance(parsedAbsolute.timeInMillis, nowMillis)
            if (offset in MIN_OFFSET..MAX_OFFSET) {
                return parsedAbsolute.toResolved(offset)
            }
        }

        val offset = relativeOffset(normalized) ?: relativeOffset(fallbackText) ?: 0
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, offset)
            extractClock(normalized)?.let { (hour, minute) ->
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.toResolved(offset)
    }

    private fun relativeOffset(text: String): Int? = when {
        "前天" in text -> -2
        "昨天" in text -> -1
        "今天" in text -> 0
        else -> null
    }

    private fun extractClock(text: String): Pair<Int, Int>? {
        val match = CLOCK.find(text) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun dayDistance(targetMillis: Long, nowMillis: Long): Int {
        fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return TimeUnit.MILLISECONDS.toDays(startOfDay(targetMillis) - startOfDay(nowMillis)).toInt()
    }

    private fun Calendar.toResolved(offset: Int): ResolvedAiTime = ResolvedAiTime(
        dateOffset = offset,
        display = AppDateFormats.format(this, AppDateFormats.dateTime),
        timeMillis = timeInMillis,
    )

    private val CLOCK = Regex("""(?<!\d)([01]?\d|2[0-3])(?:[:：]|点)([0-5]?\d)(?:分)?""")
    private const val MIN_OFFSET = -31
    private const val MAX_OFFSET = 0
}
