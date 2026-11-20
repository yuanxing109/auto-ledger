package com.eta.autoledger.data

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

@Dao
interface LedgerDao {
    @Query("SELECT * FROM Bill ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<LedgerEntry>>

    @Query("SELECT * FROM Bill ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun observePaging(limit: Int, offset: Int): Flow<List<LedgerEntry>>

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

    @Query("SELECT * FROM Bill ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<LedgerEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<LedgerEntry>)

    @Update
    suspend fun update(entry: LedgerEntry)

    @Query("SELECT * FROM Bill WHERE timestamp = 0")
    suspend fun getMissingTimestamp(): List<LedgerEntry>

    @Query("UPDATE Bill SET timestamp = :millis WHERE id = :id")
    suspend fun setTimestamp(id: Long, millis: Long)

    @Query("DELETE FROM Bill WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM Bill WHERE external_id = :externalId")
    suspend fun deleteByExternalId(externalId: String)

    @Query("DELETE FROM Bill")
    suspend fun clear()
}