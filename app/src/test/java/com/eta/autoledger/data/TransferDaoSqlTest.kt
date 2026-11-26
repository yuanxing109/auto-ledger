package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** 内存 SQLite 验证转账候选、原子认领和统计排除共用的真实 SQL。 */
class TransferDaoSqlTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE Bill (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amount REAL NOT NULL,
                    type TEXT NOT NULL,
                    category TEXT NOT NULL,
                    sub_category TEXT,
                    time_text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    remark TEXT NOT NULL,
                    source TEXT NOT NULL,
                    external_id TEXT,
                    is_recurring INTEGER NOT NULL DEFAULT 0,
                    recurring_rule TEXT,
                    is_transfer INTEGER NOT NULL DEFAULT 0,
                    transfer_pair_id TEXT
                )
                """.trimIndent(),
            )
        }
        insert("expense", 1000.0, 1_700_000_000_000L, "信用卡还款")
        insert("income", 1000.0, 1_700_000_120_000L, "还款入账")
    }

    @Test
    fun `候选查询按反向金额和时间窗返回唯一匹配`() {
        val rows = queryCandidates(entryId = 1L, oppositeType = "income", center = 1_700_000_000_000L)

        assertEquals(1, rows.size)
        assertEquals("income", rows.single().type)
    }

    @Test
    fun `原子认领只更新两行且重复认领失败`() {
        val firstClaim = claim("pair-a", 1L, 2L)
        assertEquals(2, firstClaim)

        val secondClaim = claim("pair-b", 1L, 2L)
        assertEquals(0, secondClaim)
        db.createStatement().use { st ->
            st.executeQuery("SELECT is_transfer,transfer_pair_id FROM Bill ORDER BY id").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("is_transfer"))
                assertEquals("pair-a", rs.getString("transfer_pair_id"))
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("is_transfer"))
                assertEquals("pair-a", rs.getString("transfer_pair_id"))
            }
        }
    }

    @Test
    fun `解除合并成对恢复并重新进入分类聚合`() {
        claim("pair-c", 1L, 2L)
        val excluded = db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM Bill WHERE is_transfer = 1").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        assertEquals(2, excluded)

        val released = db.prepareStatement(LedgerDaoSql.RELEASE_TRANSFER_PAIR).use { ps ->
            ps.setString(1, "pair-c")
            ps.executeUpdate()
        }
        assertEquals(2, released)
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM Bill WHERE is_transfer = 0 AND transfer_pair_id IS NULL").use { rs ->
                rs.next()
                assertEquals(2, rs.getInt(1))
            }
        }
    }

    @Test
    fun `金额分四舍五入后可精确配对`() {
        insert("expense", 0.1 + 0.2, 1_700_000_000_000L, "信用卡还款")
        insert("income", 0.3, 1_700_000_060_000L, "信用卡还款入账")
        val rows = queryCandidates(
            entryId = 3L,
            oppositeType = "income",
            center = 1_700_000_000_000L,
            amountCents = 30L,
        )

        assertEquals(1, rows.size)
        assertEquals(30L, FingerprintCodec.amountFen(rows.single().amount))
    }

    @Test
    fun `统计聚合排除转账但列表查询仍保留`() {
        claim("pair-d", 1L, 2L)
        val stats = db.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*) FROM Bill WHERE is_recurring = 0 AND is_transfer = 0",
            ).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        val list = db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM Bill WHERE is_recurring = 0").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        assertEquals(0, stats)
        assertEquals(2, list)
    }

    @Test
    fun `单边删除后可清理悬空转账标记`() {
        claim("pair-e", 1L, 2L)
        db.createStatement().use { it.execute("DELETE FROM Bill WHERE id = 2") }
        val cleared = db.createStatement().use { it.executeUpdate(LedgerDaoSql.CLEAR_DANGLING_TRANSFER_PAIRS) }

        assertEquals(1, cleared)
        db.createStatement().use { st ->
            st.executeQuery("SELECT is_transfer,transfer_pair_id FROM Bill").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("is_transfer"))
                assertEquals(null, rs.getString("transfer_pair_id"))
            }
        }
    }

    @Test
    fun `恢复时金额不符的旧配对会被清理`() {
        claim("pair-f", 1L, 2L)
        db.createStatement().use { it.execute("UPDATE Bill SET amount = 1001.0 WHERE id = 2") }

        val released = db.createStatement().use {
            it.executeUpdate(LedgerDaoSql.RELEASE_INVALID_TRANSFER_PAIRS)
        }

        assertEquals(2, released)
    }

    @Test
    fun `删除快照 SQL 先释放配对再删除单边`() {
        claim("pair-g", 1L, 2L)
        val snapshot = db.createStatement().use { st ->
            st.executeQuery("SELECT * FROM Bill WHERE id = 1").use { rs ->
                rs.next()
                rs.getString("transfer_pair_id")
            }
        }
        assertEquals("pair-g", snapshot)

        db.prepareStatement(LedgerDaoSql.RELEASE_TRANSFER_PAIR).use { ps ->
            ps.setString(1, "pair-g")
            ps.executeUpdate()
        }
        db.createStatement().use { it.execute("DELETE FROM Bill WHERE id = 1") }

        db.createStatement().use { st ->
            st.executeQuery("SELECT is_transfer,transfer_pair_id FROM Bill WHERE id = 2").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("is_transfer"))
                assertEquals(null, rs.getString("transfer_pair_id"))
            }
        }
    }

    private fun queryCandidates(
        entryId: Long,
        oppositeType: String,
        center: Long,
        amountCents: Long = 100_000L,
    ): List<LedgerEntry> {
        val rows = mutableListOf<LedgerEntry>()
        db.prepareStatement(LedgerDaoSql.TRANSFER_PAIR_CANDIDATES).use { ps ->
            ps.setLong(1, entryId)
            ps.setString(2, oppositeType)
            ps.setLong(3, amountCents)
            ps.setLong(4, center - TransferPairMatcher.WINDOW_MS)
            ps.setLong(5, center + TransferPairMatcher.WINDOW_MS)
            ps.setLong(6, center)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += LedgerEntry(
                        id = rs.getLong("id"),
                        amount = rs.getDouble("amount"),
                        type = rs.getString("type"),
                        category = rs.getString("category"),
                        time = rs.getString("time_text"),
                        timeMillis = rs.getLong("timestamp"),
                        note = rs.getString("remark"),
                        source = rs.getString("source"),
                        isRecurring = rs.getInt("is_recurring") != 0,
                        isTransfer = rs.getInt("is_transfer") != 0,
                        transferPairId = rs.getString("transfer_pair_id"),
                    )
                }
            }
        }
        return rows
    }

    private fun claim(pairId: String, first: Long, second: Long): Int =
        db.prepareStatement(LedgerDaoSql.CLAIM_TRANSFER_PAIR).use { ps ->
            ps.setString(1, pairId)
            ps.setLong(2, first)
            ps.setLong(3, second)
            ps.executeUpdate()
        }

    private fun insert(type: String, amount: Double, timestamp: Long, note: String) {
        db.prepareStatement(
            "INSERT INTO Bill " +
                "(amount,type,category,time_text,timestamp,remark,source,is_recurring,is_transfer) " +
                "VALUES (?,?,?,?,?,?,?,0,0)",
        ).use { ps ->
            ps.setDouble(1, amount)
            ps.setString(2, type)
            ps.setString(3, "转账")
            ps.setString(4, "2023-11-15 06:13")
            ps.setLong(5, timestamp)
            ps.setString(6, note)
            ps.setString(7, "test")
            ps.executeUpdate()
        }
    }
}
