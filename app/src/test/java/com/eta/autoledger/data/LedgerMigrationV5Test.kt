package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * v4 → v5 迁移测试：Bill 增量追加可空 sub_category，历史数据不丢失且不自动回填。
 *
 * 与 [LedgerMigrationV4Test] 相同，这里用 sqlite-jdbc 执行与线上迁移一致的 DDL。
 * Room 的 schema 校验仍需真机启动覆盖，因此测试同时钉住列类型与 nullable 属性。
 */
class LedgerMigrationV5Test {

    private lateinit var db: Connection

    /** Room v4 的实际 Bill schema，包含规则/去重表存在时会附带的历史列。 */
    private val v4Schema = listOf(
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
    )

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        v4Schema.forEach { sql ->
            db.createStatement().use { it.execute(sql) }
        }
        db.createStatement().use { st ->
            st.execute(
                "INSERT INTO Bill " +
                    "(amount,type,category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                    "VALUES (46.94,'expense','餐饮','11-20 15:11',1700464260000,'午餐','微信通知','wx-abc',0,NULL)",
            )
            st.execute(
                "INSERT INTO Bill " +
                    "(amount,type,category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                    "VALUES (5000.0,'income','薪资','11-10 09:00',1699587600000,'发薪','手动',NULL,1,'MONTHLY_10')",
            )
        }
    }

    @Test
    fun `迁移后历史账单数量与原有字段不变`() {
        applyMigration()

        assertEquals(2, countOf("Bill"))
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT amount,type,category,remark,source,external_id,is_recurring,recurring_rule " +
                    "FROM Bill ORDER BY id",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(46.94, rs.getDouble("amount"), 0.001)
                assertEquals("expense", rs.getString("type"))
                assertEquals("餐饮", rs.getString("category"))
                assertEquals("午餐", rs.getString("remark"))
                assertEquals("微信通知", rs.getString("source"))
                assertEquals("wx-abc", rs.getString("external_id"))
                assertEquals(0, rs.getInt("is_recurring"))
                assertNull(rs.getString("recurring_rule"))

                assertTrue(rs.next())
                assertEquals(5000.0, rs.getDouble("amount"), 0.001)
                assertEquals("income", rs.getString("type"))
                assertEquals("薪资", rs.getString("category"))
                assertEquals(1, rs.getInt("is_recurring"))
                assertEquals("MONTHLY_10", rs.getString("recurring_rule"))
                assertTrue(!rs.next())
            }
        }
    }

    @Test
    fun `历史账单的子分类全部保持 NULL`() {
        applyMigration()

        db.createStatement().use { st ->
            st.executeQuery("SELECT sub_category FROM Bill ORDER BY id").use { rs ->
                assertTrue(rs.next())
                assertNull("历史支出账单不允许自动回填子分类", rs.getString("sub_category"))
                assertTrue(rs.next())
                assertNull("历史收入账单不允许自动回填子分类", rs.getString("sub_category"))
            }
        }
    }

    @Test
    fun `新增子分类列是 TEXT 且允许 NULL`() {
        applyMigration()

        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`Bill`)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "sub_category") {
                        found = true
                        assertEquals("TEXT", rs.getString("type"))
                        assertEquals(0, rs.getInt("notnull"))
                    }
                }
                assertTrue("Bill 缺少 sub_category 列", found)
            }
        }
    }

    @Test
    fun `迁移后新账单可以写入子分类`() {
        applyMigration()

        db.prepareStatement(
            "INSERT INTO Bill " +
                "(amount,type,category,sub_category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setDouble(1, 18.0)
            ps.setString(2, "expense")
            ps.setString(3, "消费")
            ps.setString(4, "餐饮")
            ps.setString(5, "11-23 12:00")
            ps.setLong(6, 1700712000000L)
            ps.setString(7, "工作餐")
            ps.setString(8, "手动")
            ps.setNull(9, Types.VARCHAR)
            ps.setInt(10, 0)
            ps.setNull(11, Types.VARCHAR)
            ps.executeUpdate()
        }

        db.createStatement().use { st ->
            st.executeQuery("SELECT category,sub_category FROM Bill ORDER BY id DESC LIMIT 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("消费", rs.getString("category"))
                assertEquals("餐饮", rs.getString("sub_category"))
            }
        }
    }

    private fun applyMigration() {
        db.createStatement().use { it.execute(LedgerSchema.V5_ADD_SUB_CATEGORY) }
    }

    private fun countOf(table: String): Int {
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }
}
