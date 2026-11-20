package com.eta.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class Bucket(val bucket: String, val total: Double)

data class CategoryAgg(val name: String, val total: Double, val count: Int)

data class Summary(
    val todayExpense: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
)

/** 导入去重只需要这四列，不必把全表实体读进内存。 */
data class ImportRow(
    @ColumnInfo(name = "external_id") val externalId: String?,
    @ColumnInfo(name = "time_text") val time: String,
    val amount: Double,
    val type: String,
)

@Dao
interface LedgerDao {
    // id DESC 是必要的：timestamp 只到毫秒，同一毫秒内的多条需要稳定次序，否则分页会漏行/重行。
    @Query("SELECT * FROM Bill ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<LedgerEntry>>

    @Query("SELECT * FROM Bill ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun observePaging(limit: Int, offset: Int): Flow<List<LedgerEntry>>

    /**
     * 游标分页：只按「比上一页最后一条更旧」取下一页，代价恒定，
     * 不像 LIMIT/OFFSET 那样每翻一页都要扫描并丢弃前面所有行。
     */
    @Query(
        "SELECT * FROM Bill WHERE timestamp < :cursorTime OR (timestamp = :cursorTime AND id < :cursorId) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun pageBefore(cursorTime: Long, cursorId: Long, limit: Int): List<LedgerEntry>

    @Query("SELECT COUNT(*) FROM Bill")
    fun observeCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type != 'income' AND timestamp >= :start AND timestamp < :end",
    )
    fun observeExpense(start: Long, end: Long): Flow<Double>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type == 'income' AND timestamp >= :start AND timestamp < :end",
    )
    fun observeIncome(start: Long, end: Long): Flow<Double>

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE ((:income = 1 AND type = 'income') OR (:income = 0 AND type != 'income')) " +
            "AND timestamp >= :start AND timestamp < :end",
    )
    fun observeTotal(start: Long, end: Long, income: Int): Flow<Double>

    @Query(
        "SELECT strftime(:format, timestamp / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "COALESCE(SUM(amount), 0.0) AS total FROM Bill " +
            "WHERE ((:income = 1 AND type = 'income') OR (:income = 0 AND type != 'income')) " +
            "AND timestamp >= :start AND timestamp < :end GROUP BY bucket ORDER BY bucket",
    )
    fun observeBuckets(format: String, start: Long, end: Long, income: Int): Flow<List<Bucket>>

    @Query(
        "SELECT category AS name, COALESCE(SUM(amount), 0.0) AS total, COUNT(*) AS count FROM Bill " +
            "WHERE ((:income = 1 AND type = 'income') OR (:income = 0 AND type != 'income')) " +
            "AND timestamp >= :start AND timestamp < :end GROUP BY category ORDER BY total DESC",
    )
    fun observeCategories(start: Long, end: Long, income: Int): Flow<List<CategoryAgg>>

    // ---- 一次性聚合：给桌面小部件与提醒用，避免把全表读进内存再 filter/sum ----

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type != 'income' AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun sumExpenseOnce(start: Long, end: Long): Double

    @Query(
        "SELECT COALESCE(SUM(amount), 0.0) FROM Bill " +
            "WHERE type == 'income' AND timestamp >= :start AND timestamp < :end",
    )
    suspend fun sumIncomeOnce(start: Long, end: Long): Double

    @Query("SELECT COUNT(*) FROM Bill WHERE timestamp >= :start AND timestamp < :end")
    suspend fun countRange(start: Long, end: Long): Int

    @Query("SELECT * FROM Bill ORDER BY timestamp DESC, id DESC")
    suspend fun getAllOnce(): List<LedgerEntry>

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
        WHERE amount BETWEEN :minAmount AND :maxAmount
          AND timestamp >= :start AND timestamp < :end
          AND (:type = '' OR type = :type)
          AND (:category = '' OR category = :category)
          AND (
            :keyword = ''
            OR note LIKE '%' || :keyword || '%'
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
        category: String,
        keyword: String,
        limit: Int,
    ): Flow<List<LedgerEntry>>

    @Query("SELECT external_id, time_text, amount, type FROM Bill")
    suspend fun importRows(): List<ImportRow>

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
}