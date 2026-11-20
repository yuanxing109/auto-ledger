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
    @PrimaryKey val id: Long,
    val amount: Double,
    val type: String,
    val category: String,
    @ColumnInfo(name = "time_text") val time: String,
    @ColumnInfo(name = "timestamp") val timeMillis: Long = 0L,
    @ColumnInfo(name = "remark") val note: String,
    val source: String,
    @ColumnInfo(name = "external_id") val externalId: String? = null,
)
