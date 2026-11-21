package com.eta.autoledger.data

import android.content.Context
import com.eta.autoledger.widget.LedgerWidgetProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 小部件/提醒要的三个数，一次 SQL 聚合拿全，不要把全表读进内存。 */
data class WidgetTotals(
    val todayExpense: Double = 0.0,
    val todayIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val monthCount: Int = 0,
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

    /** 游标分页：拿比 [cursorTime]/[cursorId] 更旧的一页。 */
    suspend fun pageBefore(context: Context, cursorTime: Long, cursorId: Long, limit: Int): List<LedgerEntry> =
        dao(context).pageBefore(cursorTime, cursorId, limit)

    /** 仅供确实在后台线程的调用方（迁移、报表导出）使用。 */
    suspend fun load(context: Context): List<LedgerEntry> = dao(context).getAllOnce()

    suspend fun add(context: Context, entry: LedgerEntry) = addAll(context, listOf(entry))

    suspend fun addAll(context: Context, entries: List<LedgerEntry>) {
        if (entries.isEmpty()) return
        val normalized = entries.map { it.normalized() }
        dao(context).insertAll(normalized)
        LedgerWidgetProvider.requestUpdate(context)
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

    private fun LedgerEntry.normalized(): LedgerEntry =
        if (timeMillis > 0) this else copy(
            timeMillis = parseTime(time)?.timeInMillis ?: System.currentTimeMillis(),
        )

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

    /** 小部件的三个数：两条 SUM 区间查询，不碰全表。 */
    suspend fun widgetTotals(context: Context): WidgetTotals {
        val now = Calendar.getInstance()
        val todayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val tomorrow = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val monthStart = (todayStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val d = dao(context)
        return WidgetTotals(
            todayExpense = d.sumExpenseOnce(todayStart.timeInMillis, tomorrow.timeInMillis),
            todayIncome = d.sumIncomeOnce(todayStart.timeInMillis, tomorrow.timeInMillis),
            monthExpense = d.sumExpenseOnce(monthStart.timeInMillis, monthEnd.timeInMillis),
            monthIncome = d.sumIncomeOnce(monthStart.timeInMillis, monthEnd.timeInMillis),
            monthCount = d.countRange(monthStart.timeInMillis, monthEnd.timeInMillis),
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