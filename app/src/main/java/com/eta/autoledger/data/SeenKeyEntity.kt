package com.eta.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 已处理过的去重键，落库版本。
 *
 * 为什么内存里的 [ParseDedup] 还不够：
 * 进程被杀、系统清理后台、用户手动划掉 App —— 内存里的 `recent`/`bursts` 全部清空。
 * 此时若有一条通知的重投/重放（银行 App 重新拉取、系统补发、用户重进微信让通知重新发出），
 * 就会当成新的一笔再记一次。内存去重只挡得住「同一个进程生命周期内」的重复，
 * 挡不住「跨进程」的重复。
 *
 * 为什么不用 Room 的复合主键 + 自动时间戳：
 * 这里就是一张「字符串 → 时间」的简单表。主键是键本身，重复写入用 REPLACE 刷新时间，
 * 于是它天然表达「这个键最近一次出现是什么时候」。
 *
 * 为什么保留 `at` 索引：
 * 清理语句是 `DELETE FROM seen_key WHERE at < ?`，没有索引每次心跳就是一次全表扫。
 * 表的稳态大小是「10 分钟内的去重键数」，通常个位数到几十，但索引成本几乎为零，
 * 而全表扫在极端情况（用户手机上一天几千条通知）会变成可感知的耗时。
 */
@Entity(
    tableName = "seen_key",
    indices = [Index(value = ["at"])],
)
data class SeenKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    /** 该键最后一次出现的时间（毫秒），清理时按它判过期。 */
    @ColumnInfo(name = "at")
    val at: Long,
)
