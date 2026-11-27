package com.eta.autoledger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 一套完整的语义色（浅色或深色）。
 *
 * 这是全项目**唯一的色值真源**：`LedgerPalette.light` / `LedgerPalette.dark` 就是
 * `docs/ui-design-system.md` 中的语义色规范。任何地方要新增颜色，
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
    /** 按下态表面：深色用实心提亮一档（#1C242E），不用白色透明叠加。 */
    val surfacePressed: Color,
    // ---- 线 ----
    /** 可交互组件边界；选中态还必须同时用颜色、文字或图标表达。 */
    val border: Color,
    /** 装饰性卡片/容器外框：1dp 实色细线。 */
    val borderSoft: Color,
    val divider: Color,
    /** 图表网格专用中性实色线。 */
    val chartGrid: Color,
    /** 未选中柱图的实色中性填充。 */
    val chartMuted: Color,
    val scrim: Color,
    /** 自定义首页背景上的可读性遮罩。 */
    val backgroundOverlay: Color,
    /** 卡片柔和投影色，必须使用带透明度的语义色。 */
    val cardShadow: Color,
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
    val expense: Color,
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

    // ---- 预算语义：蓝色承担「剩余」，绿色只归收入金额，禁止拿 income 表示预算 ----
    /** 预算剩余充足：用强调蓝（不用收入绿）。 */
    val budgetRemaining: Color get() = accent
    /** 预算临近上限（≥80%）：琥珀提醒。 */
    val budgetNearLimit: Color get() = warning
    /** 预算已超支：红。 */
    val budgetOver: Color get() = danger
}

/**
 * 两套色板。
 *
 * 为什么不用 `isSystemInDarkTheme()` 在这里判断：色板必须能在 `Canvas` 的 DrawScope、
 * 函数的默认参数、以及 `RemoteViews` 之外的任何上下文里取到，所以只能由调用方告知
 * 当前模式。当前模式的唯一来源是 [com.eta.autoledger.ui.theme.AppTheme]。
 */
object LedgerPalette {

    /** 浅色：干净冷白底 + 纯白实色卡片 + 清晰 Zinc 细线。 */
    val light: LedgerColors = LedgerColors(
        page = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF4F4F5),
        inset = Color(0xFFF4F4F5),
        surfacePressed = Color(0xFFEFEFF1),
        border = Color(0xFFE4E4E7),
        borderSoft = Color(0xFFE4E4E7),
        divider = Color(0xFFE4E4E7),
        chartGrid = Color(0xFFF1F1F3),
        chartMuted = Color(0xFFA1A1AA),
        scrim = Color(0x99000000),
        backgroundOverlay = Color(0x80FFFFFF),
        cardShadow = Color(0x0D000000),
        text = Color(0xFF09090B),
        textSecondary = Color(0xFF71717A),
        textTertiary = Color(0xFFA1A1AA),
        accent = Color(0xFF2563EB),
        accentPressed = Color(0xFF1D4FD7),
        onAccent = Color(0xFFFFFFFF),
        income = Color(0xFF399E75),
        expense = Color(0xFFD16158),
        danger = Color(0xFFC6534D),
        warning = Color(0xFFB7791F),
        chart1 = Color(0xFF2563EB),
        chart2 = Color(0xFF399E75),
        chart3 = Color(0xFFF59E0B),
        chart4 = Color(0xFF0891B2),
        chart5 = Color(0xFFDB2777),
        chart6 = Color(0xFFD16158),
    )

    /**
     * 深色：近黑实色分档 `page < inset < surface < surfaceVariant`。
     *
     * 不用 `#000000`：纯黑与卡片 `#18181B` 的强对比会让滚动时数字「抖动」（OLED 亮度
     * 切换更明显），而且近黑底上的阴影完全不可见，所以深色一律靠 `border` 描边分层。
     */
    val dark: LedgerColors = LedgerColors(
        page = Color(0xFF09090B),
        surface = Color(0xFF18181B),
        surfaceVariant = Color(0xFF27272A),
        inset = Color(0xFF202023),
        surfacePressed = Color(0xFF27272A),
        border = Color(0xFF27272A),
        borderSoft = Color(0xFF27272A),
        divider = Color(0xFF3F3F46),
        chartGrid = Color(0xFF202023),
        chartMuted = Color(0xFF52525B),
        scrim = Color(0xB3000000),
        backgroundOverlay = Color(0xB3000000),
        cardShadow = Color(0x0D000000),
        text = Color(0xFFFAFAFA),
        textSecondary = Color(0xFFA1A1AA),
        textTertiary = Color(0xFF71717A),
        accent = Color(0xFF60A5FA),
        accentPressed = Color(0xFF3B82F6),
        onAccent = Color(0xFF09090B),
        income = Color(0xFF70C39D),
        expense = Color(0xFFE18B83),
        danger = Color(0xFFD97870),
        warning = Color(0xFFD7A94E),
        chart1 = Color(0xFF60A5FA),
        chart2 = Color(0xFF70C39D),
        chart3 = Color(0xFFFBBF24),
        chart4 = Color(0xFF22D3EE),
        chart5 = Color(0xFFF472B6),
        chart6 = Color(0xFFE18B83),
    )

    /** 按当前模式取一套。 */
    fun of(isDark: Boolean): LedgerColors = if (isDark) dark else light

}
