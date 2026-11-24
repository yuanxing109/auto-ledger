package com.eta.autoledger.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 解析总入口的断言：动态规则优先、内置规则兜底、两条路的产出不互相污染。
 *
 * 这里测的是**编排**，不是正则细节 —— 正则细节由 [RuleEngineTest] 与
 * `ParseDedupTest` 覆盖。编排错了的表现是「规则配了但不生效」或「规则一坏就整体失记」，
 * 这两种都是用户能立刻感知、却极难从日志反推的故障。
 */
class ParsePipelineTest {

    @Before
    fun reset() {
        RuleEngine.resetForTest()
        ParseDedup.clear()
        PendingMap.clearForTest()
    }

    // ── 动态规则优先 ──────────────────────────────────────────────────

    @Test
    fun 动态规则命中时不走内置逻辑() = runBlocking {
        val dao = FakeRuleDao(listOf(userRule("我的微信", "com.tencent.mm")))
        val bill = ParsePipeline.parse(
            raw = wechatRaw("微信支付 12.34 支付成功"),
            cats(),
            dao = dao,
        )
        assertNotNull(bill)
        assertEquals(12.34, bill!!.amount, 0.001)
        // 动态规则的 externalId 带 rul 前缀，用来与内置的 ntf / sms 区分
        assertTrue("规则产出的 externalId 应以 rul 开头：${bill.externalId}", bill.externalId.startsWith("rul"))
    }

    @Test
    fun 动态规则的分类来自规则而不是内置词表() = runBlocking {
        // 内置词表里「支付成功」会落到「消费」，这条规则明确写的是「转账」
        val dao = FakeRuleDao(listOf(userRule("我的微信", "com.tencent.mm", category = "转账")))
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功 转账"), cats(), dao = dao)
        assertEquals("转账", bill?.category)
    }

    @Test
    fun 动态规则可跨行提取折叠通知金额() = runBlocking {
        val rule = RuleEntity(
            id = RuleEntity.makeId(RuleType.NOTICE, "com.tencent.mm", "跨行规则"),
            app = "com.tencent.mm",
            type = RuleType.NOTICE,
            name = "跨行规则",
            creator = RuleEntity.CREATOR_USER,
            patternJson = """
                {"v":2,"type":"notice","app":"com.tencent.mm",
                 "amount":["转账金额.*?(\\d+(?:\\.\\d{1,2})?)"],
                 "expense":["支付成功"],"income":[],"block":[],"cats":{},
                 "necessaryFields":["amount","direction"]}
            """.trimIndent(),
            enabled = true,
            autoRecord = true,
            updatedAt = 0L,
        )
        val raw = NotifyRaw(
            pkg = "com.tencent.mm",
            title = "微信",
            text = "",
            bigText = "转账金额\n12.34\n支付成功",
            postTime = 1_700_000_000_000,
        )
        val bill = ParsePipeline.parse(raw = raw, cats(), dao = FakeRuleDao(listOf(rule)))
        assertNotNull("换行文本应被 DOTALL 动态规则识别", bill)
        assertEquals(12.34, bill!!.amount, 0.001)
    }

    @Test
    fun 规则里的分类被用户删掉时回退到其他而不是丢账() = runBlocking {
        val dao = FakeRuleDao(listOf(userRule("我的微信", "com.tencent.mm", category = "已删除的分类")))
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = dao)
        assertNotNull("金额与方向都是对的，不该因为分类不存在就丢账", bill)
        assertEquals("其他", bill?.category)
    }

    // ── 内置兜底 ──────────────────────────────────────────────────────

    @Test
    fun 规则表为空时内置逻辑照常工作() = runBlocking {
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = FakeRuleDao(emptyList()))
        assertNotNull("规则表为空不该让自动记账整体失效", bill)
        assertEquals(12.34, bill!!.amount, 0.001)
        assertTrue(bill.externalId.startsWith("ntf"))
    }

    @Test
    fun dao为null时只走内置逻辑() = runBlocking {
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = null)
        assertNotNull(bill)
        assertTrue(bill!!.externalId.startsWith("ntf"))
    }

    @Test
    fun 规则加载抛异常时回退内置逻辑() = runBlocking {
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = ThrowingRuleDao())
        assertNotNull("规则表读失败不能让记账失效", bill)
        assertTrue(bill!!.externalId.startsWith("ntf"))
    }

    @Test
    fun 动态规则没命中时回退内置逻辑() = runBlocking {
        // 规则包名对不上，动态路必然落空
        val dao = FakeRuleDao(listOf(userRule("支付宝", "com.eg.android.AlipayGphone")))
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = dao)
        assertNotNull(bill)
        assertTrue(bill!!.externalId.startsWith("ntf"))
    }

    // ── 用户词库的强制与忽略 ──────────────────────────────────────────

    @Test
    fun 忽略词命中时连动态规则也不跑() = runBlocking {
        val dao = FakeRuleDao(listOf(userRule("我的微信", "com.tencent.mm")))
        val bill = ParsePipeline.parse(
            raw = wechatRaw("微信支付 12.34 支付成功"),
            cats(),
            ignoreKeywords = setOf("微信支付"),
            dao = dao,
        )
        assertNull(bill)
    }

    @Test
    fun 强制词命中时压过忽略词() = runBlocking {
        val dao = FakeRuleDao(listOf(userRule("我的微信", "com.tencent.mm")))
        val bill = ParsePipeline.parse(
            raw = wechatRaw("微信支付 12.34 支付成功"),
            cats(),
            ignoreKeywords = setOf("微信支付"),
            forceKeywords = setOf("支付成功"),
            dao = dao,
        )
        assertNotNull(bill)
    }

    // ── 自动记账开关 ──────────────────────────────────────────────────

    @Test
    fun 规则要求不自动记账时结果标记为false() = runBlocking {
        val dao = FakeRuleDao(listOf(userRule("我的微信", "com.tencent.mm", autoRecord = false)))
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = dao)
        assertNotNull("认得出来就该返回结果，是否入库由 autoRecord 决定", bill)
        assertEquals(false, bill?.autoRecord)
    }

    @Test
    fun 内置规则产出的一律自动记账() = runBlocking {
        val bill = ParsePipeline.parse(raw = wechatRaw("微信支付 12.34 支付成功"), cats(), dao = null)
        assertEquals(true, bill?.autoRecord)
    }

    // ── 短信 ──────────────────────────────────────────────────────────

    @Test
    fun 短信动态规则命中() = runBlocking {
        val dao = FakeRuleDao(listOf(smsRule("我的银行短信")))
        val bill = ParsePipeline.parseSms(
            sender = "95555",
            body = "您尾号1234的卡消费人民币100.00元",
            postTime = 1_700_000_000_000,
            cats(),
            dao = dao,
        )
        assertNotNull(bill)
        assertEquals(100.00, bill!!.amount, 0.001)
        assertTrue(bill.externalId.startsWith("ruls"))
    }

    @Test
    fun 短信发送方闸门在动态规则之前生效() = runBlocking {
        // 106 通道 + 正文无金融词 → 内置闸门就拦掉，动态规则也不该有机会命中
        val dao = FakeRuleDao(listOf(smsRule("我的银行短信")))
        val bill = ParsePipeline.parseSms(
            sender = "10690001",
            body = "您尾号1234的卡消费人民币100.00元",
            postTime = 1_700_000_000_000,
            cats(),
            dao = dao,
        )
        // 正文里有「消费」「尾号」这些金融词，闸门放行 —— 这条断言确认闸门是「正文金融词」而不是「一律拦 106」
        assertNotNull(bill)
    }

    @Test
    fun 纯营销短信被闸门挡住() = runBlocking {
        val dao = FakeRuleDao(listOf(smsRule("我的银行短信")))
        val bill = ParsePipeline.parseSms(
            sender = "10690001",
            body = "限时活动 消费满100减50 快来领取",
            postTime = 1_700_000_000_000,
            cats(),
            dao = dao,
        )
        // 「消费」是金融词，闸门会放行；真正拦住它的是规则里的 block 词与内置的未消费拦截。
        // 这里只断言「不会产出 100 元账单」这一结果，不限定由哪一层拦住。
        assertTrue("营销短信不该记账：${bill?.amount}", bill == null || bill.amount != 100.0)
    }


    // ── 收支方向：「向您支付」是进账，不是支出 ──────────────────────────

    /** 真实误判样本：企业代付服务费，句子里「支付」「服务费」「代扣」全是支出词。 */
    private val inwardSms =
        "【天津新元信息技术有限公司】钟元兴先生/女士，天津新元信息技术有限公司向您支付了" +
            "深圳市起点云电子商务有限公司项目服务费19.40元，我司将按国家规定进行个人所得税代扣申报。" +
            "您在次月底可在“个人所得税”App查询，详询4000051667"

    @Test
    fun 内核把向您支付判为收入而不是支出() = runBlocking {
        val bill = ParsePipeline.parseSms(
            sender = "95588",
            body = inwardSms,
            postTime = 1_700_000_000_000,
            cats = cats(),
            dao = null,
        )
        assertNotNull("这条短信应该被识别", bill)
        assertEquals(19.40, bill!!.amount, 0.001)
        assertEquals("「向您支付」的受益者是我，钱是进来的", "income", bill.type)
        // 收入侧不能落进「消费」：那条分类只在支出表里，落进去说明方向又反了
        assertTrue("收入不该归到消费，实际是 ${bill.category}", bill.category != "消费")
    }

    @Test
    fun 内置的向您支付规则写死收入方向() = runBlocking {
        val systemRules = RuleSeed.systemRules()
        assertEquals("内置规则应保持为可验收的 4 条", 4, systemRules.size)
        val rule = systemRules.firstOrNull { it.name == "向您支付进账" }
        assertNotNull("内置规则里缺少「向您支付进账」", rule)
        assertTrue("这条规则必须写死 direction=income", rule!!.patternJson.contains("\"direction\":\"income\""))

        val bill = ParsePipeline.parseSms(
            sender = "95588",
            body = inwardSms,
            postTime = 1_700_000_000_000,
            cats = cats(),
            dao = FakeRuleDao(listOf(rule)),
        )
        assertNotNull(bill)
        assertEquals(19.40, bill!!.amount, 0.001)
        assertEquals("income", bill.type)
        // 走的是动态规则（rul 前缀），不是内核兜底
        assertTrue("应命中动态规则：${bill.externalId}", bill.externalId.startsWith("ruls"))
    }

    @Test
    fun 内核的强制进账兜底也覆盖给您打款() = runBlocking {
        val bill = ParsePipeline.parseSms(
            sender = "95588",
            body = "【某公司】公司给您打款5000.00元，已代扣个人所得税，请您留意到账。",
            postTime = 1_700_000_000_000,
            cats = cats(),
            dao = null,
        )
        assertNotNull("「给您打款」应被识别为收入", bill)
        assertEquals(5000.00, bill!!.amount, 0.001)
        assertEquals("income", bill.type)
    }


    @Test
    fun 企业短信从106通道发来也能识别为收入() = runBlocking {
        // 106 开头的通道号默认被发送方闸门挡住，只有正文带金融词才放行；
        // 「向您支付」「服务费」必须算金融词，否则这条真实的代付短信会被整条丢掉。
        val bill = ParsePipeline.parseSms(
            sender = "106903330000",
            body = inwardSms,
            postTime = 1_700_000_000_000,
            cats = cats(),
            dao = null,
        )
        assertNotNull("106 通道的企业代付短信不该被发送方闸门挡掉", bill)
        assertEquals(19.40, bill!!.amount, 0.001)
        assertEquals("income", bill.type)
    }

    // ── 两阶段确认 ──────────────────────────────────────────────────────

    @Test
    fun 未支付下单只进入PendingMap不生成账单() = runBlocking {
        val bill = ParsePipeline.parse(
            raw = NotifyRaw(
                pkg = "com.tencent.mm",
                title = "订单通知",
                text = "订单已生成 12.34元，待支付",
                bigText = "",
                postTime = 1_700_000_000_000,
            ),
            cats(),
            dao = null,
        )

        assertNull("待支付订单不能直接生成账单", bill)
        assertEquals("待确认订单只应挂起一条", 1, PendingMap.size())
    }

    @Test
    fun 五分钟内支付成功会合并Pending信息并清空挂起项() = runBlocking {
        val rawPending = wechatRaw("微信支付 12.34元 下单成功，订单商家：老王面馆")
        assertNull(
            "下单阶段不能直接记成已支付",
            ParsePipeline.parse(raw = rawPending, cats(), dao = null),
        )

        val confirmed = ParsePipeline.parse(
            raw = wechatRaw("微信支付 12.34元 支付成功"),
            cats(),
            dao = null,
        )
        assertNotNull(confirmed)
        assertEquals(12.34, confirmed!!.amount, 0.001)
        assertTrue("应保留下单阶段的商户信息", confirmed.note.contains("老王面馆"))
        assertEquals("确认后必须消费 pending", 0, PendingMap.size())
    }

    @Test
    fun 分类列表为空时回退其他而不是丢账() = runBlocking {
        val bill = ParsePipeline.parse(
            raw = wechatRaw("微信支付 12.34 元 支付成功"),
            cats = Triple(emptyList(), emptyList(), emptyList()),
            dao = null,
        )
        assertNotNull("分类缺失不能导致整笔账单丢失", bill)
        assertEquals("其他", bill!!.category)
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    private fun cats(): Triple<List<String>, List<String>, List<String>> = Triple(
        listOf("其他", "消费", "转账", "餐饮", "购物"),
        listOf("转账", "其他", "退款"),
        listOf("提现", "还款"),
    )

    private fun ParsePipeline.parse(
        raw: NotifyRaw,
        cats: Triple<List<String>, List<String>, List<String>>,
        ignoreKeywords: Set<String> = emptySet(),
        forceKeywords: Set<String> = emptySet(),
        dao: RuleDao? = null,
    ): NotifyBill? = runBlocking {
        parse(
            raw = raw,
            expenseCats = cats.first,
            incomeCats = cats.second,
            otherCats = cats.third,
            ignoreKeywords = ignoreKeywords,
            forceKeywords = forceKeywords,
            dao = dao,
        )
    }

    private fun ParsePipeline.parseSms(
        sender: String,
        body: String,
        postTime: Long,
        cats: Triple<List<String>, List<String>, List<String>>,
        dao: RuleDao? = null,
    ): NotifyBill? = runBlocking {
        parseSms(
            sender = sender,
            body = body,
            postTime = postTime,
            expenseCats = cats.first,
            incomeCats = cats.second,
            otherCats = cats.third,
            dao = dao,
        )
    }

    private fun wechatRaw(body: String) = NotifyRaw(
        pkg = "com.tencent.mm",
        title = "微信支付",
        text = body,
        bigText = "",
        postTime = 1_700_000_000_000,
    )

    private fun userRule(
        name: String,
        app: String?,
        category: String = "消费",
        autoRecord: Boolean = true,
    ): RuleEntity = RuleEntity(
        id = RuleEntity.makeId(RuleType.NOTICE, app, name),
        app = app,
        type = RuleType.NOTICE,
        name = name,
        creator = RuleEntity.CREATOR_USER,
        patternJson = """
            {"v":1,"type":"notice","app":${if (app == null) "null" else "\"$app\""},
             "amount":["微信支付\\s*(\\d+(?:\\.\\d{1,2})?)"],
             "expense":["支付成功"],"income":["收款到账"],"block":[],
             "cats":{"$category":["支付成功"]}}
        """.trimIndent(),
        enabled = true,
        autoRecord = autoRecord,
        updatedAt = 0L,
    )

    private fun smsRule(name: String): RuleEntity = RuleEntity(
        id = RuleEntity.makeId(RuleType.SMS, null, name),
        app = null,
        type = RuleType.SMS,
        name = name,
        creator = RuleEntity.CREATOR_USER,
        patternJson = """
            {"v":1,"type":"sms","app":null,
             "amount":["消费人民币\\s*(\\d+(?:\\.\\d{1,2})?)"],
             "expense":["消费"],"income":["入账"],"block":["验证码"],
             "cats":{"消费":["消费"]}}
        """.trimIndent(),
        enabled = true,
        autoRecord = true,
        updatedAt = 0L,
    )

    private class FakeRuleDao(private val rules: List<RuleEntity>) : RuleDao {
        override suspend fun getAll(): List<RuleEntity> = rules
        override suspend fun getEnabledByCreator(creator: String): List<RuleEntity> =
            rules.filter { it.creator == creator && it.enabled }
        override suspend fun upsert(rule: RuleEntity) = Unit
        override suspend fun insertIgnore(rules: List<RuleEntity>): List<Long> = rules.map { -1L }
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteUserRule(id: String): Int = 0
        override suspend fun count(): Int = rules.size
    }

    private class ThrowingRuleDao : RuleDao {
        override suspend fun getAll(): List<RuleEntity> = throw IllegalStateException("表损坏")
        override suspend fun getEnabledByCreator(creator: String): List<RuleEntity> =
            throw IllegalStateException("表损坏")
        override suspend fun upsert(rule: RuleEntity) = throw IllegalStateException("表损坏")
        override suspend fun insertIgnore(rules: List<RuleEntity>): List<Long> =
            throw IllegalStateException("表损坏")
        override suspend fun delete(id: String) = throw IllegalStateException("表损坏")
        override suspend fun deleteUserRule(id: String): Int = throw IllegalStateException("表损坏")
        override suspend fun count(): Int = throw IllegalStateException("表损坏")
    }
}
