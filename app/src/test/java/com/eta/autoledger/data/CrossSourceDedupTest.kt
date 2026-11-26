package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** 用真实 SQLite 验证跨渠道候选查询与合并后只保留一行。 */
class CrossSourceDedupTest {

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
                    timestamp INTEGER NOT NULL,
                    remark TEXT NOT NULL,
                    source TEXT NOT NULL,
                    is_recurring INTEGER NOT NULL DEFAULT 0,
                    is_transfer INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX index_Bill_timestamp ON Bill(timestamp)")
        }
        insertBankRow()
    }

    @Test
    fun `微信与银行相差一分钟时最终只保留一条并合并来源`() {
        val incoming = entry(
            id = 0L,
            timestamp = 1_700_000_060_000L,
            source = "微信通知",
            note = "微信支付：已支付¥22.00",
        )
        val candidates = queryCandidates(incoming)
        val matched = ParseDedup.chooseMergeTarget(incoming, candidates)

        assertTrue(matched != null)
        val merged = ParseDedup.mergeRecords(matched!!, incoming)
        db.prepareStatement("UPDATE Bill SET remark = ?, source = ? WHERE id = ?").use { ps ->
            ps.setString(1, merged.note)
            ps.setString(2, merged.source)
            ps.setLong(3, merged.id)
            ps.executeUpdate()
        }

        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*), MIN(remark), MIN(source) FROM Bill").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
                assertTrue(rs.getString(2).contains("账户0306"))
                assertTrue(rs.getString(3).contains("招商银行通知"))
                assertTrue(rs.getString(3).contains("微信通知"))
            }
        }
    }

    @Test
    fun `候选查询走时间索引而不是无界全表扫描`() {
        val statements = mutableListOf<String>()
        db.prepareStatement("EXPLAIN QUERY PLAN ${LedgerDaoSql.TIMED_MERGE_CANDIDATES}").use { ps ->
            ps.setLong(1, 2_200L)
            ps.setString(2, "expense")
            ps.setLong(3, 1_699_999_700_000L)
            ps.setLong(4, 1_699_999_760_000L)
            ps.executeQuery().use { rs ->
                while (rs.next()) statements += rs.getString("detail")
            }
        }

        assertTrue(statements.any { it.contains("index_Bill_timestamp") })
    }

    private fun queryCandidates(incoming: LedgerEntry): List<LedgerEntry> {
        val rows = mutableListOf<LedgerEntry>()
        db.prepareStatement(LedgerDaoSql.TIMED_MERGE_CANDIDATES).use { ps ->
            ps.setLong(1, FingerprintCodec.amountFen(incoming.amount))
            ps.setString(2, incoming.type)
            ps.setLong(3, incoming.timeMillis - ParseDedup.MERGE_WINDOW_MS)
            ps.setLong(4, incoming.timeMillis + ParseDedup.MERGE_WINDOW_MS)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += entry(
                        id = rs.getLong("id"),
                        timestamp = rs.getLong("timestamp"),
                        source = rs.getString("source"),
                        note = rs.getString("remark"),
                    )
                }
            }
        }
        return rows
    }

    private fun insertBankRow() {
        db.prepareStatement(
            "INSERT INTO Bill (amount,type,timestamp,remark,source,is_recurring,is_transfer) " +
                "VALUES (22.0,'expense',1700000000000,?,'招商银行通知',0,0)",
        ).use { ps ->
            ps.setString(1, "招商银行 您账户0306于17:50在财付通-微信支付-商户 消费22.00元")
            ps.executeUpdate()
        }
    }

    private fun entry(id: Long, timestamp: Long, source: String, note: String): LedgerEntry =
        LedgerEntry(
            id = id,
            amount = 22.0,
            type = "expense",
            category = "消费",
            time = "2026-11-26 17:50",
            timeMillis = timestamp,
            note = note,
            source = source,
        )
}
