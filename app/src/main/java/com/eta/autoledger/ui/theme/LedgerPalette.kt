package com.eta.autoledger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 一套完整的语义色（浅色或深色）。
 *
 * 这是全项目**唯一的色值真源**：`LedgerPalette.light` / `LedgerPalette.dark` 就是
 * `docs/06-UI统一改造方案.md` §2.1 两张色值表的代码化。任何地方要新增颜色，
 * 先加字段到这里，再在 `LedgerPalette` 两组实例里给值。
 *
 * 命名对应关系（与 Miuix 53 个槽位的映射见 `LedgerMiuixColors.kt`）：
 * - [page] → Miuix `background`
 * - [surface] → Miuix `surface` / `surfaceContainer`
 * - [surfaceVariant] → Miuix `surfaceVariant` / `surfaceContainerHigh`
 * - [inset] → Miuix `surfaceContainerVariant`
 * - [border] → Miuix `outline`
 * - [divider] → Miuix `dividerLine`
 * - [text] / [textSecondary] / [textTertiary] → Miuix `onBackground` / `onSurfaceSecondary` / `onSurfaceVariantActions`
 * - [accent] / [accentPressed] / [onAccent] → Miuix `primary` / `primaryVariant` / `onPrimary`
 * - [income] → Miuix `tertiaryContainer` 的 `onTertiaryContainer`
 * - [danger] → Miuix `error`
 */
@Immutable
data class LedgerColors(
    // ---- 表面层次：page < inset < surface < surfaceVariant ----
    val page: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val inset: Color,
    // ---- 线 ----
    val border: Color,
    val divider: Color,
    val scrim: Color,
    // ---- 文字 ----
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // ---- 强调 ----
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    // ---- 语义 ----
    val income: Color,
    val danger: Color,
    val warning: Color,
    // ---- 图表（不参与 Miuix 映射，只给 MPChart / Canvas 用）----
    val chart1: Color,
    val chart2: Color,
    val chart3: Color,
    val chart4: Color,
    val chart5: Color,
    val chart6: Color,
) {
    /** 图表调色板，顺序稳定（分类构成图的颜色顺序依赖它）。 */
    val charts: List<Color> get() = listOf(chart1, chart2, chart3, chart4, chart5, chart6)
}

/**
 * 两套色板。
 *
 * 为什么不用 `isSystemInDarkTheme()` 在这里判断：色板必须能在 `Canvas` 的 DrawScope、
 * 函数的默认参数、以及 `RemoteViews` 之外的任何上下文里取到，所以只能由调用方告知
 * 当前模式。当前模式的唯一来源是 [com.eta.autoledger.ui.theme.AppTheme]。
 */
object LedgerPalette {

    /** 浅色：冷灰白底 + 白卡 + 蓝强调。不用纯白做页面底，避免大面积高亮刺眼。 */
    val light: LedgerColors = LedgerColors(
        page = Color(0xFFF5F7FB),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEEF2F9),
        inset = Color(0xFFF0F4FA),
        border = Color(0xFFE4EAF3),
        divider = Color(0xFFEDF1F7),
        scrim = Color(0x990B1220),
        text = Color(0xFF14181F),
        textSecondary = Color(0xFF5B6675),
        textTertiary = Color(0xFF8D97A6),
        accent = Color(0xFF2563EB),
        accentPressed = Color(0xFF1D4FD7),
        onAccent = Color(0xFFFFFFFF),
        income = Color(0xFF0B8A5F),
        danger = Color(0xFFDC2626),
        warning = Color(0xFFB45309),
        chart1 = Color(0xFF2563EB),
        chart2 = Color(0xFF0B8A5F),
        chart3 = Color(0xFFF59E0B),
        chart4 = Color(0xFF8B5CF6),
        chart5 = Color(0xFF06B6D4),
        chart6 = Color(0xFFEF4444),
    )

    /**
     * 深色：近黑分档 `page < inset < surface < surfaceVariant`。
     *
     * 不用 `#000000`：纯黑与卡片 `#141A21` 的强对比会让滚动时数字「抖动」（OLED 亮度
     * 切换更明显），而且近黑底上的阴影完全不可见，所以深色一律靠 `border` 描边分层。
     */
    val dark: LedgerColors = LedgerColors(
        page = Color(0xFF0B0F14),
        surface = Color(0xFF141A21),
        surfaceVariant = Color(0xFF1B232C),
        inset = Color(0xFF10161D),
        border = Color(0xFF242E39),
        divider = Color(0xFF1E262F),
        scrim = Color(0xB30B1220),
        text = Color(0xFFE8EDF3),
        textSecondary = Color(0xFFA3AEBD),
        textTertiary = Color(0xFF7A8798),
        accent = Color(0xFF6D9BFF),
        accentPressed = Color(0xFF5A88F0),
        onAccent = Color(0xFF06101F),
        income = Color(0xFF34D399),
        danger = Color(0xFFF87171),
        warning = Color(0xFFFBBF24),
        chart1 = Color(0xFF6D9BFF),
        chart2 = Color(0xFF34D399),
        chart3 = Color(0xFFFBBF24),
        chart4 = Color(0xFFA78BFA),
        chart5 = Color(0xFF22D3EE),
        chart6 = Color(0xFFF87171),
    )

    /** 按当前模式取一套。 */
    fun of(isDark: Boolean): LedgerColors = if (isDark) dark else light

    /**
     * 给「底 + 半透明色」叠出来的块用（如 `primaryContainer`、预算进度条底槽）。
     *
     * 深色比浅色多 0.04 alpha：近黑底上同等 alpha 的色块会偏暗，肉眼看起来"没填满"。
     */
    fun tint(base: Color, alpha: Float, isDark: Boolean): Color {
        val a = if (isDark) (alpha + 0.04f).coerceAtMost(0.32f) else alpha
        return base.copy(alpha = a)
    }
}