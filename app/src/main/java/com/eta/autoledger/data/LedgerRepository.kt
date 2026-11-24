package com.eta.autoledger.data

import android.content.Context
import com.eta.autoledger.widget.LedgerWidgetProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 小部件汇总与近 7 日趋势，全部走区间聚合，不把全表读进内存。 */
data class WidgetTotals(
    val todayExpense: Double = 0.0,
    val todayIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val monthCount: Int = 0,
    val sevenDayExpenses: List<Double> = List(7) { 0.0 },
)

/** 提醒（日报/周报/月报）用的区间汇总。 */
data class RangeTotals(
    val count: Int = 0,
    val expense: Double = 0.0,
    val income: Double = 0.0,
)

object LedgerRepository {

    /** 自动记账跨渠道去重窗口：短信与通知常差几秒各送一次同一笔扣费。 */
    private const val AUTO_IMPORT_WINDOW_MS = 2 * 60_000L

    /** 强指纹候选查询窗口，围绕入账单的发生时间双向放宽 5 分钟。 */
    private const val STRONG_MERGE_WINDOW_MS = 5 * 60_000L

    /** 自动记账的候选查询、来源合并和插入必须作为一个顺序队列执行。 */
    private val autoParsedMutex = Mutex()

    fun dao(context: Context): LedgerDao = LedgerDatabase.get(context).ledgerDao()

    fun observeAll(context: Context): Flow<List<LedgerEntry>> = dao(context).observeAll()

    fun observePaging(context: Context, limit: Int, offset: Int): Flow<List<LedgerEntry>> =
        dao(context).observePaging(limit, offset)

    fun observeCount(context: Context): Flow<Int> = dao(context).observeCount()

    /** 组合筛选，返回的是热 Flow：筛选条件一变，界面用新参数重新订阅即可。 */
    /**
     * 组合筛选。`categories` 为空表示不限分类。
     *
     * 空列表不能直接进 `IN (...)`（Room 会生成 `IN ()` 让 SQLite 报语法错误），
     * 所以这里用 `categoryMode` + 占位元素把「不过滤」和「过滤」分开表达。
     */
    fun search(
        context: Context,
        minAmount: Double,
        maxAmount: Double,
        start: Long,
        end: Long,
        type: String,
        categories: List<String>,
        keyword: String,
        limit: Int = 300,
    ): Flow<List<LedgerEntry>> =
        dao(context).search(
            minAmount = minAmount,
            maxAmount = maxAmount,
            start = start,
            end = end,
            type = type,
            categories = categories.ifEmpty { listOf("") },
            categoryMode = if (categories.isEmpty()) 0 else 1,
            keyword = keyword,
            limit = limit,
        )

    suspend fun countSearch(
        context: Context,
        minAmount: Double,
        maxAmount: Double,
        start: Long,
        end: Long,
        type: String,
        categories: List<String>,
        keyword: String,
    ): Int = dao(context).countSearch(
        minAmount = minAmount,
        maxAmount = maxAmount,
        start = start,
        end = end,
        type = type,
        categories = categories.ifEmpty { listOf("") },
        categoryMode = if (categories.isEmpty()) 0 else 1,
        keyword = keyword,
    )

    fun observeSummary(context: Context): Flow<Summary> {
        val now = Calendar.getInstance()
        val todayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val tomorrow = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val monthStart = (todayStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }

        val d = dao(context)
        return combine(
            d.observeExpense(todayStart.timeInMillis, tomorrow.timeInMillis),
            d.observeExpense(monthStart.timeInMillis, monthEnd.timeInMillis),
            d.observeIncome(monthStart.timeInMillis, monthEnd.timeInMillis),
        ) { today, month, income -> Summary(today, month, income) }
    }

    /**
     * 预算周期区间：从 [startDay] 日到次月 [startDay] 日前一天。
     *
     * [startDay] = 1 时就是自然月；= 25 时表示「25 号 ~ 次月 24 号」。
     * 上限 28 是刻意约束（29/30/31 在 2 月不存在，会让边界漂移）。
     */
    fun budgetCycleRange(startDay: Int, now: Calendar = Calendar.getInstance()): Pair<Long, Long> {
        val day = startDay.coerceIn(1, 28)
        val todayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // 本周期起点：若今天 >= startDay，起点在本月；否则在上月
        val start = (todayStart.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, day)
            if (todayStart.get(Calendar.DAY_OF_MONTH) < day) add(Calendar.MONTH, -1)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    /** 预算周期内的支出合计（单次 SQL 聚合，不读整表）。 */
    suspend fun budgetCycleExpense(context: Context, startDay: Int): Double {
        val (start, end) = budgetCycleRange(startDay)
        return dao(context).sumExpenseOnce(start, end)
    }

    /** 游标分页：拿比 [cursorTime]/[cursorId] 更旧的一页。 */
    suspend fun pageBefore(context: Context, cursorTime: Long, cursorId: Long, limit: Int): List<LedgerEntry> =
        dao(context).pageBefore(cursorTime, cursorId, limit)

    /**
     * 仅供确实在后台线程的调用方（备份、导出）使用。
     *
     * @param includeTemplates 备份必须为 true（模板要一起备份，否则换机丢周期账单）；
     *   CSV/XLSX 导出应为 false（模板不是一笔真实交易）。
     */
    suspend fun load(context: Context, includeTemplates: Boolean = false): List<LedgerEntry> =
        if (includeTemplates) dao(context).getAllOnce() else dao(context).getAllOnceReal()

    /**
     * 插入一笔账目，返回新记录的 rowId。
     *
     * `insertAll` 的 `@Insert` 已返回 `List<Long>`（被 IGNORE 的位置是 -1），
     * 这里透传第一条即可。调用方需要「刚插入的那笔」时（如截图记账的撤销通知）
     * 用它定位；不关心的调用点忽略返回值不受影响。
     */
    suspend fun add(context: Context, entry: LedgerEntry): Long = addAll(context, listOf(entry)).firstOrNull() ?: -1L

    suspend fun addAll(context: Context, entries: List<LedgerEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        val normalized = entries.map { it.normalized() }
        val ids = dao(context).insertAll(normalized)
        LedgerWidgetProvider.requestUpdate(context)
        return ids
    }

    /**
     * 自动记账跨渠道去重：短信与通知监听常把同一笔实际扣费各送一次，
     * 两者文本不同、时间戳差几秒，externalId 与时间指纹都对不上。
     * 只有窗口内存在「同金额 + 同收支 + 不同来源」的自动账单时才判重；
     * 同来源（同一通道）的同额账单不合并，避免误杀真实的两笔同额消费。
     */
    suspend fun isRecentDuplicate(
        context: Context,
        amount: Double,
        type: String,
        source: String,
        timeMillis: Long,
        windowMs: Long = AUTO_IMPORT_WINDOW_MS,
    ): Boolean {
        val now = System.currentTimeMillis()
        val since = (timeMillis - windowMs).coerceAtLeast(0L)
        return dao(context).countRecentImportedOtherSource(amount, type, source, since, now) > 0
    }

    /** 导入去重：优先交易单号，其次「毫秒时间 + 金额 + 收支」指纹；返回实际新增数。 */
    suspend fun addImported(context: Context, entries: List<LedgerEntry>): Int {
        if (entries.isEmpty()) return 0
        val dao = dao(context)
        val keys = mutableSetOf<String>()
        dao.importRows().forEach { row ->
            row.externalId?.takeIf { it.isNotBlank() }?.let(keys::add)
            keys.add(fingerprint(row.timestamp, row.amount, row.type))
        }
        val normalized = entries.map { it.normalized() }
        val toInsert = normalized
            .filter { entry -> keys.add(entry.importKey()) }
            .distinctBy { it.importKey() }
        if (toInsert.isEmpty()) return 0
        val rowIds = dao.insertAll(toInsert)
        LedgerWidgetProvider.requestUpdate(context)
        // 撞上 external_id 唯一索引（IGNORE）的位置返回 -1，不算新增。
        return rowIds.count { it != -1L }
    }

    /**
     * 自动记账专用入库：先做跨来源强指纹合并，再交给 [addImported] 做单号/时间指纹去重。
     *
     * 为什么数据库去重挡不住：`external_id` 由 `pkg|title|text|bigText|postTime` 算出，
     * 而 postTime 是**毫秒级**的。长短信被拆成多段、接收器被系统连调两次、或进程重启后
     * 补发时，同一笔账的 postTime 会差几毫秒 → external_id 与「毫秒+金额+收支」指纹
     * 双双失配 → 同一条短信写出两行。截图里 15:11 的两条 46.94 就是这么来的。
     *
     * 现有秒级内存指纹继续挡住重复投递。数据库侧再按“金额分 + 方向 + 分钟桶 + 卡尾”
     * 查询 ±5 分钟候选，强指纹命中时只合并来源，不再新增第二笔。
     * 只用于短信 / 通知通道；手动记账、CSV 导入、云恢复走 [addImported] / [add]，行为不变。
     */
    suspend fun addAutoParsed(context: Context, entries: List<LedgerEntry>): Int = autoParsedMutex.withLock {
        if (entries.isEmpty()) return@withLock 0
        val now = System.currentTimeMillis()
        val dao = dao(context)
        var added = 0

        for (raw in entries) {
            val entry = raw.normalized()
            val stamp = entry.timeMillis.takeIf { it > 0 } ?: now
            val tail = ParseDedup.accountTailFromText(entry.note)
            val strong = ParseDedup.strongFingerprint(entry.amount, entry.type, stamp, tail)
            val candidates = dao.strongMergeCandidates(
                amountCents = ParseDedup.amountFen(entry.amount),
                type = entry.type,
                since = stamp - STRONG_MERGE_WINDOW_MS,
                until = stamp + STRONG_MERGE_WINDOW_MS,
            )
            val matched = findStrongMergeTarget(entry, candidates, strong)
            if (matched != null) {
                val mergedSource = ParseDedup.mergeSources(matched.source, entry.source)
                if (mergedSource != matched.source) {
                    dao.updateSource(matched.id, mergedSource)
                    LedgerWidgetProvider.requestUpdate(context)
                }
                continue
            }

            val merchant = entry.note.ifBlank { entry.category }
            if (!ParseDedup.accept(ParseDedup.fingerprint(entry.amount, merchant, stamp), now)) continue
            added += addImported(context, listOf(entry))
        }
        added
    }

    private fun LedgerEntry.normalized(): LedgerEntry =
        if (timeMillis > 0) this else copy(
            timeMillis = parseTime(time)?.timeInMillis ?: System.currentTimeMillis(),
        )

    private fun sourcesOf(source: String): Set<String> =
        source.split(" + ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 供数据层测试复用：仅跨来源、强指纹完全一致时才允许合并。 */
    internal fun findStrongMergeTarget(
        incoming: LedgerEntry,
        candidates: List<LedgerEntry>,
        incomingStrongFingerprint: String = ParseDedup.strongFingerprint(
            incoming.amount,
            incoming.type,
            incoming.timeMillis,
            ParseDedup.accountTailFromText(incoming.note),
        ),
    ): LedgerEntry? = candidates.firstOrNull { candidate ->
        sourcesOf(candidate.source).intersect(sourcesOf(incoming.source)).isEmpty() &&
            ParseDedup.strongFingerprint(
                amount = candidate.amount,
                type = candidate.type,
                timeMillis = candidate.timeMillis,
                accountTail = ParseDedup.accountTailFromText(candidate.note),
            ) == incomingStrongFingerprint
    }

    /** 指纹用毫秒时间：分钟级会把「同分钟同额同类型」的两笔真实账单误判成一笔。 */
    private fun fingerprint(timeMillis: Long, amount: Double, type: String): String =
        "$timeMillis|${"%.2f".format(amount)}|$type"

    private fun LedgerEntry.importKey(): String =
        externalId?.takeIf { it.isNotBlank() } ?: fingerprint(timeMillis, amount, type)

    /** 修复旧版本导入导致的 timestamp=0 数据。 */
    suspend fun repairTimestamps(context: Context) {
        val dao = dao(context)
        dao.getMissingTimestamp().forEach { entry ->
            val millis = parseTime(entry.time)?.timeInMillis ?: System.currentTimeMillis()
            dao.setTimestamp(entry.id, millis)
        }
    }

    /** 小部件汇总 + 近 7 日支出，逐项使用一次性聚合，不改变其他统计口径。 */
    suspend fun widgetTotals(context: Context): WidgetTotals {
        val now = Calendar.getInstance()
        val todayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val tomorrow = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val monthStart = (todayStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val trendStart = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
        val d = dao(context)
        val dayRows = d.sumExpenseByDayOnce(trendStart.timeInMillis, tomorrow.timeInMillis)
            .associate { it.day to it.total }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sevenDayExpenses = List(7) { index ->
            val day = (trendStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, index) }
            dayRows[dayFormat.format(day.time)] ?: 0.0
        }
        return WidgetTotals(
            todayExpense = d.sumExpenseOnce(todayStart.timeInMillis, tomorrow.timeInMillis),
            todayIncome = d.sumIncomeOnce(todayStart.timeInMillis, tomorrow.timeInMillis),
            monthExpense = d.sumExpenseOnce(monthStart.timeInMillis, monthEnd.timeInMillis),
            monthIncome = d.sumIncomeOnce(monthStart.timeInMillis, monthEnd.timeInMillis),
            monthCount = d.countRange(monthStart.timeInMillis, monthEnd.timeInMillis),
            sevenDayExpenses = sevenDayExpenses,
        )
    }

    /** 提醒用的区间汇总：三条聚合查询，不读整表。 */
    suspend fun rangeTotals(context: Context, start: Long, end: Long): RangeTotals {
        val d = dao(context)
        return RangeTotals(
            count = d.countRange(start, end),
            expense = d.sumExpenseOnce(start, end),
            income = d.sumIncomeOnce(start, end),
        )
    }

    suspend fun update(context: Context, entry: LedgerEntry) {
        dao(context).update(entry)
        LedgerWidgetProvider.requestUpdate(context)
    }

    suspend fun delete(context: Context, id: Long) {
        dao(context).deleteById(id)
        LedgerWidgetProvider.requestUpdate(context)
    }

    /**
     * 撤销删除：把被删账目按原 id 写回。
     *
     * 用 `insertAll` 直接写回带原 id 的实体：`add` 会走 autoGenerate 生成新 id，
     * 撤销后列表顺序会变。DAO 的 `@Insert` 接受显式 id，`IGNORE` 策略下若 id 已存在
     * （极小概率）会静默跳过，不会产生脏数据。
     */
    suspend fun restore(context: Context, entry: LedgerEntry) {
        dao(context).insertAll(listOf(entry))
        LedgerWidgetProvider.requestUpdate(context)
    }

    /** 按 [LedgerEntry.externalId] 删除：自动记账的通知里只带了外部单号，没有自增主键。 */
    suspend fun deleteByExternalId(context: Context, externalId: String): Int {
        val removed = dao(context).deleteByExternalId(externalId)
        LedgerWidgetProvider.requestUpdate(context)
        return removed
    }

    suspend fun clear(context: Context) {
        dao(context).clear()
        LedgerWidgetProvider.requestUpdate(context)
    }

    fun parseTime(raw: String): Calendar? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/M/d H:mm",
            "yyyy/M/d",
            "MM月dd日 HH:mm",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).parse(raw)
            }.getOrNull()
            if (parsed != null) return Calendar.getInstance().apply { time = parsed }
        }
        return null
    }

    fun formatTime(cal: Calendar = Calendar.getInstance()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(cal.time)

    fun dayLabel(cal: Calendar): String =
        SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(cal.time) + " " + weekday(cal)

    fun weekday(cal: Calendar): String = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "星期一"
        Calendar.TUESDAY -> "星期二"
        Calendar.WEDNESDAY -> "星期三"
        Calendar.THURSDAY -> "星期四"
        Calendar.FRIDAY -> "星期五"
        Calendar.SATURDAY -> "星期六"
        else -> "星期日"
    }
}
