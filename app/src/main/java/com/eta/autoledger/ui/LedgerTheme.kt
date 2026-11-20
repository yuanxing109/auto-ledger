package com.eta.autoledger.ui

import androidx.compose.ui.graphics.Color

/**
 * 项目自己的语义色板 —— 全局唯一的取色入口。
 *
 * Miuix 的 `MiuixTheme.colorScheme` 只覆盖它自带的组件，而本项目的界面大量是
 * 手绘背景 + 自绘卡片 + Canvas 图表，所以额外提供这一层语义色。
 *
 * 为什么用「可变全局标记」而不是 `@Composable get()`：
 * 这些颜色既要在 Composable 里用，也要在 `Canvas` 的 DrawScope、以及函数的
 * 默认参数里用；只有普通属性 getter 才能同时满足。`dark` 由 [LedgerApp] 在每次
 * 重组时写入，主题切换会触发整棵树重组，读到新值。
 *
 * 配色规范见 `docs/06-UI统一改造方案.md` §2.1。两条硬规则：
 * 1. 深色底不用纯黑（`#000000` 会让白字发光），必须近黑分档：page < inset < surface < surfaceVariant；
 * 2. 深色禁用阴影（近黑上不可见），一律用 [border] 1dp 描边分层。
 */
object LedgerTheme {

    /** 当前是否深色。由 LedgerApp 在组装期同步，其他代码只读。 */
    var dark: Boolean = false
        internal set

    /** 兼容旧调用：返回当前生效（可能来自系统）的深色状态。 */
    fun isDark(): Boolean = dark

    // ---------------- 表面层次 ----------------

    /** 页面底色。浅色用冷灰白（不用纯白），深色用近黑。 */
    val page: Color get() = if (dark) Color(0xFF0B0F14) else Color(0xFFF5F7FB)

    /** 卡片 / 顶栏 / 弹层底色（深色里是第一层提亮）。 */
    val surface: Color get() = if (dark) Color(0xFF141A21) else Color(0xFFFFFFFF)

    /** 兼容旧名：等同于 [surface]。 */
    val card: Color get() = surface

    /** 卡片内部再压一层：输入框、键帽、次级卡片（深色里是第二层提亮）。 */
    val surfaceVariant: Color get() = if (dark) Color(0xFF1B232C) else Color(0xFFEEF2F9)

    /** 卡内的「凹槽」块（比卡片更暗，表达下沉）。 */
    val inset: Color get() = if (dark) Color(0xFF10161D) else Color(0xFFF0F4FA)

    /** 卡片描边。深色靠它分层，不靠阴影。 */
    val border: Color get() = if (dark) Color(0xFF242E39) else Color(0xFFE4EAF3)

    /** 兼容旧名：等同于 [border]。 */
    val cardBorder: Color get() = border

    /** 分割线（比描边更轻）。 */
    val divider: Color get() = if (dark) Color(0xFF1E262F) else Color(0xFFEDF1F7)

    /** 弹层遮罩。 */
    val scrim: Color get() = if (dark) Color(0xB3000000) else Color(0x990B1220)

    // ---------------- 文字 ----------------

    /** 主要文字、支出金额。 */
    val text: Color get() = if (dark) Color(0xFFE8EDF3) else Color(0xFF14181F)

    /** 副标题、标签。 */
    val textSecondary: Color get() = if (dark) Color(0xFFA3AEBD) else Color(0xFF5B6675)

    /** 占位符、辅助说明（仅 ≥15sp 或图标）。 */
    val textTertiary: Color get() = if (dark) Color(0xFF7A8798) else Color(0xFF8D97A6)

    // ---------------- 语义色 ----------------

    /** 主色：选中态、主按钮、链接。深色下必须比浅色版更亮，否则会"沉"下去。 */
    val accent: Color get() = if (dark) Color(0xFF6D9BFF) else Color(0xFF2563EB)

    /** 主色按下态 / 渐变末端。 */
    val accentPressed: Color get() = if (dark) Color(0xFF5A88F0) else Color(0xFF1D4FD7)

    /** 主色上的文字。深色主色偏亮，所以上面压深色字而不是白字。 */
    val onAccent: Color get() = if (dark) Color(0xFF06101F) else Color(0xFFFFFFFF)

    /** 收入金额、正数。 */
    val income: Color get() = if (dark) Color(0xFF34D399) else Color(0xFF0B8A5F)

    /** 支出金额（与正文同色，靠符号与字重区分；红色只留给异常）。 */
    val expense: Color get() = text

    /** 负数对比、删除、错误。 */
    val danger: Color get() = if (dark) Color(0xFFF87171) else Color(0xFFDC2626)

    /** 预算超支等警示。 */
    val warning: Color get() = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)

    /** 分类/来源标签的淡底（配合 [income]、[accent] 等前景色使用）。 */
    fun tint(foreground: Color, alpha: Float = 0.12f): Color =
        foreground.copy(alpha = if (dark) alpha + 0.04f else alpha)

    // ---------------- 图表系列色 ----------------

    val chart1: Color get() = if (dark) Color(0xFF6D9BFF) else Color(0xFF2563EB)
    val chart2: Color get() = if (dark) Color(0xFF34D399) else Color(0xFF0B8A5F)
    val chart3: Color get() = if (dark) Color(0xFFFBBF24) else Color(0xFFF59E0B)
    val chart4: Color get() = if (dark) Color(0xFFA78BFA) else Color(0xFF8B5CF6)
    val chart5: Color get() = if (dark) Color(0xFF22D3EE) else Color(0xFF06B6D4)
    val chart6: Color get() = if (dark) Color(0xFFF87171) else Color(0xFFEF4444)

    /** 图表系列整体（环形图、堆叠柱按顺序取）。 */
    val chartSeries: List<Color>
        get() = listOf(chart1, chart2, chart3, chart4, chart5, chart6)
}