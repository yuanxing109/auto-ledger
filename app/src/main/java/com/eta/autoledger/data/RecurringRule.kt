package com.eta.autoledger.data

import java.util.Calendar

/**
 * 周期记账规则。
 *
 * 格式刻意做成字符串而不是结构化对象：规则要存进 SQLite 单列，
 * 序列化成 "WEEKLY_1" / "MONTHLY_5" / "YEARLY_3-15" 比塞 JSON 更省事，
 * 而且人能直接读懂数据库里的值。
 *
 * 只支持「每周 / 每月 / 每年」三种基础周期，不做节假日跳过、不做末日顺延。
 */
sealed class RecurringRule {

    /** 规则串，存进 LedgerEntry.recurringRule。 */
    abstract val raw: String

    /** 人类可读描述，用于 UI 与通知文案。 */
    abstract val label: String

    /** 判断 [day]（当天 00:00）是否命中本规则。 */
    abstract fun matches(day: Calendar): Boolean

    /** 每周第 [dow] 天（1=周一 … 7=周日）。 */
    data class Weekly(val dow: Int) : RecurringRule() {
        override val raw: String get() = "WEEKLY_$dow"
        override val label: String
            get() = "每周" + arrayOf("一", "二", "三", "四", "五", "六", "日")[(dow - 1).coerceIn(0, 6)]

        override fun matches(day: Calendar): Boolean {
            // Calendar.MONDAY = 2 … SUNDAY = 1，换算成 1..7（周一开头）
            val cal = day.get(Calendar.DAY_OF_WEEK)
            val normalized = if (cal == Calendar.SUNDAY) 7 else cal - 1
            return normalized == dow
        }
    }

    /** 每月第 [dayOfMonth] 天（1..28）。 */
    data class Monthly(val dayOfMonth: Int) : RecurringRule() {
        override val raw: String get() = "MONTHLY_$dayOfMonth"
        override val label: String get() = "每月 $dayOfMonth 日"

        override fun matches(day: Calendar): Boolean =
            day.get(Calendar.DAY_OF_MONTH) == dayOfMonth.coerceIn(1, 28)
    }

    /** 每年 [month] 月 [dayOfMonth] 日。 */
    data class Yearly(val month: Int, val dayOfMonth: Int) : RecurringRule() {
        override val raw: String get() = "YEARLY_$month-$dayOfMonth"
        override val label: String get() = "每年 $month 月 $dayOfMonth 日"

        override fun matches(day: Calendar): Boolean =
            day.get(Calendar.MONTH) + 1 == month && day.get(Calendar.DAY_OF_MONTH) == dayOfMonth
    }

    companion object {
        /**
         * 解析规则串。无法识别时返回 null——Worker 遇到 null 直接跳过该模板，
         * 不做「猜一个默认值」这种会生成错误账单的兜底。
         */
        fun parse(raw: String?): RecurringRule? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split("_")
            return runCatching {
                when (parts.getOrNull(0)) {
                    "WEEKLY" -> Weekly(parts[1].toInt())
                    "MONTHLY" -> Monthly(parts[1].toInt())
                    "YEARLY" -> {
                        val md = parts[1].split("-")
                        Yearly(md[0].toInt(), md[1].toInt())
                    }
                    else -> null
                }
            }.getOrNull()
        }

        /** 把某一天（默认今天）编码成「每周/每月」规则，供 AddScreen 快速生成。 */
        fun fromDate(period: Period, day: Calendar): RecurringRule = when (period) {
            Period.WEEKLY -> {
                val cal = day.get(Calendar.DAY_OF_WEEK)
                Weekly(if (cal == Calendar.SUNDAY) 7 else cal - 1)
            }
            Period.MONTHLY -> Monthly(day.get(Calendar.DAY_OF_MONTH).coerceIn(1, 28))
            Period.YEARLY -> Yearly(day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
        }
    }

    /** 周期种类，供 UI 选择器使用。 */
    enum class Period { WEEKLY, MONTHLY, YEARLY }
}
