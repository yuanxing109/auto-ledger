package com.eta.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
            "AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end " +
            "GROUP BY category, sub_category ORDER BY category ASC, total DESC"
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
            "WHERE type = 'expense' AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end",
    )
    fun observeExpense(start: Long, end: Long): Flow<Double>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type == 'income' AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end",
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
            "FROM Bill WHERE is_recurring = 0 " +
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
            "AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end GROUP BY bucket ORDER BY bucket",
    )
    fun observeBuckets(format: String, start: Long, end: Long, income: Int): Flow<List<Bucket>>

    @Query(LedgerDaoSql.DUAL_CATEGORY_AGGREGATION)
    fun observeCategories(start: Long, end: Long, income: Int): Flow<List<CategoryAgg>>

    // ---- 一次性聚合：给桌面小部件与提醒用，避免把全表读进内存再 filter/sum ----

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type = 'expense' AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun sumExpenseOnce(start: Long, end: Long): Double

    /** 小部件专用：一次查询近 7 日支出，按本地日期分组。 */
    @Query(
        "SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day, " +
            "COALESCE(SUM(amount), 0.0) AS total FROM Bill WHERE type = 'expense' " +
            "AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end GROUP BY day ORDER BY day",
    )
    suspend fun sumExpenseByDayOnce(start: Long, end: Long): List<DayAmount>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type == 'income' AND is_recurring = 0 AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun sumIncomeOnce(start: Long, end: Long): Double

    @Query("SELECT COUNT(*) FROM Bill WHERE is_recurring = 0 AND timestamp >= :start AND timestamp < :end")
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

    /** 自动记账强指纹的候选集：精确金额、方向、发生时间 ±5 分钟。 */
    @Query(
        "SELECT * FROM Bill WHERE is_recurring = 0 " +
            "AND CAST(ROUND(amount * 100) AS INTEGER) = :amountCents AND type = :type " +
            "AND timestamp BETWEEN :since AND :until ORDER BY timestamp ASC, id ASC",
    )
    suspend fun strongMergeCandidates(
        amountCents: Long,
        type: String,
        since: Long,
        until: Long,
    ): List<LedgerEntry>

    /** 只更新来源标签，供跨渠道强指纹命中后合并来源。 */
    @Query("UPDATE Bill SET source = :source WHERE id = :id")
    suspend fun updateSource(id: Long, source: String): Int

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
