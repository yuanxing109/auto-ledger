package com.eta.autoledger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全站间距与圆角的**唯一真源**。
 *
 * 为什么单独一层：字号有 Miuix 的 14 个 `textStyles` 槽接，色值有 [LedgerPalette] 的 53 槽接，
 * 但间距和圆角此前散落在 58 处 `RoundedCornerShape(14.dp)` / `padding(13.dp)` 里，
 * 同一个视觉元素在首页是 14dp、在报表页是 16dp，谁也说不清"标准是多少"。
 *
 * 阶梯只有五档（[LedgerDimens] 的 5 个常量），任何新代码只能从这五档里选：
 * 不允许再出现 `padding(13.dp)` 这种一次性数字。
 *
 * `tools/check-ui-consistency.sh` 的 R6 规则会把本文件之外的魔法数统计出来（当前是 warn，
 * 迁移完成后升级为 error）。
 */
@Immutable
data class LedgerDimens(
    /** 卡片内边距（标准卡片内容区）。 */
    val cardPadding: Dp,
    /** 页面左右安全边距。 */
    val pagePadding: Dp,
    /** 元素之间的标准间距。 */
    val gap: Dp,
    /** 元素之间的紧凑间距。 */
    val gapSmall: Dp,
    /** 元素之间的大间距（分区之间）。 */
    val gapLarge: Dp,
    /** 标准圆角（输入框、键帽、小卡片）。 */
    val radiusSmall: Dp,
    /** 卡片圆角。 */
    val radiusCard: Dp,
    /** 弹层圆角（对话框、底部面板）。 */
    val radiusSheet: Dp,
    /** 胶囊圆角（按钮、标签）。 */
    val radiusCapsule: Dp,
) {
    /**
     * 标准尺寸（间距/圆角常量）与默认实例。
     *
     * 收进 `companion object`：`LedgerDimens` 既是 `data class`（实例携带一套间距），
     * 又需要 `LedgerDimens.default` / `LedgerDimens.sm` 这类静态取用。顶层 `object`
     * 会与同类名 `data class` 构成重复声明，放进 companion 后二者共存且调用点不变。
     */
    companion object {
        /** 4dp —— 极紧间距（图标与文字之间）。 */
        val xs: Dp = 4.dp

        /** 8dp —— 紧凑间距（同级元素）。 */
        val sm: Dp = 8.dp

        /** 12dp —— 标准间距（文字与图标、标签之间）。 */
        val md: Dp = 12.dp

        /** 16dp —— 宽松间距（卡片内边距、页面边距）。 */
        val lg: Dp = 16.dp

        /** 24dp —— 分区间距（区块与区块）。 */
        val xl: Dp = 24.dp

        /** 32dp —— 大分区间距（页面顶部留白、区块大分隔）。 */
        val xxl: Dp = 32.dp

        /** 48dp —— 最小可点区域（Android 无障碍下限；iOS 对应 44pt）。 */
        val minTouch: Dp = 48.dp

        /** 8dp 圆角。 */
        val radiusSm: Dp = 8.dp

        /** 12dp 圆角。 */
        val radiusMd: Dp = 12.dp

        /** 16dp 圆角。 */
        val radiusLg: Dp = 16.dp

        /** 24dp 圆角。 */
        val radiusXl: Dp = 24.dp

        /** 28dp 圆角（对话框）。 */
        val radiusDialog: Dp = 28.dp

        /** 全局默认尺寸集。 */
        val default: LedgerDimens = LedgerDimens(
            cardPadding = lg,
            pagePadding = lg,
            gap = md,
            gapSmall = sm,
            gapLarge = xl,
            radiusSmall = radiusMd,
            radiusCard = radiusXl,
            radiusSheet = radiusDialog,
            radiusCapsule = 50.dp,
        )
    }
}
