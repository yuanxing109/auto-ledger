package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class CategoryStoreTest {

    @Before
    fun resetCategoryStore() {
        CategoryStore.resetForTest()
    }

    @After
    fun clearCategoryStore() {
        CategoryStore.resetForTest()
    }

    @Test
    fun `持久化树保留自定义项并允许清空默认子分类`() {
        val tree = CategoryStore.decodeSubCategoryTree(
            """{"expense":{"餐饮":["外卖","夜宵","夜宵"],"教育":[]}}""",
        )

        assertEquals(listOf("外卖", "夜宵"), tree[0]?.get("餐饮"))
        assertEquals(emptyList<String>(), tree[0]?.get("教育"))
        assertTrue((tree[0]?.get("交通") ?: emptyList()).isNotEmpty())
        assertTrue((tree[1]?.get("转账") ?: emptyList()).isNotEmpty())
    }

    @Test
    fun `损坏的持久化树回退到默认分类`() {
        val tree = CategoryStore.decodeSubCategoryTree("not-json")

        assertEquals(CategoryStore.decodeSubCategoryTree(null), tree)
        assertTrue((tree[0]?.get("餐饮") ?: emptyList()).contains("奶茶咖啡"))
    }

    @Test
    fun `子分类名称会去除多余空白并拒绝空值`() {
        assertEquals("工作餐", CategoryStore.normalizeSubCategoryName("  工作餐  "))
        assertEquals("工作 餐", CategoryStore.normalizeSubCategoryName("工作   餐"))
        assertNull(CategoryStore.normalizeSubCategoryName("   "))
        assertTrue("餐饮" in CategoryStore.primaryCategories)
    }

    @Test
    fun `内置子分类与用户自定义子分类可以区分`() {
        val tree = CategoryStore.decodeSubCategoryTree(
            """{"expense":{"餐饮":["外卖","夜宵"]}}""",
        )

        val builtIn = CategoryStore.defaultSubCategories("餐饮")
        val custom = tree[0]?.get("餐饮").orEmpty().filterNot(builtIn::contains)
        assertEquals(listOf("夜宵"), custom)
    }

    @Test
    fun `保存手打的新子分类后自动联想源会包含它`() {
        CategoryStore.rememberForTest(
            type = 0,
            category = "餐饮",
            names = CategoryStore.subCategories(0, "餐饮") + "夜宵",
        )

        assertTrue("夜宵" in CategoryStore.subCategories(0, "餐饮"))
        assertTrue(CategoryStore.customSubCategories(0, "餐饮").contains("夜宵"))
    }
}
