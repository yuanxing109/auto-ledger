package com.eta.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "Bill",
    indices = [Index(value = ["timestamp"]), Index(value = ["external_id"], unique = true)],
)
data class LedgerEntry(
    /**
     * 主键由 SQLite 自增分配，调用方一律传 0。
     * 之前用 `System.currentTimeMillis()` 当主键，同一毫秒内的两条账单（连点保存、
     * 批量导入同一时间戳）会撞键，配合 REPLACE 就是静默覆盖旧账。
     */
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val amount: Double,
    val type: String,
    val category: String,
    @ColumnInfo(name = "time_text") val time: String,
    @ColumnInfo(name = "timestamp") val timeMillis: Long = 0L,
    @ColumnInfo(name = "remark") val note: String,
    val source: String,
    @ColumnInfo(name = "external_id") val externalId: String? = null,
)
