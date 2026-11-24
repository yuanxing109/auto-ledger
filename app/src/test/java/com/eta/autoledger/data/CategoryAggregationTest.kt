package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 报表分类聚合的内存 SQLite 测试。
 *
 * 项目没有 room-testing/Robolectric，因此用 sqlite-jdbc 执行与
 * [LedgerDao.observeCategories] 完全相同的 [LedgerDaoSql.DUAL_CATEGORY_AGGREGATION]。
 * 该测试覆盖主分类汇总、子分类拆分、历史 NULL 分组以及占比计算。
 */
class CategoryAggregationTest {

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
                    `sub_category` TEXT,
                    `time_text` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `remark` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `external_id` TEXT,
                    `is_recurring` INTEGER NOT NULL DEFAULT 0,
                    `recurring_rule` TEXT
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX `index_Bill_timestamp` ON `Bill` (`timestamp`)")
            st.execute("CREATE UNIQUE INDEX `index_Bill_external_id` ON `Bill` (`external_id`)")
        }

        insert(amount = 20.0, category = "餐饮", subCategory = "外卖", day = 1)
        insert(amount = 30.0, category = "餐饮", subCategory = "外卖", day = 2)
        insert(amount = 10.0, category = "餐饮", subCategory = null, day = 3)
        insert(amount = 8.0, category = "交通", subCategory = null, day = 4)
        insert(amount = 12.0, category = "交通", subCategory = null, day = 5)
        // 收入账单不能污染支出聚合。
        insert(amount = 100.0, category = "餐饮", subCategory = "外卖", day = 6, type = "income")
    }

    @Test
    fun `双重聚合返回子分类金额并保留历史 NULL 分组`() {
        val rows = queryCategoryRows()

        assertEquals(3, rows.size)
        assertRow(rows, category = "餐饮", subCategory = "外卖", total = 50.0, count = 2)
        assertRow(rows, category = "餐饮", subCategory = null, total = 10.0, count = 1)
        assertRow(rows, category = "交通", subCategory = null, total = 20.0, count = 2)
    }

    @Test
    fun `按主分类汇总时餐饮总额正确`() {
        val breakdowns = buildCategoryBreakdowns(queryCategoryRows())

        val food = breakdowns.single { it.name == "餐饮" }
        assertEquals(60.0, food.total, 0.001)
        assertEquals(3, food.count)
        assertTrue(food.hasSubCategories)

        val transport = breakdowns.single { it.name == "交通" }
        assertEquals(20.0, transport.total, 0.001)
        assertEquals(2, transport.count)
        assertFalse(transport.hasSubCategories)
    }

    @Test
    fun `子分类占比以所属主分类总额为分母`() {
        val food = buildCategoryBreakdowns(queryCategoryRows()).single { it.name == "餐饮" }

        val takeout = food.subCategories.single { it.name == "外卖" }
        val legacyNull = food.subCategories.single { it.name == null }
        assertEquals(50.0, takeout.total, 0.001)
        assertEquals(83.333f, takeout.percent, 0.01f)
        assertEquals(10.0, legacyNull.total, 0.001)
        assertEquals(16.667f, legacyNull.percent, 0.01f)
        assertEquals(100.0, food.subCategories.sumOf { it.percent.toDouble() }, 0.01)
    }

    private fun insert(
        amount: Double,
        category: String,
        subCategory: String?,
        day: Int,
        type: String = "expense",
    ) {
        db.prepareStatement(
            "INSERT INTO Bill " +
                "(amount,type,category,sub_category,time_text,timestamp,remark,source,external_id,is_recurring,recurring_rule) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setDouble(1, amount)
            ps.setString(2, type)
            ps.setString(3, category)
            if (subCategory == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, subCategory)
            ps.setString(5, "11-${day.toString().padStart(2, '0')} 12:00")
            ps.setLong(6, 1_700_000_000_000L + day * 86_400_000L)
            ps.setString(7, category)
            ps.setString(8, "test")
            ps.setNull(9, java.sql.Types.VARCHAR)
            ps.setInt(10, 0)
            ps.setNull(11, java.sql.Types.VARCHAR)
            ps.executeUpdate()
        }
    }

    private fun queryCategoryRows(): List<CategoryAgg> {
        val rows = mutableListOf<CategoryAgg>()
        db.prepareStatement(LedgerDaoSql.DUAL_CATEGORY_AGGREGATION).use { ps ->
            ps.setInt(1, 0)
            ps.setLong(2, 1_700_000_000_000L)
            ps.setLong(3, 1_700_000_000_000L + 10L * 86_400_000L)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += CategoryAgg(
                        name = rs.getString("name"),
                        total = rs.getDouble("total"),
                        count = rs.getInt("count"),
                        subCategory = rs.getString("subCategory"),
                    )
                }
            }
        }
        return rows
    }

    private fun assertRow(
        rows: List<CategoryAgg>,
        category: String,
        subCategory: String?,
        total: Double,
        count: Int,
    ) {
        val row = rows.single { it.name == category && it.subCategory == subCategory }
        assertEquals(total, row.total, 0.001)
        assertEquals(count, row.count)
        if (subCategory == null) assertNull(row.subCategory)
    }
}
