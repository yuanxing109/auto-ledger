package com.eta.autoledger.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryDisplayTest {

    @Test
    fun `空子分类只显示主分类`() {
        assertEquals("餐饮", formatCategoryLabel("餐饮", null))
        assertEquals("餐饮", formatCategoryLabel("餐饮", ""))
        assertEquals("餐饮", formatCategoryLabel("餐饮", "   "))
    }

    @Test
    fun `非空子分类使用中点连接并去除首尾空白`() {
        assertEquals("餐饮 · 外卖", formatCategoryLabel("餐饮", "外卖"))
        assertEquals("餐饮 · 堂食", formatCategoryLabel("餐饮", " 堂食 "))
    }

    @Test
    fun `主分类内容保持不变`() {
        assertEquals("其他", formatCategoryLabel("其他", null))
        assertEquals("自定义分类 · 子项", formatCategoryLabel("自定义分类", "子项"))
    }
}
