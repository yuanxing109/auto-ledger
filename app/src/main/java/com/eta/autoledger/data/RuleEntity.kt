package com.eta.autoledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rule",
    indices = [
        Index(value = ["type", "app", "enabled"]),
        Index(value = ["creator", "enabled"]),
    ],
)
data class RuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "app")
    val app: String?,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "creator")
    val creator: String,
    @ColumnInfo(name = "pattern_json")
    val patternJson: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "auto_record")
    val autoRecord: Boolean,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val CREATOR_SYSTEM = "system"
        const val CREATOR_USER = "user"

        fun makeId(type: String, app: String?, name: String): String =
            "$type/${app ?: "*"}/$name"
    }
}

/**
 * 规则类型。
 *
 * 用 ASCII 小写而不是「通知 / SMS / OCR」这种混排，原因有三：
 *  1. 类型会进主键（`type/app/name`）、进 SQL 的 WHERE、进备份 JSON —— 中文在这三处
 *     都要处理编码，而 ASCII 永远安全；
 *  2. 用户改规则名字（`name`）时 `id` 会跟着变，但 `type` 不会，ASCII 让「改名不换类型」
 *     这件事在 diff 里一眼可见；
 *  3. 与 AA（AutoAccounting）的 `DataType` 一一对应，将来要导入它的规则库时不需要翻译表。
 */
object RuleType {
    /** App 通知（含短信被系统短信 App 转成通知的情况）。 */
    const val NOTICE = "notice"

    /** 短信广播。 */
    const val SMS = "sms"

    /** 截图 OCR。 */
    const val OCR = "ocr"
}
