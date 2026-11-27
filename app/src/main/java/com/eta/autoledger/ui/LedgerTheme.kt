package com.eta.autoledger.ui

import androidx.compose.ui.graphics.Color
import com.eta.autoledger.ui.theme.LedgerColors
import com.eta.autoledger.ui.theme.LedgerPalette

/**
 * 自绘侧的语义色入口 —— P0 之后它只是 [LedgerPalette] 的转发层，不再是第二个色源。
 *
 * 存在的理由：`Canvas` 的 `DrawScope`、`Modifier.drawWithCache` 的 lambda、函数默认参数、
 * 以及图表库的 `Colors` 配置都拿不到 `CompositionLocal`，只有普通 getter 能同时满足。
 *
 * 三条使用规则（`tools/check-ui-consistency.sh` 会查）：
 * 1. **Composable 里优先用 `MiuixTheme.colorScheme.<槽>`**，与本类同名同值；
 * 2. 本类只允许出现在 Canvas / drawWithCache / 图表配置 / 非 Composable 工具函数里；
 * 3. 任何地方都不得再写 `Color(0x…)`，色值只能住在 [LedgerPalette]。
 *
 * [dark] 由 `AppTheme` 在每次重组时写入，与 Miuix 的 `ThemeController` 用同一个判定结果，
 * 所以自绘与 Miuix 组件不会出现"半深半浅"。
 */
object LedgerTheme {

    /** 当前是否深色。由 AppTheme 在组装期同步，其他代码只读。 */
    var dark: Boolean = false
        internal set

    /** 兼容旧调用：返回当前生效（可能来自系统）的深色状态。 */
    fun isDark(): Boolean = dark

    /** 当前生效的整套色板。 */
    private val colors: LedgerColors get() = LedgerPalette.of(dark)

    // ---------------- 表面层次：page < inset < surface < surfaceVariant ----------------

    /** 页面底色。浅色为冷白，深色为近黑，均为实色。 */
    val page: Color get() = colors.page

    /** 卡片 / 顶栏 / 弹层底色。 */
    val surface: Color get() = colors.surface

    /** 兼容旧名：等同于 [surface]。 */
    val card: Color get() = colors.surface

    /** 卡片内部再压一层：输入框、键帽、次级卡片。 */
    val surfaceVariant: Color get() = colors.surfaceVariant

    /** 卡内的「凹槽」块（比卡片更暗，表达下沉）。 */
    val inset: Color get() = colors.inset

    /** 按下态表面：深色用实心提亮一档（#1C242E），不再用白色透明叠加。 */
    val surfacePressed: Color get() = colors.surfacePressed

    // ---------------- 线 ----------------

    /** 可交互组件边界；选中态还必须同时用颜色、文字或图标表达。 */
    val border: Color get() = colors.border

    /** 装饰性卡片/容器外框：不受 3:1 约束，仅需肉眼可辨。 */
    val borderSoft: Color get() = colors.borderSoft

    /** 兼容旧名：等同于 [borderSoft]（卡片场景）。 */
    val cardBorder: Color get() = colors.borderSoft

    /** 列表分隔线 / 图表虚线网格。 */
    val divider: Color get() = colors.divider

    /** 图表网格专用中性实色。 */
    val chartGrid: Color get() = colors.chartGrid

    /** 未选中柱图的实色中性填充。 */
    val chartMuted: Color get() = colors.chartMuted

    /** 遮罩（弹层背后的压暗层）。 */
    val scrim: Color get() = colors.scrim

    /** 自定义全局背景图上的主题可读性遮罩。 */
    val backgroundOverlay: Color get() = colors.backgroundOverlay

    /** 报表截图分享使用的纯白/纯黑底。 */
    val captureBackground: Color get() = colors.captureBackground

    /** 卡片统一柔和阴影色。 */
    val cardShadow: Color get() = colors.cardShadow

    // ---------------- 文字 ----------------

    val text: Color get() = colors.text

    val textSecondary: Color get() = colors.textSecondary

    val textTertiary: Color get() = colors.textTertiary

    // ---------------- 强调与语义 ----------------

    val accent: Color get() = colors.accent

    val accentPressed: Color get() = colors.accentPressed

    /** 主色块上的文字（深色模式下 accent 是亮蓝，必须配深字）。 */
    val onAccent: Color get() = colors.onAccent

    val income: Color get() = colors.income

    /** 支出金额的柔和砖红。 */
    val expense: Color get() = colors.expense

    val danger: Color get() = colors.danger

    val warning: Color get() = colors.warning

    // ---- 预算语义：蓝=剩余，琥珀=临界，红=超支；绿只归收入金额 ----
    val budgetRemaining: Color get() = colors.budgetRemaining
    val budgetNearLimit: Color get() = colors.budgetNearLimit
    val budgetOver: Color get() = colors.budgetOver

    // ---------------- 图表调色板 ----------------

    val chart1: Color get() = colors.chart1
    val chart2: Color get() = colors.chart2
    val chart3: Color get() = colors.chart3
    val chart4: Color get() = colors.chart4
    val chart5: Color get() = colors.chart5
    val chart6: Color get() = colors.chart6

    /** 分类构成图的取色顺序，稳定不变（换顺序会让用户觉得"分类颜色跳了"）。 */
    val chartSeries: List<Color> get() = colors.charts
}
