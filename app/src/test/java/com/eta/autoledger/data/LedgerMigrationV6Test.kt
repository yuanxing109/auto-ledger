package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** v5 → v6：历史账单保留，转账字段默认关闭，配对索引允许两行共享同一 pair id。 */
class LedgerMigrationV6Test {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE `Bill` (
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
                    `recurring_rule` TEXT,
                    `sub_category` TEXT
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX index_Bill_timestamp ON Bill(timestamp)")
            st.execute("CREATE UNIQUE INDEX index_Bill_external_id ON Bill(external_id)")
            st.execute(
                "INSERT INTO Bill " +
                    "(amount,type,category,sub_category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                    "VALUES (46.94,'expense','餐饮',NULL,'11-20 15:11',1700464260000,'午餐','微信通知','wx-abc',0,NULL)",
            )
            st.execute(
                "INSERT INTO Bill " +
                    "(amount,type,category,sub_category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                    "VALUES (5000.0,'income','薪资',NULL,'11-10 09:00',1699587600000,'发薪','手动',NULL,1,'MONTHLY_10')",
            )
        }
    }

    @Test
    fun `迁移不丢历史数据且转账标记默认关闭`() {
        migrate()

        db.createStatement().use { st ->
            st.executeQuery("SELECT amount,category,is_transfer,transfer_pair_id FROM Bill ORDER BY id").use { rs ->
                assertTrue(rs.next())
                assertEquals(46.94, rs.getDouble("amount"), 0.001)
                assertEquals("餐饮", rs.getString("category"))
                assertEquals(0, rs.getInt("is_transfer"))
                assertNull(rs.getString("transfer_pair_id"))
                assertTrue(rs.next())
                assertEquals(5000.0, rs.getDouble("amount"), 0.001)
                assertEquals("薪资", rs.getString("category"))
                assertEquals(0, rs.getInt("is_transfer"))
                assertNull(rs.getString("transfer_pair_id"))
                assertTrue(!rs.next())
            }
        }
    }

    @Test
    fun `pair id 可被两笔转账共享且索引不是唯一索引`() {
        migrate()
        db.createStatement().use { st ->
            st.execute("UPDATE Bill SET is_transfer = 1, transfer_pair_id = 'pair-1'")
            st.executeQuery("SELECT COUNT(*) FROM Bill WHERE transfer_pair_id = 'pair-1'").use { rs ->
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
            }
        }
    }

    @Test
    fun `is transfer 是非空整数列且默认0`() {
        migrate()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`Bill`)").use { rs ->
                var checked = false
                while (rs.next()) {
                    if (rs.getString("name") == "is_transfer") {
                        checked = true
                        assertEquals("INTEGER", rs.getString("type"))
                        assertEquals(1, rs.getInt("notnull"))
                        assertEquals("0", rs.getString("dflt_value"))
                    }
                }
                assertTrue(checked)
            }
        }
    }

    @Test
    fun `迁移同时创建非唯一配对索引`() {
        migrate()

        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`Bill`)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "index_Bill_transfer_pair_id") {
                        found = true
                        assertEquals(0, rs.getInt("unique"))
                    }
                }
                assertTrue(found)
            }
        }
    }

    @Test
    fun `解除合并 SQL 成对清空状态`() {
        migrate()
        db.createStatement().use { st ->
            st.execute("UPDATE Bill SET is_transfer = 1, transfer_pair_id = 'pair-2'")
        }
        val released = db.prepareStatement(LedgerDaoSql.RELEASE_TRANSFER_PAIR).use { ps ->
            ps.setString(1, "pair-2")
            ps.executeUpdate()
        }
        assertEquals(2, released)
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM Bill WHERE is_transfer = 0 AND transfer_pair_id IS NULL").use { rs ->
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
            }
        }
    }

    private fun migrate() {
        LedgerSchema.V6_ADD_TRANSFER_FIELDS.forEach { sql ->
            db.createStatement().use { it.execute(sql) }
        }
    }
}
