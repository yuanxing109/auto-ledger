package com.eta.autoledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * [RuleEntity] 的访问接口。
 *
 * 这一版只有存取，没有「按规则解析」的方法 —— 解析内核仍然是 [NotificationRules]。
 * 等内核切换（D2）时再在这里加 `match(type, app)` 之类的查询，届时改的只是 DAO 与内核，
 * 表结构不用再动一次。
 */
@Dao
interface RuleDao {

    /**
     * 全量规则，按 creator 再按 type 排。
     *
     * 排序不是装饰：规则是有优先级的（先匹配到的先算），
     * 排序放在 SQL 里而不是调用方 sort，能让「取出来的顺序」和「匹配顺序」永远一致，
     * 避免两处各写一遍排序逻辑然后悄悄分叉。
     */
    @Query("SELECT * FROM rule ORDER BY creator DESC, type ASC, name ASC")
    suspend fun getAll(): List<RuleEntity>

    /**
     * 按 creator 取启用中的规则。
     *
     * `creator DESC` 让 "user" 排在 "system" 前面（u > s），也就是用户规则优先于内置规则 ——
     * 这正是「用户能覆盖内置行为」的实现方式，不需要额外的优先级字段。
     */
    @Query("SELECT * FROM rule WHERE creator = :creator AND enabled = 1 ORDER BY type ASC, name ASC")
    suspend fun getEnabledByCreator(creator: String): List<RuleEntity>

    /** 内核用：某类型 + 某包名（含「任意包名」的 null 规则）下启用的规则。 */
    @Query(
        "SELECT * FROM rule WHERE enabled = 1 AND type = :type AND (app IS NULL OR app = :app) " +
            "ORDER BY creator DESC, name ASC",
    )
    suspend fun getEnabledFor(type: String, app: String): List<RuleEntity>

    @Query("SELECT * FROM rule WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RuleEntity?

    /**
     * 写入或覆盖。
     *
     * 用户规则走 REPLACE：同一个 id 就是同一条规则，编辑即覆盖。
     * 内置规则的播种走 [insertIgnore]，两者不能混用 —— 详见 [RuleSeed] 的注释。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RuleEntity>)

    /**
     * 播种专用：已存在就跳过，绝不覆盖。
     *
     * 为什么内置规则不能用 REPLACE：用户可能把某条内置规则关掉（enabled = false），
     * 或者改过它的 JSON。REPLACE 会在每次升级时把这些改动抹掉，
     * 表现为「我明明关了这条规则，更新后又自己开了」。IGNORE 让用户的改动永远赢。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rules: List<RuleEntity>): List<Long>

    /**
     * 内置规则的版本升级：只覆盖 system 规则，且只在这些 id 明确属于 system 时。
     *
     * 目前没有调用方，留给「内置规则修正版」随版本下发用。
     */
    @Query(
        "UPDATE rule SET pattern_json = :patternJson, name = :name, updated_at = :updatedAt " +
            "WHERE id = :id AND creator = 'system'",
    )
    suspend fun updateSystemRule(id: String, name: String, patternJson: String, updatedAt: Long): Int

    @Query("DELETE FROM rule WHERE id = :id")
    suspend fun delete(id: String)

    /** 只允许删用户规则；内置规则不可删。返回删除行数。 */
    @Query("DELETE FROM rule WHERE id = :id AND creator = 'user'")
    suspend fun deleteUserRule(id: String): Int

    @Query("SELECT COUNT(*) FROM rule")
    suspend fun count(): Int
}
