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

    /**
     * 预算周期起点：把「自然月」换成「起始日 ~ 次月起始日前一天」。
     *
     * [startDay] = 1 时就是自然月（行为与改动前完全一致）；
     * = 25 时表示「上月 25 日 ~ 本月 24 日」。[offset] 为往前推的月数。
     */
    private fun budgetMonthStart(now: Calendar, startDay: Int, offset: Int): Calendar {
        val day = startDay.coerceIn(1, 28)
        val today = now.clone() as Calendar
        val start = (today.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_MONTH, day)
            // 今天还没到起始日 → 当前周期属于上个月开始的
            if (today.get(Calendar.DAY_OF_MONTH) < day) add(Calendar.MONTH, -1)
            add(Calendar.MONTH, -offset)
        }
        return start
    }

    /** 与 observe 相同的周期口径，供明细钻取复用。 */
    fun periodRange(tab: Int, offset: Int, startDay: Int = 1): Pair<Long, Long> {
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
                periodStart = budgetMonthStart(now, startDay, offset)
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

    fun observe(
        context: Context,
        tab: Int,
        offset: Int,
        income: Boolean,
        startDay: Int = 1,
    ): Flow<ReportAggregate> {
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
                periodStart = budgetMonthStart(now, startDay, offset)
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
            1 -> {
                val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA)
                val end = (periodEnd.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                if (startDay == 1) {
                    SimpleDateFormat("yyyy年M月", Locale.CHINA).format(periodStart.time)
                } else {
                    // 起始日非 1：必须写出真实区间，否则用户会以为这是自然月数据
                    "${fmt.format(periodStart.time)}～${SimpleDateFormat("MM.dd", Locale.CHINA).format(end.time)}"
                }
            }
            else -> SimpleDateFormat("yyyy年", Locale.CHINA).format(periodStart.time)
        }
        val periodName = when (tab) { 0 -> "本周"; 1 -> "本月"; else -> "本年" }
        val compareLabel = when (tab) { 0 -> "比上周"; 1 -> "比上月"; else -> "比去年" }
        val dayCount = when (tab) {
            0 -> 7
            // 起始日非 1 时周期长度不是「本月天数」，按实际区间算，否则日均会失真
            1 -> (((periodEnd.timeInMillis - periodStart.timeInMillis) / 86_400_000L).toInt()).coerceAtLeast(1)
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

        // 4 条 observeTotal → 1 条 observeTotals：一次索引扫描取回本期/上期/支出/收入。
        // 原来 4 个独立 Flow 各自订阅表变更，一次写库触发 4 次重查；现在只触发 1 次。
        // outer 边界 = 两个区间的并集，让 SQLite 能用一次索引范围扫描覆盖两段
        val cs = periodStart.timeInMillis
        val ce = periodEnd.timeInMillis
        val ps = prevStart.timeInMillis
        val pe = prevEnd.timeInMillis
        val totals = d.observeTotals(
            cs = cs, ce = ce,
            ps = ps, pe = pe,
            outerStart = minOf(cs, ps), outerEnd = maxOf(ce, pe),
            income = incomeFlag,
        )
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
                total = t.current,
                previousTotal = t.previous,
                expense = t.expense,
                income = t.income,
                trend = c.first,
                bars = c.second,
                categories = c.third,
                dayCount = dayCount,
            )
        }
    }
}