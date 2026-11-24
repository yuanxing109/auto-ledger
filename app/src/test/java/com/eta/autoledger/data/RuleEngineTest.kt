package com.eta.autoledger.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 动态规则引擎的断言：缓存、优先级、方向、分类、熔断。
 *
 * 用内存里的假 DAO 而不是 Room：引擎的输入就是 `List<RuleEntity>`，
 * 造一张真表只会把测试拖进迁移与线程调度的细节里，测不到任何额外的东西。
 * 真表的读写由 `LedgerMigrationV4Test` 覆盖。
 *
 * 用 `runBlocking` 而不是 `runTest`：项目没有引入 kotlinx-coroutines-test，
 * 而这些用例里没有 `delay`，虚拟时间用不上。
 */
class RuleEngineTest {

    @Before
    fun reset() = RuleEngine.resetForTest()

    // ── 缓存与加载 ────────────────────────────────────────────────────

    @Test
    fun 首次加载后缓存里有规则() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "我的微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        assertEquals(1, RuleEngine.cachedCount)
        // 第二次不该再查库
        val before = dao.loadCount
        RuleEngine.ensureLoaded(dao)
        assertEquals(before, dao.loadCount)
    }

    @Test
    fun invalidate之后会重新查库() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "A", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        val before = dao.loadCount
        RuleEngine.invalidate()
        RuleEngine.ensureLoaded(dao)
        assertTrue("invalidate 之后必须重查", dao.loadCount > before)
    }

    @Test
    fun 用户规则排在系统规则前面() = runBlocking {
        val dao = FakeRuleDao(
            listOf(
                // 两条都能匹配同一条正文，系统规则词表更宽
                noticeRule(RuleEntity.CREATOR_SYSTEM, "系统微信", "com.tencent.mm"),
                noticeRule(RuleEntity.CREATOR_USER, "用户微信", "com.tencent.mm"),
            ),
        )
        RuleEngine.ensureLoaded(dao)
        val hit = RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 元 支付成功")
        assertNotNull(hit)
        assertEquals("用户微信", hit?.ruleName)
    }

    @Test
    fun 规则表为空时不会每次都重查数据库() = runBlocking {
        val dao = FakeRuleDao(emptyList())
        RuleEngine.ensureLoaded(dao)
        val before = dao.loadCount
        RuleEngine.ensureLoaded(dao)
        RuleEngine.ensureLoaded(dao)
        assertEquals("空表也是「已加载」，不能把空当成没加载", before, dao.loadCount)
    }

    @Test
    fun 加载失败不记版本号下次会重试() = runBlocking {
        val dao = FlakyRuleDao()
        dao.fail = true
        RuleEngine.ensureLoaded(dao)
        assertEquals(0, RuleEngine.cachedCount)
        // 故障恢复后，下一次解析应当拿到规则，而不是等重启
        dao.fail = false
        RuleEngine.ensureLoaded(dao)
        assertEquals(1, RuleEngine.cachedCount)
    }

    // ── 匹配语义 ──────────────────────────────────────────────────────

    @Test
    fun 包名不匹配的规则不命中() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        assertNull(RuleEngine.match(RuleType.NOTICE, "com.eg.android.AlipayGphone", "微信支付 12.34 元"))
    }

    @Test
    fun 类型不匹配的规则不命中() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        assertNull(RuleEngine.match(RuleType.SMS, "com.tencent.mm", "微信支付 12.34 元"))
    }

    @Test
    fun app为null的规则对任意包名生效() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "通用", app = null)))
        RuleEngine.ensureLoaded(dao)
        assertNotNull(RuleEngine.match(RuleType.NOTICE, "任意.包名", "微信支付 88.00 支付成功"))
    }

    @Test
    fun 屏蔽词命中直接不匹配() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        // blockWords 里含「优惠券」
        assertNull(RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 元 优惠券已到账"))
    }

    @Test
    fun 词表一个都没命中时不硬凑() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        // 有金额但没有支付/收款词 → 证据不足
        assertNull(RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 元"))
    }

    @Test
    fun 收入词多于支出词时判为收入() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        val hit = RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 元 收款到账 已入账")
        assertNotNull(hit)
        assertEquals("income", hit?.type)
    }

    @Test
    fun 金额正则使用DOTALL跨行匹配() = runBlocking {
        val rule = noticeRule("user", "跨行", "com.tencent.mm").copy(
            patternJson = """
                {"v":2,"type":"notice","app":"com.tencent.mm",
                 "necessaryFields":["amount","direction"],
                 "amount":["支付成功.*?(\\d+(?:\\.\\d{1,2})?)"],
                 "expense":["支付成功"],"income":[],"block":[],"cats":{}}
            """.trimIndent(),
        )
        RuleEngine.ensureLoaded(FakeRuleDao(listOf(rule)))

        val hit = RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "支付成功\n12.34")
        assertEquals(12.34, hit?.amount ?: 0.0, 0.001)
    }

    @Test
    fun 旧JSON缺少necessaryFields时按默认契约兼容() = runBlocking {
        val oldRule = noticeRule("user", "旧规则", "com.tencent.mm")
        assertTrue(!JSONObject(oldRule.patternJson).has("necessaryFields"))
        RuleEngine.ensureLoaded(FakeRuleDao(listOf(oldRule)))

        val hit = RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 支付成功")
        assertNotNull(hit)
        assertEquals(12.34, hit?.amount ?: 0.0, 0.001)
    }

    @Test
    fun 缺少方向证据的规则被丢弃() = runBlocking {
        val rule = noticeRule("user", "无方向证据", "com.tencent.mm").copy(
            patternJson = """
                {"v":2,"type":"notice","app":"com.tencent.mm",
                 "necessaryFields":["amount","direction"],
                 "amount":["微信支付\\s*(\\d+(?:\\.\\d{1,2})?)"],
                 "expense":[],"income":[],"block":[],"cats":{}}
            """.trimIndent(),
        )
        RuleEngine.ensureLoaded(FakeRuleDao(listOf(rule)))

        assertEquals(0, RuleEngine.cachedCount)
        assertNull(RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34"))
    }

    @Test
    fun 未知必填能力按缺失处理并丢弃规则() = runBlocking {
        val rule = noticeRule("user", "未知能力", "com.tencent.mm").copy(
            patternJson = """
                {"v":2,"type":"notice","app":"com.tencent.mm",
                 "necessaryFields":["amount","direction","futureField"],
                 "amount":["微信支付\\s*(\\d+(?:\\.\\d{1,2})?)"],
                 "expense":["支付成功"],"income":[],"block":[],"cats":{}}
            """.trimIndent(),
        )
        RuleEngine.ensureLoaded(FakeRuleDao(listOf(rule)))

        assertEquals(0, RuleEngine.cachedCount)
    }

    @Test
    fun 命中结果带上规则的自动记账开关() = runBlocking {
        val dao = FakeRuleDao(
            listOf(noticeRule("user", "微信", "com.tencent.mm", autoRecord = false)),
        )
        RuleEngine.ensureLoaded(dao)
        val hit = RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 元 支付成功")
        assertEquals(false, hit?.autoRecord)
    }

    @Test
    fun 分类按命中词数打分() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        // 「支付成功」让规则命中并把方向判为支出；分类上转账词命中两个、消费词只命中一个
        val hit = RuleEngine.match(
            RuleType.NOTICE,
            "com.tencent.mm",
            "微信支付 12.34 支付成功 转账 转给 张三",
        )
        assertNotNull(hit)
        assertEquals("转账", hit?.category)
    }

    // ── 坏数据与熔断 ──────────────────────────────────────────────────

    @Test
    fun JSON坏掉的规则被跳过而不是整批失败() = runBlocking {
        val good = noticeRule("user", "好的", "com.tencent.mm")
        val bad = good.copy(id = "notice/com.tencent.mm/坏的", name = "坏的", patternJson = "{ 这不是 JSON")
        val dao = FakeRuleDao(listOf(bad, good))
        RuleEngine.ensureLoaded(dao)
        assertEquals(1, RuleEngine.cachedCount)
        assertNotNull(RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 元 支付成功"))
    }

    @Test
    fun 金额正则全被安全闸门拒掉的规则整条丢弃() = runBlocking {
        val evil = noticeRule("user", "炸弹", "com.tencent.mm").copy(
            patternJson = """
                {"v":1,"type":"notice","app":"com.tencent.mm",
                 "amount":["(a+)+"],"expense":["支付成功"],"income":[],"block":[],"cats":{}}
            """.trimIndent(),
        )
        RuleEngine.ensureLoaded(FakeRuleDao(listOf(evil)))
        assertEquals("没有可用金额正则的规则不该进缓存", 0, RuleEngine.cachedCount)
    }

    @Test
    fun 部分正则被拒时其余正则仍然可用() = runBlocking {
        val mixed = noticeRule("user", "混合", "com.tencent.mm").copy(
            patternJson = """
                {"v":1,"type":"notice","app":"com.tencent.mm",
                 "amount":["(a+)+","微信支付\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"],
                 "expense":["支付成功"],"income":[],"block":[],"cats":{}}
            """.trimIndent(),
        )
        RuleEngine.ensureLoaded(FakeRuleDao(listOf(mixed)))
        assertEquals(1, RuleEngine.cachedCount)
        val hit = RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 12.34 支付成功")
        assertEquals(12.34, hit?.amount ?: 0.0, 0.001)
    }

    @Test
    fun 金额超出合理区间时不产出账单() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        // 9999999 超过 100 万上限，多半是「余额」而不是「本次消费」
        assertNull(RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", "微信支付 9999999 支付成功"))
    }

    @Test
    fun 空正文不匹配() = runBlocking {
        val dao = FakeRuleDao(listOf(noticeRule("user", "微信", "com.tencent.mm")))
        RuleEngine.ensureLoaded(dao)
        assertNull(RuleEngine.match(RuleType.NOTICE, "com.tencent.mm", ""))
    }

    @Test
    fun 新种子规则写入v2默认必填能力() {
        val rules = RuleSeed.systemRules()
        assertTrue(rules.isNotEmpty())
        rules.forEach { rule ->
            val json = JSONObject(rule.patternJson)
            assertEquals(2, json.getInt("v"))
            val necessary = json.getJSONArray("necessaryFields")
            assertEquals("amount", necessary.getString(0))
            assertEquals("direction", necessary.getString(1))
        }
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    /** 一条能匹配「微信支付 12.34 元 支付成功」的规则。 */
    private fun noticeRule(
        creator: String,
        name: String,
        app: String?,
        autoRecord: Boolean = true,
    ): RuleEntity = RuleEntity(
        id = RuleEntity.makeId(RuleType.NOTICE, app, name),
        app = app,
        type = RuleType.NOTICE,
        name = name,
        creator = creator,
        patternJson = """
            {"v":1,"type":"notice","app":${if (app == null) "null" else "\"$app\""},
             "amount":["微信支付\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"],
             "expense":["支付成功","付款成功"],
             "income":["收款到账","已入账"],
             "block":["优惠券"],
             "cats":{"消费":["支付成功"],"转账":["转账","转给"]}}
        """.trimIndent(),
        enabled = true,
        autoRecord = autoRecord,
        updatedAt = 0L,
    )

    /** 只实现引擎用到的那两个查询，其余给默认实现。 */
    private open class FakeRuleDao(private val rules: List<RuleEntity>) : RuleDao {

        /** 被调用 `getEnabledByCreator` 的次数，用来断言缓存是否真的生效。 */
        var loadCount = 0
            private set

        override suspend fun getAll(): List<RuleEntity> = rules

        override suspend fun getEnabledByCreator(creator: String): List<RuleEntity> {
            loadCount++
            return rules.filter { it.creator == creator && it.enabled }
        }

        override suspend fun getEnabledFor(type: String, app: String): List<RuleEntity> =
            rules.filter { it.enabled && it.type == type && (it.app == null || it.app == app) }

        override suspend fun getById(id: String): RuleEntity? = rules.firstOrNull { it.id == id }

        override suspend fun upsert(rule: RuleEntity) = Unit

        override suspend fun upsertAll(rules: List<RuleEntity>) = Unit

        override suspend fun insertIgnore(rules: List<RuleEntity>): List<Long> =
            rules.map { -1L }

        override suspend fun updateSystemRule(
            id: String,
            name: String,
            patternJson: String,
            updatedAt: Long,
        ): Int = 0

        override suspend fun delete(id: String) = Unit

        override suspend fun deleteUserRule(id: String): Int = 0

        override suspend fun count(): Int = rules.size
    }

    /** 第一次读失败、之后成功的 DAO，用来验证「失败不缓存」。 */
    private class FlakyRuleDao : FakeRuleDao(
        listOf(
            RuleEntity(
                id = RuleEntity.makeId(RuleType.NOTICE, "com.tencent.mm", "恢复后的规则"),
                app = "com.tencent.mm",
                type = RuleType.NOTICE,
                name = "恢复后的规则",
                creator = RuleEntity.CREATOR_USER,
                patternJson = """
                    {"v":1,"type":"notice","app":"com.tencent.mm",
                     "amount":["微信支付\\s*(\\d+(?:\\.\\d{1,2})?)"],
                     "expense":["支付成功"],"income":[],"block":[],"cats":{}}
                """.trimIndent(),
                enabled = true,
                autoRecord = true,
                updatedAt = 0L,
            ),
        ),
    ) {
        var fail = false

        override suspend fun getEnabledByCreator(creator: String): List<RuleEntity> {
            if (fail) throw IllegalStateException("数据库被锁")
            return super.getEnabledByCreator(creator)
        }
    }
}
