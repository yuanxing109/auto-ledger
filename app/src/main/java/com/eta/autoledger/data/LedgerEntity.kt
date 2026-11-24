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

    /**
     * 周期账单模板标记。
     *
     * 模板记录本身也会入库（否则用户看不到自己设了什么），
     * 但报表/首页汇总必须排除它们——所以 [RecurringWorker] 生成的实账
     * 走 [recurringRule] = null 的普通记录，模板永远不参与统计。
     */
    @ColumnInfo(name = "is_recurring", defaultValue = "0")
    val isRecurring: Boolean = false,

    /**
     * 重复规则，格式 "WEEKLY_1" / "MONTHLY_5" / "YEARLY_3-15"。
     *
     * null 表示这不是模板。模板行保留规则用于下次匹配；
     * Worker 生成的实账把规则写回同一字段的 [recurringFrom] 溯源字段太绕，
     * 这里简化：实账不写规则（null），只写 note 前缀与 source = "recurring"。
     */
    @ColumnInfo(name = "recurring_rule")
    val recurringRule: String? = null,

    /**
     * 子分类。主分类继续由 [category] 保存。
     *
     * 历史账单没有这层信息，迁移后保持 NULL。报表在无子分类数据时展示明确空态，
     * 不从商户名或备注自动推导，避免产生不可审计的分类结果。
     */
    @ColumnInfo(name = "sub_category")
    val subCategory: String? = null,
)
