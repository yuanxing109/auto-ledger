package com.eta.autoledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * [SeenKeyEntity] 的访问接口。
 *
 * 全部是 suspend：调用方是通知监听（已经在 IO 协程里）与心跳（`JobService` 的后台线程），
 * 没有一处需要同步阻塞地读它。用 suspend 能让 Room 自己把执行放到事务线程池，
 * 调用方不必再包一层 `withContext(Dispatchers.IO)`。
 */
@Dao
interface SeenKeyDao {

    /**
     * 记下一个已处理的键。
     *
     * REPLACE 语义在这里是刻意的：同一个键重复写入就是「它又出现了一次」，
     * 需要把时间刷新到最新，否则一条持续被重投的通知会在写入时就被判成过期。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SeenKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(keys: List<SeenKeyEntity>)

    /** 是否见过。比 `get()` 便宜：只取 1 行且不构造实体。 */
    @Query("SELECT EXISTS(SELECT 1 FROM seen_key WHERE `key` = :key)")
    suspend fun contains(key: String): Boolean

    /**
     * 清理过期键，返回删除行数。
     *
     * 参数是「早于这个时间就删」，由调用方算 `now - TTL` 传进来 ——
     * 而不是在 SQL 里写 `at < strftime(...)`。原因：SQLite 的时间函数拿的是设备本地时区，
     * 用户改时区/改系统时间会让清理行为飘；由 Kotlin 侧算好绝对毫秒再传，行为可预测，
     * 也方便单测注入固定 `now`。
     */
    @Query("DELETE FROM seen_key WHERE at < :before")
    suspend fun deleteExpired(before: Long): Int

    @Query("SELECT COUNT(*) FROM seen_key")
    suspend fun count(): Int

    /** 只在测试与「清空数据」时用。 */
    @Query("DELETE FROM seen_key")
    suspend fun clear()
}
