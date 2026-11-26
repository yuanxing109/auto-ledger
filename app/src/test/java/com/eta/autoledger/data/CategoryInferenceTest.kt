package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

class CategoryInferenceTest {

    @Before
    fun resetCustomCategories() {
        CategoryStore.resetForTest()
    }

    @After
    fun clearCustomCategories() {
        CategoryStore.resetForTest()
    }

    @Test
    fun `美团外卖推断餐饮外卖`() {
        val hit = CategoryInference.infer("财付通-微信支付-美团外卖")

        assertEquals("餐饮", hit.category)
        assertEquals("外卖", hit.subCategory)
        assertEquals(MatchSource.MERCHANT, hit.source)
        assertEquals(100, hit.weight)
    }

    @Test
    fun `瑞幸咖啡推断奶茶咖啡`() {
        val hit = CategoryInference.infer("财付通-微信支付-瑞幸咖啡")

        assertEquals("餐饮", hit.category)
        assertEquals("奶茶咖啡", hit.subCategory)
        assertEquals(MatchSource.MERCHANT, hit.source)
    }

    @Test
    fun `美团买药推断医疗药店买药`() {
        val hit = CategoryInference.infer("支付宝-美团买药")

        assertEquals("医疗", hit.category)
        assertEquals("药店买药", hit.subCategory)
    }

    @Test
    fun `随礼转账推断人情社交且不伪造子分类`() {
        val hit = CategoryInference.infer("微信转账，备注：随礼")

        assertEquals("人情社交", hit.category)
        assertNull(hit.subCategory)
        assertEquals(MatchSource.KEYWORD, hit.source)
    }

    @Test
    fun `交通大厦快餐被排除词纠正为餐饮`() {
        val hit = CategoryInference.infer("交通大厦一楼快餐店消费")

        assertEquals("餐饮", hit.category)
        assertEquals("早中晚餐", hit.subCategory)
    }

    @Test
    fun `京东机票推断旅行的火车飞机`() {
        val hit = CategoryInference.infer("京东机票预订")

        assertEquals("旅行", hit.category)
        assertEquals("火车飞机", hit.subCategory)
    }

    @Test
    fun `美团酒店推断旅行酒店住宿`() {
        val hit = CategoryInference.infer("财付通-微信支付-美团酒店预订")

        assertEquals("旅行", hit.category)
        assertEquals("酒店住宿", hit.subCategory)
    }

    @Test
    fun `盒马日用品推断购物而不被水果语义覆盖`() {
        val daily = CategoryInference.infer("支付宝-盒马鲜生-日用品")
        val fruit = CategoryInference.infer("支付宝-盒马鲜生-草莓果切")

        assertEquals("购物", daily.category)
        assertEquals("超市便利", daily.subCategory)
        assertEquals("餐饮", fruit.category)
        assertEquals("水果零食", fruit.subCategory)
    }

    @Test
    fun `还款保持转账主分类且视频命中二级分类`() {
        val repayment = CategoryInference.infer("微信转账，备注：还款")
        val video = CategoryInference.infer("腾讯视频会员充值")

        assertEquals("转账", repayment.category)
        assertNull(repayment.subCategory)
        assertEquals("娱乐", video.category)
        assertEquals("视频会员", video.subCategory)
    }

    @Test
    fun `商家红包抵扣不能压过后续精确商户`() {
        val hit = CategoryInference.infer("商家红包抵扣，瑞幸咖啡")

        assertEquals("餐饮", hit.category)
        assertEquals("奶茶咖啡", hit.subCategory)
    }

    @Test
    fun `同权重跨分类命中回退其他`() {
        val hit = CategoryInference.infer("咖啡 打车")

        assertEquals("其他", hit.category)
        assertNull(hit.subCategory)
        assertEquals(MatchSource.FALLBACK, hit.source)
        assertEquals(10, hit.weight)
    }

    @Test
    fun `用户禁用高权重分类后回退当前启用的其他分类`() {
        val hit = CategoryInference.infer("瑞幸咖啡", listOf("其他", "交通"))

        assertEquals("其他", hit.category)
        assertNull(hit.subCategory)
        assertEquals(MatchSource.FALLBACK, hit.source)
    }

    @Test
    fun `交通银行不能命中交通规则`() {
        val hit = CategoryInference.infer("交通银行营业网点")

        assertEquals("其他", hit.category)
        assertEquals(MatchSource.FALLBACK, hit.source)
    }

    @Test
    fun `交通作为独立词时仍可命中低权重规则`() {
        val hit = CategoryInference.infer("交通 支出")

        assertEquals("交通", hit.category)
        assertNull(hit.subCategory)
        assertEquals(MatchSource.KEYWORD, hit.source)
        assertEquals(40, hit.weight)
    }

    @Test
    fun `短商户与数字商户不会从无关文本中误命中`() {
        val district = CategoryInference.infer("北京东城区消费100元")
        val card = CategoryInference.infer("您尾号12306的卡支出100元")

        assertEquals("消费", district.category)
        assertEquals("其他", card.category)
        assertEquals(MatchSource.FALLBACK, card.source)
    }

    @Test
    fun `无歧义旧业务词仍保持原分类`() {
        val hotpot = CategoryInference.infer("火锅 200元 付款成功")
        val transfer = CategoryInference.infer("转给张三100元")
        val cinema = CategoryInference.infer("电影院 80元")
        val noodles = CategoryInference.infer("兰州拉面 20元 付款成功")
        val household = CategoryInference.infer("无印良品 99元 付款成功")

        assertEquals("餐饮", hotpot.category)
        assertEquals("早中晚餐", hotpot.subCategory)
        assertEquals("转账", transfer.category)
        assertEquals("娱乐", cinema.category)
        assertEquals("文娱演出", cinema.subCategory)
        assertEquals("餐饮", noodles.category)
        assertEquals("早中晚餐", noodles.subCategory)
        assertEquals("购物", household.category)
        assertEquals("日用品", household.subCategory)
    }

    @Test
    fun `无参数入口覆盖其他账户动作分类`() {
        val withdrawal = CategoryInference.infer("零钱提现 100元")

        assertEquals("提现", withdrawal.category)
        assertNull(withdrawal.subCategory)
    }

    @Test
    fun `用户自定义子分类在文本命中时覆盖默认子分类`() {
        val hit = CategoryInference.infer(
            text = "瑞幸咖啡 夜宵 32元",
            allowedCategories = listOf("其他", "餐饮"),
            customSubCategoryOwners = mapOf("夜宵" to setOf("餐饮")),
        )

        assertEquals("餐饮", hit.category)
        assertEquals("夜宵", hit.subCategory)
        assertEquals(MatchSource.MERCHANT, hit.source)
        assertEquals(100, hit.weight)
    }

    @Test
    fun `用户自定义分类名可细化任意已命中主分类`() {
        val hit = CategoryInference.infer(
            text = "美团外卖 夜宵 32元",
            allowedCategories = listOf("其他", "餐饮"),
            customSubCategoryOwners = mapOf("夜宵" to setOf("餐饮")),
        )

        assertEquals("餐饮", hit.category)
        assertEquals("夜宵", hit.subCategory)
    }

    @Test
    fun `用户自定义子分类不能把主分类带到其他分类`() {
        val hit = CategoryInference.infer(
            text = "夜宵 32元",
            allowedCategories = listOf("其他", "餐饮"),
            customSubCategoryOwners = mapOf("夜宵" to setOf("餐饮")),
        )

        assertEquals("其他", hit.category)
        assertNull(hit.subCategory)
        assertEquals(MatchSource.FALLBACK, hit.source)
    }

    @Test
    fun `用户自定义子分类只在其归属主分类命中时生效`() {
        val hit = CategoryInference.infer(
            text = "瑞幸咖啡 夜宵 32元",
            allowedCategories = listOf("其他", "餐饮"),
            customSubCategoryOwners = mapOf("夜宵" to setOf("交通")),
        )

        assertEquals("餐饮", hit.category)
        assertEquals("奶茶咖啡", hit.subCategory)
    }
}
