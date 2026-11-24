package com.eta.autoledger.data

/**
 * Room 增量迁移使用的纯 SQL，集中在一处。
 *
 * 为什么单独抽成对象而不是直接写在 Migration 里：
 * `Migration.migrate` 的参数是 `SupportSQLiteDatabase`，这是 Android 类型，
 * 纯 JVM 单测里拿不到。把 DDL 抽成纯字符串列表后，单测可以直接用 sqlite-jdbc
 * 在内存库里跑同一份语句，验证「建表 SQL 合法 + 不碰 Bill 表 + 老数据仍可读」。
 * 迁移代码本身仍只在这一份语句上执行，不存在「测试跑的和线上跑的不是同一份」的问题。
 *
 * 语句必须与 Room 期望的 schema 严格一致，否则 Room 在迁移后的
 * `onValidateSchema` 会抛 `IllegalStateException: Migration didn't properly handle`。
 * 具体约束：
 *  - 表名、列名、列顺序、类型、NOT NULL 必须和实体声明逐字对应；
 *  - 索引名必须是 Room 的自动命名规则 `index_<表>_<列1>_<列2>…`，自己起名会对不上；
 *  - 布尔字段在 SQLite 里就是 INTEGER，不能写 BOOLEAN。
 */
internal object LedgerSchema {

    /** 去重键的存活时长：10 分钟。见 [LedgerDatabase.pruneSeenKeys]。 */
    const val SEEN_KEY_TTL_MS = 10 * 60 * 1000L

    /** v4 → v5：追加可空子分类列。历史行保持 NULL，不做推断回填。 */
    const val V5_ADD_SUB_CATEGORY = "ALTER TABLE Bill ADD COLUMN sub_category TEXT"

    /**
     * v4 新增的两张表。
     *
     * 全部用 `IF NOT EXISTS`：迁移在极端情况下可能被重入（用户清数据后回滚、
     * 备份还原后重复升级），幂等能让第二次执行变成空操作而不是崩在
     * 「table already exists」。
     */
    val V4_CREATE_STATEMENTS: List<String> = listOf(
        // ── seen_key：跨进程重启的去重兜底 ────────────────────────────────
        """
        CREATE TABLE IF NOT EXISTS `seen_key` (
            `key` TEXT NOT NULL,
            `at` INTEGER NOT NULL,
            PRIMARY KEY(`key`)
        )
        """.trimIndent(),
        // 清理语句是 DELETE ... WHERE at < ?，没有这个索引就是每次心跳一次全表扫
        "CREATE INDEX IF NOT EXISTS `index_seen_key_at` ON `seen_key` (`at`)",

        // ── rule：解析规则（本轮只建表，内核未切换）────────────────────────
        """
        CREATE TABLE IF NOT EXISTS `rule` (
            `id` TEXT NOT NULL,
            `app` TEXT,
            `type` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `creator` TEXT NOT NULL,
            `pattern_json` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `auto_record` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        // 内核取规则的主路径：type + app + enabled
        "CREATE INDEX IF NOT EXISTS `index_rule_type_app_enabled` ON `rule` (`type`, `app`, `enabled`)",
        // 播种 / 升级 system 规则时的扫描路径：creator + enabled
        "CREATE INDEX IF NOT EXISTS `index_rule_creator_enabled` ON `rule` (`creator`, `enabled`)",
    )
}
