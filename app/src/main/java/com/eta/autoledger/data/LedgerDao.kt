package com.eta.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class Bucket(val bucket: String, val total: Double)

/** 小部件近 7 日趋势的一天支出。 */
data class DayAmount(val day: String, val total: Double)

data class CategoryAgg(
    val name: String,
    val total: Double,
    val count: Int,
    @ColumnInfo(name = "subCategory") val subCategory: String? = null,
)

/** DAO 与纯 JVM SQLite 测试共用的查询语句，避免测试复制一份不同 SQL。 */
internal object LedgerDaoSql {
    const val DUAL_CATEGORY_AGGREGATION =
        "SELECT category AS name, sub_category AS subCategory, " +
            "COALESCE(SUM(amount), 0.0) AS total, COUNT(*) AS count FROM Bill " +
            "WHERE ((:income = 1 AND type = 'income') OR (:income = 0 AND type = 'expense')) " +
            "AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end " +
            "GROUP BY category, sub_category ORDER BY category ASC, total DESC"

    const val COUNT_SUB_CATEGORY_BILLS =
        "SELECT COUNT(*) FROM Bill WHERE type = :type AND category = :category " +
            "AND sub_category = :subCategory"

    const val MOVE_SUB_CATEGORY_BILLS =
        "UPDATE Bill SET sub_category = :target WHERE type = :type AND category = :category " +
            "AND sub_category = :source"

    /** 配对候选：只查真实账单、未合并、方向相反、金额精确到分且时间差不超过 5 分钟。 */
    const val TRANSFER_PAIR_CANDIDATES =
        "SELECT * FROM Bill WHERE is_recurring = 0 AND is_transfer = 0 " +
            "AND id != :entryId AND type = :oppositeType " +
            "AND ABS(CAST(ROUND(amount * 100) AS INTEGER)) = :amountCents " +
            "AND timestamp BETWEEN :since AND :until " +
            "ORDER BY ABS(timestamp - :centerTime) ASC, id ASC"

    const val CLAIM_TRANSFER_PAIR =
        "UPDATE Bill SET is_transfer = 1, transfer_pair_id = :pairId " +
            "WHERE id IN (:firstId, :secondId) AND is_transfer = 0"

    const val RELEASE_TRANSFER_PAIR =
        "UPDATE Bill SET is_transfer = 0, transfer_pair_id = NULL " +
            "WHERE transfer_pair_id = :pairId"

    const val CLEAR_DANGLING_TRANSFER_PAIRS =
        "UPDATE Bill SET is_transfer = 0, transfer_pair_id = NULL " +
            "WHERE transfer_pair_id IS NOT NULL " +
            "AND (SELECT COUNT(*) FROM Bill AS paired " +
            "WHERE paired.transfer_pair_id = Bill.transfer_pair_id) < 2"

    /** 备份恢复时，只认完整的一对同 pair、金额分相等、方向相反。 */
    const val RELEASE_INVALID_TRANSFER_PAIRS =
        "UPDATE Bill SET is_transfer = 0, transfer_pair_id = NULL " +
            "WHERE transfer_pair_id IS NOT NULL AND transfer_pair_id IN (" +
            "SELECT transfer_pair_id FROM Bill WHERE transfer_pair_id IS NOT NULL " +
            "GROUP BY transfer_pair_id HAVING COUNT(*) != 2 " +
            "OR SUM(CASE WHEN type = 'expense' THEN 1 ELSE 0 END) != 1 " +
            "OR SUM(CASE WHEN type = 'income' THEN 1 ELSE 0 END) != 1 " +
            "OR MIN(CAST(ROUND(amount * 100) AS INTEGER)) != " +
            "MAX(CAST(ROUND(amount * 100) AS INTEGER)))"

    /** 自动记账跨源合并候选：精确金额、同方向、时间窗最多 5 分钟。 */
    const val TIMED_MERGE_CANDIDATES =
        "SELECT * FROM Bill WHERE is_recurring = 0 AND is_transfer = 0 " +
            "AND CAST(ROUND(amount * 100) AS INTEGER) = :amountCents AND type = :type " +
            "AND timestamp BETWEEN :since AND :until ORDER BY timestamp ASC, id ASC"
}

/**
 * [LedgerDao.observeTotals] 的投影：报表顶部 4 个数，一条 SQL 出全。
 *
 * 列别名刻意避开 `current`（SQLite 保留字，CURRENT_DATE 系列）与 `income`
 * （与同名查询参数撞车），再用 @ColumnInfo 映射回语义化字段名。
 */
data class TotalsRow(
    @ColumnInfo(name = "cur_amount") val current: Double,
    @ColumnInfo(name = "prev_amount") val previous: Double,
    @ColumnInfo(name = "exp_amount") val expense: Double,
    @ColumnInfo(name = "inc_amount") val income: Double,
)

data class Summary(
    val todayExpense: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
)

/** 导入去重只需要这几列，不必把全表实体读进内存。 */
data class ImportRow(
    @ColumnInfo(name = "external_id") val externalId: String?,
    @ColumnInfo(name = "time_text") val time: String,
    val amount: Double,
    val type: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
)

@Dao
interface LedgerDao {
    // id DESC 是必要的：timestamp 只到毫秒，同一毫秒内的多条需要稳定次序，否则分页会漏行/重行。
    @Query("SELECT * FROM Bill WHERE is_recurring = 0 ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<LedgerEntry>>

    @Query("SELECT * FROM Bill WHERE is_recurring = 0 ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun observePaging(limit: Int, offset: Int): Flow<List<LedgerEntry>>

    /**
     * 游标分页：只按「比上一页最后一条更旧」取下一页，代价恒定，
     * 不像 LIMIT/OFFSET 那样每翻一页都要扫描并丢弃前面所有行。
     */
    @Query(
        "SELECT * FROM Bill WHERE is_recurring = 0 AND (timestamp < :cursorTime OR (timestamp = :cursorTime AND id < :cursorId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun pageBefore(cursorTime: Long, cursorId: Long, limit: Int): List<LedgerEntry>

    @Query("SELECT COUNT(*) FROM Bill WHERE is_recurring = 0")
    fun observeCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type = 'expense' AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end",
    )
    fun observeExpense(start: Long, end: Long): Flow<Double>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type == 'income' AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end",
    )
    fun observeIncome(start: Long, end: Long): Flow<Double>

    /**
     * 报表顶部 4 个数一次取回：本期、上期、本期支出、本期收入。
     *
     * 4 个值走同一条 SQL，避免多次独立 Room invalidation，
     * 任意一次写库都会让 4 个 Flow 各重跑一次。这里压成 1 条：
     * 外层 WHERE 用 MIN/MAX 框住两个区间的并集，让 SQLite 能走 (is_recurring, timestamp) 索引，
     * 再在 SELECT 里用 CASE WHEN 分流到 4 个桶 —— 一次索引扫描出全部结果。
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN timestamp >= :cs AND timestamp < :ce " +
            "AND ((:income = 1 AND type = 'income') OR (:income = 0 AND type = 'expense')) " +
            "THEN amount END), 0.0) AS cur_amount, " +
            "COALESCE(SUM(CASE WHEN timestamp >= :ps AND timestamp < :pe " +
            "AND ((:income = 1 AND type = 'income') OR (:income = 0 AND type = 'expense')) " +
            "THEN amount END), 0.0) AS prev_amount, " +
            "COALESCE(SUM(CASE WHEN timestamp >= :cs AND timestamp < :ce AND type = 'expense' " +
            "THEN amount END), 0.0) AS exp_amount, " +
            "COALESCE(SUM(CASE WHEN timestamp >= :cs AND timestamp < :ce AND type = 'income' " +
            "THEN amount END), 0.0) AS inc_amount " +
            "FROM Bill WHERE is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :outerStart AND timestamp < :outerEnd",
    )
    fun observeTotals(
        cs: Long, ce: Long,
        ps: Long, pe: Long,
        outerStart: Long, outerEnd: Long,
        income: Int,
    ): Flow<TotalsRow>

    @Query(
        "SELECT strftime(:format, timestamp / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "COALESCE(SUM(amount), 0.0) AS total FROM Bill " +
            "WHERE ((:income = 1 AND type = 'income') OR (:income = 0 AND type = 'expense')) " +
            "AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end GROUP BY bucket ORDER BY bucket",
    )
    fun observeBuckets(format: String, start: Long, end: Long, income: Int): Flow<List<Bucket>>

    @Query(LedgerDaoSql.DUAL_CATEGORY_AGGREGATION)
    fun observeCategories(start: Long, end: Long, income: Int): Flow<List<CategoryAgg>>

    /** 删除子分类前统计引用数；周期模板虽不出现在账目中，也会影响未来自动入账。 */
    @Query(LedgerDaoSql.COUNT_SUB_CATEGORY_BILLS)
    suspend fun countSubCategoryBills(type: String, category: String, subCategory: String): Int

    /** 重命名或迁移子分类。周期模板也同步更新，避免下期自动入账写回旧名称。 */
    @Query(LedgerDaoSql.MOVE_SUB_CATEGORY_BILLS)
    suspend fun moveSubCategoryBills(type: String, category: String, source: String, target: String?): Int

    // ---- 一次性聚合：给桌面小部件与提醒用，避免把全表读进内存再 filter/sum ----

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type = 'expense' AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun sumExpenseOnce(start: Long, end: Long): Double

    /** 小部件专用：一次查询近 7 日支出，按本地日期分组。 */
    @Query(
        "SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day, " +
            "COALESCE(SUM(amount), 0.0) AS total FROM Bill WHERE type = 'expense' " +
            "AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end GROUP BY day ORDER BY day",
    )
    suspend fun sumExpenseByDayOnce(start: Long, end: Long): List<DayAmount>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type == 'income' AND is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun sumIncomeOnce(start: Long, end: Long): Double

    @Query(
        "SELECT COUNT(*) FROM Bill WHERE is_recurring = 0 AND is_transfer = 0 " +
            "AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun countRange(start: Long, end: Long): Int

    /** 全表（含周期模板）：仅供备份使用，恢复时模板要一起带走。 */
    @Query("SELECT * FROM Bill ORDER BY timestamp DESC, id DESC")
    suspend fun getAllOnce(): List<LedgerEntry>

    /** 只取真实账单（排除周期模板）：供 CSV/XLSX 导出，模板不是交易。 */
    @Query("SELECT * FROM Bill WHERE is_recurring = 0 ORDER BY timestamp DESC, id DESC")
    suspend fun getAllOnceReal(): List<LedgerEntry>

    /**
     * 组合筛选。
     *
     * 所有条件都写成「参数为空即不参与过滤」，这样界面只要把未选中的项传空值，
     * 不用拼 SQL（拼字符串既慢又容易被备注里的引号搞崩）。
     * 金额下限传 0 而不是 -1，因为 0 元账单本身没有意义。
     */
    @Query(
        """
        SELECT * FROM Bill
        WHERE is_recurring = 0
          AND amount BETWEEN :minAmount AND :maxAmount
          AND timestamp >= :start AND timestamp < :end
          AND (:type = '' OR type = :type)
          AND (:categoryMode = 0 OR category IN (:categories))
          AND (
            :keyword = ''
            OR remark LIKE '%' || :keyword || '%'
            OR category LIKE '%' || :keyword || '%'
            OR source LIKE '%' || :keyword || '%'
          )
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    fun search(
        minAmount: Double,
        maxAmount: Double,
        start: Long,
        end: Long,
        type: String,
        categories: List<String>,
        categoryMode: Int,
        keyword: String,
        limit: Int,
    ): Flow<List<LedgerEntry>>

    /**
     * 与 [search] 同条件的一次性命中数，给筛选面板实时显示「应用 (N 条)」。
     *
     * Room 对空 `List` 会生成 `IN ()`，SQLite 直接语法错误，所以调用方必须在
     * 不筛选时传 `categoryMode = 0` 且 `categories = listOf("")` 占位。
     */
    @Query(
        """
        SELECT COUNT(*) FROM Bill
        WHERE is_recurring = 0
          AND amount BETWEEN :minAmount AND :maxAmount
          AND timestamp >= :start AND timestamp < :end
          AND (:type = '' OR type = :type)
          AND (:categoryMode = 0 OR category IN (:categories))
          AND (
            :keyword = ''
            OR remark LIKE '%' || :keyword || '%'
            OR category LIKE '%' || :keyword || '%'
            OR source LIKE '%' || :keyword || '%'
          )
        """,
    )
    suspend fun countSearch(
        minAmount: Double,
        maxAmount: Double,
        start: Long,
        end: Long,
        type: String,
        categories: List<String>,
        categoryMode: Int,
        keyword: String,
    ): Int

    @Query("SELECT external_id, time_text, amount, type, timestamp FROM Bill")
    suspend fun importRows(): List<ImportRow>

    /**
     * 导入查重的候选集：同收支、金额在 ±0.005 内、时间落在窗口里的实账。
     *
     * 只回候选、判定留给 [com.eta.autoledger.data.ImportDedup] —— 商户名相似度、
     * 备注谁更详细这些判断 SQL 表达不了，塞进 SQL 只会变成一条谁都不敢改的巨型 CASE。
     */
    @Query(
        "SELECT * FROM Bill WHERE is_recurring = 0 AND type = :type " +
            "AND amount BETWEEN :amountLow AND :amountHigh " +
            "AND timestamp BETWEEN :since AND :until ORDER BY timestamp ASC",
    )
    suspend fun nearbySameAmount(
        type: String,
        amountLow: Double,
        amountHigh: Double,
        since: Long,
        until: Long,
    ): List<LedgerEntry>

    @Query(LedgerDaoSql.TIMED_MERGE_CANDIDATES)
    suspend fun timedMergeCandidates(
        amountCents: Long,
        type: String,
        since: Long,
        until: Long,
    ): List<LedgerEntry>

    /**
     * IGNORE 而不是 REPLACE：主键交给 SQLite 自增后，撞键只可能是 external_id 唯一索引，
     * 那时也不能静默替换掉已有账单——宁可丢掉这条重复，也不该让用户的旧账消失。
     * 返回每个元素的 rowId，被忽略的位置是 -1。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<LedgerEntry>): List<Long>

    @Update
    suspend fun update(entry: LedgerEntry)

    @Query("SELECT * FROM Bill WHERE timestamp = 0")
    suspend fun getMissingTimestamp(): List<LedgerEntry>

    @Query(LedgerDaoSql.TRANSFER_PAIR_CANDIDATES)
    suspend fun transferPairCandidates(
        entryId: Long,
        oppositeType: String,
        amountCents: Long,
        since: Long,
        until: Long,
        centerTime: Long,
    ): List<LedgerEntry>

    @Query("SELECT * FROM Bill WHERE id = :id")
    suspend fun getById(id: Long): LedgerEntry?

    @Query("SELECT * FROM Bill WHERE transfer_pair_id = :pairId ORDER BY id ASC")
    suspend fun getTransferPair(pairId: String): List<LedgerEntry>

    @Query(LedgerDaoSql.CLAIM_TRANSFER_PAIR)
    suspend fun claimTransferPair(pairId: String, firstId: Long, secondId: Long): Int

    @Query(LedgerDaoSql.RELEASE_TRANSFER_PAIR)
    suspend fun releaseTransferPair(pairId: String): Int

    /** 撤销删除恢复原配对时，仅当另一半仍存在才重新认领。 */
    @Transaction
    suspend fun restoreTransferPairAtomically(entry: LedgerEntry): Boolean {
        val pairId = entry.transferPairId ?: return false
        val existing = getTransferPair(pairId)
        val restored = entry.copy(isTransfer = false, transferPairId = null)
        val other = if (existing.size == 1) {
            existing.single()
        } else if (existing.isEmpty()) {
            val stamp = restored.timeMillis.takeIf { it > 0L } ?: return false
            val candidates = transferPairCandidates(
                entryId = restored.id,
                oppositeType = if (restored.type == "income") "expense" else "income",
                amountCents = kotlin.math.abs(FingerprintCodec.amountFen(restored.amount)),
                since = stamp - TransferPairMatcher.WINDOW_MS,
                until = stamp + TransferPairMatcher.WINDOW_MS,
                centerTime = stamp,
            )
            TransferPairMatcher.chooseCandidate(restored, candidates) ?: return false
        } else {
            return false
        }
        if (!TransferPairMatcher.matches(restored, other)) return false
        return claimTransferPair(pairId, entry.id, other.id) == 2
    }

    /** 清理已被单边删除的转账标记，保证不会长期隐藏幸存那一笔。 */
    @Query(LedgerDaoSql.CLEAR_DANGLING_TRANSFER_PAIRS)
    suspend fun clearDanglingTransferPairs(): Int

    /** 只用于删除撤销快照：先记录配对，再释放，最后删单边。 */
    @Transaction
    suspend fun deleteEntryPreservingTransferSnapshot(id: Long): LedgerEntry? {
        val entry = getById(id) ?: return null
        entry.transferPairId?.let { releaseTransferPair(it) }
        deleteById(id)
        return entry
    }

    @Query(LedgerDaoSql.RELEASE_INVALID_TRANSFER_PAIRS)
    suspend fun releaseInvalidTransferPairs(): Int

    @Transaction
    suspend fun claimTransferPairAtomically(pairId: String, firstId: Long, secondId: Long): Boolean {
        val first = getById(firstId) ?: return false
        val second = getById(secondId) ?: return false
        if (first.isTransfer || second.isTransfer || first.isRecurring || second.isRecurring) return false
        return claimTransferPair(pairId, firstId, secondId) == 2
    }

    @Transaction
    suspend fun releaseTransferPairAtomically(pairId: String): Int = releaseTransferPair(pairId)

    @Query("UPDATE Bill SET timestamp = :millis WHERE id = :id")
    suspend fun setTimestamp(id: Long, millis: Long)

    @Query("DELETE FROM Bill WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM Bill WHERE external_id = :externalId")
    suspend fun deleteByExternalId(externalId: String): Int

    @Query("DELETE FROM Bill")
    suspend fun clear()

    // ── 周期记账 ────────────────────────────────────────────────────────

    /** 所有模板（isRecurring = true）。模板数量是个位数，全表扫可接受。 */
    @Query("SELECT * FROM Bill WHERE is_recurring = 1 ORDER BY id ASC")
    suspend fun recurringTemplates(): List<LedgerEntry>

}
