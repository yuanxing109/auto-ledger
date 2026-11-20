package com.eta.autoledger.ui

import androidx.compose.ui.graphics.Color

/**
 * 项目自己的语义色板。
 *
 * Miuix 的 `MiuixTheme.colorScheme` 只覆盖它自带的组件，而本项目的界面大量是
 * 手绘背景 + 自绘卡片 + Canvas 图表，所以额外提供这一层语义色。
 *
 * 为什么用「可变全局标记」而不是 `@Composable get()`：
 * 这些颜色既要在 Composable 里用，也要在 `Canvas` 的 DrawScope、以及函数的
 * 默认参数里用；只有普通属性 getter 才能同时满足。`dark` 由 [LedgerApp] 在每次
 * 重组时写入，主题切换会触发整棵树重组，读到新值。
 */
object LedgerTheme {

    /** 当前是否深色。由 LedgerApp 在组装期同步，其他代码只读。 */
    var dark: Boolean = false
        internal set

    /** 兼容旧调用：返回当前生效（可能来自系统）的深色状态。 */
    fun isDark(): Boolean = dark

    val page: Color get() = if (dark) Color(0xFF0D1117) else Color(0xFFF2F7FF)
    val card: Color get() = if (dark) Color(0xFF161B22) else Color.White
    val cardBorder: Color get() = if (dark) Color(0xFF232A33) else Color(0xFFE1ECFF)
    val text: Color get() = if (dark) Color(0xFFE6EAF0) else Color(0xFF1B1C1E)
    val textSecondary: Color get() = if (dark) Color(0xFF9AA5B4) else Color(0xFF8A94A6)
    val accent: Color get() = if (dark) Color(0xFF6D9BFF) else Color(0xFF2F6BFF)
    val income: Color get() = if (dark) Color(0xFF34D399) else Color(0xFF159B6B)
    val divider: Color get() = if (dark) Color(0xFF232A33) else Color(0xFFEDF1F8)

    /** 卡片内部再压一层的输入框/浅底。 */
    val inset: Color get() = if (dark) Color(0xFF0F141B) else Color(0xFFF7FAFF)
}