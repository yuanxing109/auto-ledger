package com.eta.autoledger.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ReportAggregate(
    val periodLabel: String = "",
    val periodName: String = "本月",
    val compareLabel: String = "比上月",
    val total: Double = 0.0,
    val previousTotal: Double = 0.0,
    val expense: Double = 0.0,
    val income: Double = 0.0,
    val trend: List<Bucket> = emptyList(),
    val bars: List<Bucket> = emptyList(),
    val categories: List<CategoryAgg> = emptyList(),
    val dayCount: Int = 30,
)

object ReportRepository {

    /** 与 observe 相同的周期口径，供明细钻取复用。 */
    fun periodRange(tab: Int, offset: Int): Pair<Long, Long> {
        val now = Calendar.getInstance()
        fun startOfWeek(base: Calendar): Calendar = (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val dow = get(Calendar.DAY_OF_WEEK)
            add(Calendar.DAY_OF_YEAR, if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow)
        }
        val periodStart: Calendar
        val periodEnd: Calendar
        when (tab) {
            0 -> {
                periodStart = startOfWeek(now).apply { add(Calendar.DAY_OF_YEAR, -offset * 7) }
                periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
            }
            1 -> {
                periodStart = (now.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.MONTH, -offset)
                }
                periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            }
            else -> {
                periodStart = (now.clone() as Calendar).apply {
                    set(Calendar.MONTH, 0); set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.YEAR, -offset)
                }
                periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
            }
        }
        return Pair(periodStart.timeInMillis, periodEnd.timeInMillis)
    }

    fun observe(context: Context, tab: Int, offset: Int, income: Boolean): Flow<ReportAggregate> {
        val now = Calendar.getInstance()
        fun startOfWeek(base: Calendar): Calendar = (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val dow = get(Calendar.DAY_OF_WEEK)
            add(Calendar.DAY_OF_YEAR, if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow)
        }

        val periodStart: Calendar
        val periodEnd: Calendar
        val prevStart: Calendar
        val prevEnd: Calendar
        when (tab) {
            0 -> {
                periodStart = startOfWeek(now).apply { add(Calendar.DAY_OF_YEAR, -offset * 7) }
                periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
                prevStart = (periodStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
                prevEnd = periodStart
            }
            1 -> {
                periodStart = (now.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.MONTH, -offset)
                }
                periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                prevStart = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                prevEnd = periodStart
            }
            else -> {
                periodStart = (now.clone() as Calendar).apply {
                    set(Calendar.MONTH, 0); set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.YEAR, -offset)
                }
                periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
                prevStart = (periodStart.clone() as Calendar).apply { add(Calendar.YEAR, -1) }
                prevEnd = periodStart
            }
        }

        val periodLabel = when (tab) {
            0 -> {
                val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA)
                val end = (periodEnd.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                "${fmt.format(periodStart.time)}～${SimpleDateFormat("MM.dd", Locale.CHINA).format(end.time)}"
            }
            1 -> SimpleDateFormat("yyyy年M月", Locale.CHINA).format(periodStart.time)
            else -> SimpleDateFormat("yyyy年", Locale.CHINA).format(periodStart.time)
        }
        val periodName = when (tab) { 0 -> "本周"; 1 -> "本月"; else -> "本年" }
        val compareLabel = when (tab) { 0 -> "比上周"; 1 -> "比上月"; else -> "比去年" }
        val dayCount = when (tab) {
            0 -> 7
            1 -> periodEnd.getActualMaximum(Calendar.DAY_OF_MONTH)
            else -> if (periodEnd.get(Calendar.YEAR) % 4 == 0) 366 else 365
        }

        val trendFormat = if (tab == 2) "%Y-%m" else "%Y-%m-%d"
        val barsFormat = when (tab) { 0 -> "%Y-%W"; 1 -> "%Y-%m"; else -> "%Y" }
        val barsStart = when (tab) {
            0 -> (periodStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -5 * 7) }
            1 -> (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, -5) }
            else -> (periodStart.clone() as Calendar).apply { add(Calendar.YEAR, -5) }
        }
        val incomeFlag = if (income) 1 else 0
        val d = LedgerRepository.dao(context)

        val totals = combine(
            d.observeTotal(periodStart.timeInMillis, periodEnd.timeInMillis, incomeFlag),
            d.observeTotal(prevStart.timeInMillis, prevEnd.timeInMillis, incomeFlag),
            d.observeTotal(periodStart.timeInMillis, periodEnd.timeInMillis, 0),
            d.observeTotal(periodStart.timeInMillis, periodEnd.timeInMillis, 1),
        ) { current, previous, expense, incomeTotal ->
            doubleArrayOf(current, previous, expense, incomeTotal)
        }
        val charts = combine(
            d.observeBuckets(trendFormat, periodStart.timeInMillis, periodEnd.timeInMillis, incomeFlag),
            d.observeBuckets(barsFormat, barsStart.timeInMillis, periodEnd.timeInMillis, incomeFlag),
            d.observeCategories(periodStart.timeInMillis, periodEnd.timeInMillis, incomeFlag),
        ) { trend, bars, categories -> Triple(trend, bars, categories) }

        return combine(totals, charts) { t, c ->
            ReportAggregate(
                periodLabel = periodLabel,
                periodName = periodName,
                compareLabel = compareLabel,
                total = t[0],
                previousTotal = t[1],
                expense = t[2],
                income = t[3],
                trend = c.first,
                bars = c.second,
                categories = c.third,
                dayCount = dayCount,
            )
        }
    }
}