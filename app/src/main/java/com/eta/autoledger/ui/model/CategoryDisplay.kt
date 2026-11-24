package com.eta.autoledger.ui.model

/**
 * 列表中的分类展示文案。
 *
 * 子分类为空、空白或 null 时只显示主分类，兼容 v5 迁移前遗留的历史账单。
 * 非空子分类统一去除首尾空白，并以中点连接，供首页和搜索列表复用。
 */
fun formatCategoryLabel(category: String, subCategory: String?): String {
    val child = subCategory?.trim().orEmpty()
    return if (child.isEmpty()) category else "$category · $child"
}
