package com.eta.autoledger.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v3 → v4 迁移测试。
 *
 * 为什么用 sqlite-jdbc 而不是 Room 的 MigrationTestHelper：
 * 后者依赖 `androidx.room:room-testing` + instrumentation（或 Robolectric），
 * 本项目是纯 JVM 单测（`testOptions.unitTests.isReturnDefaultValues = true`），
 * 没有也不打算引入 Robolectric。所以退一步：**迁移的 DDL 本身就是纯 SQL**，
 * 抽到 [LedgerSchema.V4_CREATE_STATEMENTS] 之后，用真 SQLite 跑同一份语句，
 * 验证的正是线上 `MIGRATION_3_4` 执行的那几行。
 *
 * 这个测试覆盖的是迁移里最容易出错的三件事：
 *  1. DDL 语法/幂等（IF NOT EXISTS 能否重复执行）；
 *  2. **不动 Bill 表**（老数据仍在、老列仍可读）；
 *  3. 新表能按 DAO 里的 SQL 查（列名、索引名与 Room 的期望一致）。
 *
 * 它**不能**覆盖的是 Room 自己的 `onValidateSchema`（校验列顺序/类型是否与实体完全一致）。
 * 那一步只有真机启动或 instrumentation 才能跑到，所以 DDL 必须逐字对照实体声明写。
 */
class LedgerMigrationV4Test {

    private lateinit var db: Connection

    /** Room v3 生成的实际 schema（照 LedgerEntry 的实体声明逐字还原）。 */
    private val v3Schema = listOf(
        """
        CREATE TABLE IF NOT EXISTS `Bill` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `amount` REAL NOT NULL,
            `type` TEXT NOT NULL,
            `category` TEXT NOT NULL,
            `time_text` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `remark` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `external_id` TEXT,
            `is_recurring` INTEGER NOT NULL DEFAULT 0,
            `recurring_rule` TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_Bill_timestamp` ON `Bill` (`timestamp`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_Bill_external_id` ON `Bill` (`external_id`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
    )

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        v3Schema.forEach { db.createStatement().use { st -> st.execute(it) } }
        // 写入两条 v3 时代的账单，模拟「升级前用户已有数据」
        db.createStatement().use { st ->
            st.execute(
                "INSERT INTO Bill (amount,type,category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                    "VALUES (46.94,'expense','餐饮','11-20 15:11',1700464260000,'午餐','微信通知','wx-abc',0,NULL)",
            )
            st.execute(
                "INSERT INTO Bill (amount,type,category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                    "VALUES (5000.0,'income','薪资','11-10 09:00',1699587600000,'发薪','手动',NULL,1,'MONTHLY_10')",
            )
        }
    }

    @Test
    fun `迁移后老账单仍然可读且字段完整`() {
        applyMigration()

        db.createStatement().use { st ->
            st.executeQuery("SELECT amount,type,category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule FROM Bill ORDER BY id").use { rs ->
                assertTrue("第一条账单丢了", rs.next())
                assertEquals(46.94, rs.getDouble("amount"), 0.001)
                assertEquals("expense", rs.getString("type"))
                assertEquals("餐饮", rs.getString("category"))
                assertEquals("11-20 15:11", rs.getString("time_text"))
                assertEquals(1700464260000L, rs.getLong("timestamp"))
                assertEquals("午餐", rs.getString("remark"))
                assertEquals("微信通知", rs.getString("source"))
                assertEquals("wx-abc", rs.getString("external_id"))
                assertEquals(0, rs.getInt("is_recurring"))

                assertTrue("第二条账单丢了", rs.next())
                assertEquals(5000.0, rs.getDouble("amount"), 0.001)
                assertEquals("income", rs.getString("type"))
                assertNull("非周期账单的 external_id 应为 NULL", rs.getString("external_id"))
                assertEquals(1, rs.getInt("is_recurring"))
                assertEquals("MONTHLY_10", rs.getString("recurring_rule"))

                assertTrue("不该有第三条", !rs.next())
            }
        }
    }

    @Test
    fun `迁移不改变 Bill 的列结构`() {
        val before = columnsOf("Bill")
        applyMigration()
        val after = columnsOf("Bill")
        assertEquals("迁移改动了 Bill 的列，这是不允许的", before, after)
    }

    @Test
    fun `迁移建出 seen_key 与 rule 两张表`() {
        applyMigration()
        val tables = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                while (rs.next()) tables += rs.getString(1)
            }
        }
        assertTrue("缺 seen_key 表", "seen_key" in tables)
        assertTrue("缺 rule 表", "rule" in tables)
        assertTrue("Bill 表不应消失", "Bill" in tables)
    }

    @Test
    fun `迁移建的索引名与 Room 的命名规则一致`() {
        applyMigration()
        val indices = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'").use { rs ->
                while (rs.next()) indices += rs.getString(1)
            }
        }
        // 索引名写错会让 Room 的 onValidateSchema 在真机启动时抛异常，
        // 而纯 JVM 测试发现不了 —— 所以这里显式钉住名字。
        assertTrue("缺 index_seen_key_at", "index_seen_key_at" in indices)
        assertTrue("缺 index_rule_type_app_enabled", "index_rule_type_app_enabled" in indices)
        assertTrue("缺 index_rule_creator_enabled", "index_rule_creator_enabled" in indices)
        assertTrue("老索引 index_Bill_external_id 被删了", "index_Bill_external_id" in indices)
    }

    @Test
    fun `迁移可重复执行而不报错`() {
        applyMigration()
        // 第二次执行必须是空操作。真实场景：用户清数据后从备份还原、或升级流程被重入。
        applyMigration()
        assertEquals(2, countOf("Bill"))
    }

    @Test
    fun `seen_key 的清理语句能按 at 删除过期行`() {
        applyMigration()
        val now = 1_700_000_000_000L
        db.createStatement().use { st ->
            st.execute(
                "INSERT INTO seen_key (`key`,`at`) VALUES ('fresh',$now),('stale',${now - 11 * 60 * 1000})",
            )
            // 与 SeenKeyDao.deleteExpired 完全同一条 SQL
            st.executeUpdate("DELETE FROM seen_key WHERE at < ${now - LedgerSchema.SEEN_KEY_TTL_MS}")
        }
        assertEquals(1, countOf("seen_key"))
        db.createStatement().use { st ->
            st.executeQuery("SELECT `key` FROM seen_key").use { rs ->
                assertTrue(rs.next())
                assertEquals("fresh", rs.getString(1))
            }
        }
    }

    @Test
    fun `seen_key 主键冲突时 REPLACE 会刷新时间而不是插出第二行`() {
        applyMigration()
        db.createStatement().use { st ->
            st.execute("INSERT OR REPLACE INTO seen_key (`key`,`at`) VALUES ('k',1000)")
            st.execute("INSERT OR REPLACE INTO seen_key (`key`,`at`) VALUES ('k',2000)")
        }
        assertEquals(1, countOf("seen_key"))
        db.createStatement().use { st ->
            st.executeQuery("SELECT at FROM seen_key WHERE `key`='k'").use { rs ->
                assertTrue(rs.next())
                assertEquals(2000L, rs.getLong(1))
            }
        }
    }

    @Test
    fun `rule 表的 app 可为 NULL 且能按类型与包名筛出通用规则`() {
        applyMigration()
        db.createStatement().use { st ->
            st.execute(
                "INSERT INTO rule (id,app,type,name,creator,pattern_json,enabled,auto_record,updated_at) " +
                    "VALUES ('notice/*/通用',NULL,'notice','通用','system','{}',1,1,0)," +
                    "('notice/com.tencent.mm/微信','com.tencent.mm','notice','微信','system','{}',1,1,0)," +
                    "('notice/com.tencent.mm/关掉的','com.tencent.mm','notice','关掉的','system','{}',0,1,0)",
            )
        }
        // 迁移数据校验：包名精确匹配 + NULL（任意包名）都要命中
        val ids = mutableListOf<String>()
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT id FROM rule WHERE enabled = 1 AND type = 'notice' " +
                    "AND (app IS NULL OR app = 'com.tencent.mm') ORDER BY creator DESC, name ASC",
            ).use { rs -> while (rs.next()) ids += rs.getString(1) }
        }
        assertEquals(listOf("notice/*/通用", "notice/com.tencent.mm/微信").sorted(), ids.sorted())
        assertTrue("enabled = 0 的规则不该出现", "notice/com.tencent.mm/关掉的" !in ids)
    }

    @Test
    fun `内置规则播种是幂等的且不覆盖用户的开关`() {
        applyMigration()
        // 模拟首次启动播种
        insertSeedRules()
        assertEquals(RuleSeed.systemRules().size, countOf("rule"))

        // 用户关掉其中一条
        db.createStatement().use { st ->
            st.executeUpdate(
                "UPDATE rule SET enabled = 0 WHERE id = '" +
                    RuleEntity.makeId(RuleType.NOTICE, "com.tencent.mm", "微信支付通知") + "'",
            )
        }

        // 再次播种（版本升级后走同一条路径）
        insertSeedRules()

        assertEquals("重复播种不该插出新行", RuleSeed.systemRules().size, countOf("rule"))
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT enabled FROM rule WHERE id = '" +
                    RuleEntity.makeId(RuleType.NOTICE, "com.tencent.mm", "微信支付通知") + "'",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals("用户的关闭状态被播种覆盖了", 0, rs.getInt(1))
            }
        }
    }

    @Test
    fun `播种的规则 JSON 可被解析且字段齐全`() {
        RuleSeed.systemRules(now = 123L).forEach { rule ->
            val json = runCatching { JSONObject(rule.patternJson) }.getOrNull()
            assertNotNull("${rule.id} 的 patternJson 不是合法 JSON", json)
            json!!
            assertEquals(RuleSeed.PATTERN_VERSION, json.getInt("v"))
            assertEquals(rule.type, json.getString("type"))
            assertTrue("${rule.id} 缺 amount 正则", json.getJSONArray("amount").length() > 0)
            assertTrue("${rule.id} 缺 expense 词表", json.getJSONArray("expense").length() > 0)
            assertTrue("${rule.id} 缺 block 词表", json.getJSONArray("block").length() > 0)
            assertTrue("${rule.id} 缺 cats", json.getJSONObject("cats").length() > 0)
            // app 为 null 的规则，JSON 里必须是 null 而不是字符串 "null"
            if (rule.app == null) assertTrue(json.isNull("app")) else assertEquals(rule.app, json.getString("app"))
        }
    }

    @Test
    fun `内置规则全部是 system 且默认启用并自动记账`() {
        val rules = RuleSeed.systemRules()
        assertTrue("没有内置规则", rules.isNotEmpty())
        rules.forEach {
            assertEquals(RuleEntity.CREATOR_SYSTEM, it.creator)
            assertTrue("${it.id} 默认应启用", it.enabled)
            assertTrue("${it.id} 默认应自动记账", it.autoRecord)
        }
        // id 必须能由 type/app/name 重新拼出来，否则改名即换 id 的约定就断了
        rules.forEach {
            assertEquals(it.id, RuleEntity.makeId(it.type, it.app, it.name))
        }
        assertEquals("内置规则 id 有重复", rules.size, rules.map { it.id }.toSet().size)
    }

    @Test
    fun `规则 JSON 里的正则全部可编译`() {
        RuleSeed.systemRules().forEach { rule ->
            val json = JSONObject(rule.patternJson)
            val amount = json.getJSONArray("amount")
            for (i in 0 until amount.length()) {
                val pattern = amount.getString(i)
                // 编不过就是运行时才炸的炸弹，必须在这里拦住
                val regex = runCatching { Regex(pattern) }.getOrNull()
                assertNotNull("${rule.id} 的第 $i 条金额正则编不过：$pattern", regex)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun applyMigration() {
        // 与 MIGRATION_3_4.migrate 完全相同的执行方式
        LedgerSchema.V4_CREATE_STATEMENTS.forEach { db.createStatement().use { st -> st.execute(it) } }
    }

    private fun insertSeedRules() {
        val sql =
            "INSERT OR IGNORE INTO `rule` " +
                "(`id`,`app`,`type`,`name`,`creator`,`pattern_json`,`enabled`,`auto_record`,`updated_at`) " +
                "VALUES (?,?,?,?,?,?,?,?,?)"
        RuleSeed.systemRules().forEach { rule ->
            db.prepareStatement(sql).use { ps ->
                ps.setString(1, rule.id)
                if (rule.app == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, rule.app)
                ps.setString(3, rule.type)
                ps.setString(4, rule.name)
                ps.setString(5, rule.creator)
                ps.setString(6, rule.patternJson)
                ps.setInt(7, if (rule.enabled) 1 else 0)
                ps.setInt(8, if (rule.autoRecord) 1 else 0)
                ps.setLong(9, rule.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    private fun columnsOf(table: String): List<String> {
        val cols = mutableListOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) cols += "${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}"
            }
        }
        return cols
    }

    private fun countOf(table: String): Int {
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }
}
