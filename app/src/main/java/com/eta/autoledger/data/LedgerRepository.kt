package com.eta.autoledger.data

import android.content.Context
import com.eta.autoledger.widget.LedgerWidgetProvider
import com.eta.autoledger.util.AppDateFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.UUID

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

    private val TRANSFER_TYPES = setOf("expense", "income")

    /** 自动记账的候选查询和来源合并必须作为一个顺序队列执行。 */
    private val autoParsedMutex = Mutex()

    /** 手动保存、导入与自动入账可能并发；进程内串行化后再由 DAO 事务做最终认领。 */
    private val transferMutex = Mutex()

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
    suspend fun add(context: Context, entry: LedgerEntry): Long = transferMutex.withLock {
        val normalized = entry.normalized()
        val id = dao(context).insertAll(listOf(normalized)).firstOrNull() ?: -1L
        if (id > 0L && !normalized.isRecurring) {
            mergeTransferLocked(context, normalized.copy(id = id))
        }
        LedgerWidgetProvider.requestUpdate(context)
        id
    }

    suspend fun addAll(context: Context, entries: List<LedgerEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        return transferMutex.withLock {
            val dao = dao(context)
            val normalized = entries.map { it.normalized() }
            val ids = dao.insertAll(normalized)
            val insertedRows = ids.mapNotNull { id ->
                if (id <= 0L) null else dao.getById(id)
            }.filter { !it.isTransfer && !it.isRecurring }
            mergeTransferBatchLocked(context, dao, insertedRows)
            LedgerWidgetProvider.requestUpdate(context)
            ids
        }
    }

    /** 导入去重：优先交易单号，其次「毫秒时间 + 金额 + 收支」指纹；返回实际新增数。 */
    suspend fun addImported(context: Context, entries: List<LedgerEntry>): Int {
        if (entries.isEmpty()) return 0
        val dao = dao(context)
        val rowIds = transferMutex.withLock {
            // 去重快照与插入必须同一串行区，否则两个自动入口可能同时通过 keys 过滤。
            val keys = mutableSetOf<String>()
            dao.importRows().forEach { row ->
                row.externalId?.takeIf { it.isNotBlank() }?.let(keys::add)
                keys.add(fingerprint(row.timestamp, row.amount, row.type))
            }
            val normalized = entries.map { it.normalized() }
            val toInsert = normalized
                .filter { entry -> keys.add(entry.importKey()) }
                .distinctBy { entry -> entry.importKey() }
            if (toInsert.isEmpty()) return@withLock emptyList()
            val inserted = dao.insertAll(toInsert)
            val insertedRows = inserted.mapNotNull { id ->
                if (id <= 0L) null else dao.getById(id)
            }.filter { !it.isTransfer && !it.isRecurring }
            // 同批内部先配对，避免第一条支出提前认领库里时间更近的旧收入。
            mergeTransferBatchLocked(context, dao, insertedRows)
            inserted
        }
        // 配对状态改变不影响新增计数，也不能把同批的第二条当成重复。
        LedgerWidgetProvider.requestUpdate(context)
        // 撞上 external_id 唯一索引（IGNORE）的位置返回 -1，不算新增。
        return rowIds.count { it != -1L }
    }

    /**
     * 自动记账专用入库：先做时间窗跨源合并，再做单号/时间指纹去重与转账配对。
     *
     * 为什么数据库去重挡不住：`external_id` 由 `pkg|title|text|bigText|postTime` 算出，
     * 而 postTime 是**毫秒级**的。长短信被拆成多段、接收器被系统连调两次、或进程重启后
     * 补发时，同一笔账的 postTime 会差几毫秒 → external_id 与「毫秒+金额+收支」指纹
     * 双双失配 → 同一条短信写出两行。截图里 15:11 的两条 46.94 就是这么来的。
     *
     * 数据库按“金额分 + 方向 + ±5 分钟”只取候选，再由 [ParseDedup.shouldMerge]
     * 执行跨钱包/银行来源合并或 1 分钟内的同额快速合并。
     * 只用于短信 / 通知通道；手动记账、CSV 导入、云恢复分别走 [add] / [addImported]，
     * 同样在去重完成后触发转账配对。
     */
    suspend fun addAutoParsed(
        context: Context,
        entries: List<LedgerEntry>,
    ): Int = autoParsedMutex.withLock {
        if (entries.isEmpty()) return@withLock 0
        val now = System.currentTimeMillis()
        val dao = dao(context)
        var added = 0

        for (raw in entries) {
            val entry = raw.normalized()
            val stamp = entry.timeMillis.takeIf { it > 0 } ?: now
            val candidates = dao.timedMergeCandidates(
                amountCents = ParseDedup.amountFen(entry.amount),
                type = entry.type,
                since = stamp - ParseDedup.MERGE_WINDOW_MS,
                until = stamp + ParseDedup.MERGE_WINDOW_MS,
            )
            val matched = ParseDedup.chooseMergeTarget(entry, candidates)
            if (matched != null) {
                val merged = ParseDedup.mergeRecords(matched, entry)
                if (merged != matched) {
                    dao.update(merged)
                    LedgerWidgetProvider.requestUpdate(context)
                }
                continue
            }

            val merchant = entry.note.ifBlank { entry.category }
            if (!ParseDedup.accept(ParseDedup.fingerprint(entry.amount, merchant, stamp), now)) continue
            // 统一走导入入口，保持单号/毫秒指纹去重与转账配对共用同一条写路径。
            added += addImported(context, listOf(entry))
        }
        added
    }

    private fun LedgerEntry.normalized(): LedgerEntry =
        if (timeMillis > 0) this else copy(
            timeMillis = parseTime(time)?.timeInMillis ?: System.currentTimeMillis(),
        )

    /** 指纹用毫秒时间与金额分：避免 Locale 和 Double 文本改变身份键。 */
    private fun fingerprint(timeMillis: Long, amount: Double, type: String): String =
        "$timeMillis|${FingerprintCodec.amountFenToken(amount)}|$type"

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
        val sevenDayExpenses = List(7) { index ->
            val day = (trendStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, index) }
            dayRows[AppDateFormats.format(day, AppDateFormats.date)] ?: 0.0
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
        transferMutex.withLock {
            val dao = dao(context)
            val previous = dao.getById(entry.id)
            previous?.transferPairId?.let { pairId ->
                dao.releaseTransferPairAtomically(pairId)
            }
            val updated = entry.copy(isTransfer = false, transferPairId = null)
            dao.update(updated)
            // 编辑即视为用户明确纠正：不自动重新合并，避免刚改完又被系统锁住。
            LedgerWidgetProvider.requestUpdate(context)
        }
    }

    suspend fun delete(context: Context, id: Long): LedgerEntry? {
        var snapshot: LedgerEntry? = null
        transferMutex.withLock {
            val dao = dao(context)
            // 删除单边时先清空配对标记，让另一半立即恢复普通账单；撤销时再按快照恢复。
            snapshot = dao.deleteEntryPreservingTransferSnapshot(id)
            LedgerWidgetProvider.requestUpdate(context)
        }
        return snapshot
    }

    /**
     * 去重完成后调用：在同一事务里认领一对方向相反、金额相同、时间相近且有转账语义的账单。
     *
     * 原始 category 不覆盖，列表展示时才映射为“内部转账”。这样用户解除合并后，
     * 两条记录会恢复原有分类与统计语义。
     */
    suspend fun detectAndMergeTransfer(context: Context, entry: LedgerEntry): String? {
        return transferMutex.withLock {
            mergeTransferLocked(context, entry)
        }
    }

    private suspend fun mergeTransferLocked(context: Context, entry: LedgerEntry): String? {
        if (entry.id <= 0L || entry.isRecurring || entry.isTransfer || entry.transferPairId != null) return null
        val dao = dao(context)
        val current = dao.getById(entry.id)?.takeIf { !it.isTransfer } ?: return null
        if (current.transferPairId != null) return null
        val stamp = current.timeMillis.takeIf { it > 0L } ?: return null
        // 只允许支出/收入参与转账；其他类型既不查也不配。
        if (current.type !in TRANSFER_TYPES) return null
        val candidates = dao.transferPairCandidates(
            entryId = current.id,
            oppositeType = if (current.type == "income") "expense" else "income",
            amountCents = kotlin.math.abs(FingerprintCodec.amountFen(current.amount)),
            since = stamp - TransferPairMatcher.WINDOW_MS,
            until = stamp + TransferPairMatcher.WINDOW_MS,
            centerTime = stamp,
        )
        val matched = TransferPairMatcher.chooseCandidate(current, candidates) ?: return null
        val pairId = UUID.randomUUID().toString()
        if (!dao.claimTransferPairAtomically(pairId, current.id, matched.id)) return null
        LedgerWidgetProvider.requestUpdate(context)
        return pairId
    }

    /**
     * 批量写入先在同批内部配对，再让剩余记录与库内旧记录配对。
     * 这样新导入的转出不会先抢走旧收入，导致同批真正的转入被孤立。
     */
    private suspend fun mergeTransferBatchLocked(
        context: Context,
        dao: LedgerDao,
        rows: List<LedgerEntry>,
    ) {
        val localPairs = TransferPairMatcher.chooseLocalPairs(rows)
        val claimedIds = localPairs.flatMapTo(mutableSetOf()) { (first, second) ->
            listOf(first.id, second.id)
        }
        localPairs.forEach { (first, local) ->
            val pairId = UUID.randomUUID().toString()
            if (dao.claimTransferPairAtomically(pairId, first.id, local.id)) {
                LedgerWidgetProvider.requestUpdate(context)
            }
        }
        rows.forEach { row ->
            if (row.id in claimedIds) return@forEach
            dao.getById(row.id)?.takeIf { !it.isTransfer && !it.isRecurring }?.let { remaining ->
                mergeTransferLocked(context, remaining)
            }
        }
    }

    /** 成对解除合并，返回实际恢复的记录数。 */
    suspend fun releaseTransferPair(context: Context, pairId: String): Int {
        return transferMutex.withLock {
            val released = dao(context).releaseTransferPairAtomically(pairId)
            if (released > 0) LedgerWidgetProvider.requestUpdate(context)
            released
        }
    }

    /** 恢复后校验：不完整或条件已变化的配对降级为普通账单。 */
    suspend fun releaseInvalidTransferPairs(context: Context): Int {
        return transferMutex.withLock {
            val released = dao(context).releaseInvalidTransferPairs()
            if (released > 0) LedgerWidgetProvider.requestUpdate(context)
            released
        }
    }

    suspend fun countSubCategoryBills(
        context: Context,
        type: String,
        category: String,
        subCategory: String,
    ): Int = dao(context).countSubCategoryBills(type, category, subCategory)

    suspend fun moveSubCategoryBills(
        context: Context,
        type: String,
        category: String,
        source: String,
        target: String?,
    ): Int = dao(context).moveSubCategoryBills(type, category, source, target)

    /**
     * 撤销删除：把被删账目按原 id 写回。
     *
     * 用 `insertAll` 直接写回带原 id 的实体：`add` 会走 autoGenerate 生成新 id，
     * 撤销后列表顺序会变。DAO 的 `@Insert` 接受显式 id，`IGNORE` 策略下若 id 已存在
     * （极小概率）会静默跳过，不会产生脏数据。
     */
    suspend fun restore(context: Context, entry: LedgerEntry) {
        transferMutex.withLock {
            val dao = dao(context)
            val pairId = entry.transferPairId
            val restored = entry.copy(isTransfer = false, transferPairId = null)
            dao.insertAll(listOf(restored))
            if (pairId != null) dao.restoreTransferPairAtomically(entry)
            LedgerWidgetProvider.requestUpdate(context)
        }
    }

    /** 按 [LedgerEntry.externalId] 删除：自动记账的通知里只带了外部单号，没有自增主键。 */
    suspend fun deleteByExternalId(context: Context, externalId: String): Int {
        return transferMutex.withLock {
            val dao = dao(context)
            val removed = dao.deleteByExternalId(externalId)
            if (removed > 0) {
                // 该入口不带自增主键，无法精确定位另一半；清除悬空的成对标记，
                // 避免统计继续隐藏一条已经不存在的转账。
                dao.clearDanglingTransferPairs()
            }
            LedgerWidgetProvider.requestUpdate(context)
            removed
        }
    }

    suspend fun clear(context: Context) {
        dao(context).clear()
        LedgerWidgetProvider.requestUpdate(context)
    }

    fun parseTime(raw: String): Calendar? = AppDateFormats.parse(raw)

    fun formatTime(cal: Calendar = Calendar.getInstance()): String =
        AppDateFormats.format(cal, AppDateFormats.dateTime)

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
