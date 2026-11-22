package com.eta.autoledger.ui.theme

import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 把项目 token 灌进 Miuix 的 53 个色槽。
 *
 * 为什么用 `lightColorScheme(...)` 的命名参数，而不是 `lightColorScheme().apply { primary = … }`：
 * `Colors` 里 53 个 `var` 的 setter 全是 `internal`（库内部可见），外部模块只能走工厂函数的
 * 构造参数赋值。工厂函数 53 个参数**全带默认值**，所以这里只覆盖用得着的 40 个，
 * 剩下 13 个（`disabled*`、`slider*`）保留 Miuix 官方推导值 —— 它们只影响禁用态与滑块，
 * 与项目色板没有语义冲突。
 *
 * 命名必须精确：`surfaceContainerVariant` 不存在，正确的是 `onSurfaceContainerVariant`
 * （CI 曾在这一点上报 `Unresolved reference`）。
 *
 * 覆盖原则：**Miuix 组件看起来就该像这个 App 自己画的**。所以：
 * - `primary/onPrimary` ← `accent/onAccent`（主按钮、FAB、开关、下划线）
 * - `background/onBackground` ← `page/text`（页面底色）
 * - `surface*` 五档容器全部映射到 `surface/surfaceVariant/inset`，不再让 Miuix 自己算五档灰
 * - `outline/dividerLine` ← `border/divider`（深色靠描边分层，不靠阴影）
 * - `error*` ← `danger` + 12% 淡底
 */
internal fun ledgerLightColors(): Colors {
    val p = LedgerPalette.light
    return lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryVariant = p.accentPressed,
        onPrimaryVariant = p.onAccent,
        primaryContainer = LedgerPalette.tint(p.accent, 0.16f, false),
        onPrimaryContainer = p.onAccent,

        secondary = p.surfaceVariant,
        onSecondary = p.text,
        secondaryVariant = p.surfaceVariant,
        onSecondaryVariant = p.textSecondary,
        secondaryContainer = p.surfaceVariant,
        onSecondaryContainer = p.text,
        secondaryContainerVariant = p.surfaceVariant,
        onSecondaryContainerVariant = p.textSecondary,

        tertiaryContainer = p.surfaceVariant,
        onTertiaryContainer = p.text,
        tertiaryContainerVariant = p.surfaceVariant,

        background = p.page,
        onBackground = p.text,
        onBackgroundVariant = p.textSecondary,

        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceVariant,
        onSurfaceSecondary = p.textSecondary,
        onSurfaceVariantSummary = p.textSecondary,
        onSurfaceVariantActions = p.accent,
        disabledOnSurface = p.textTertiary,

        surfaceContainer = p.surface,
        onSurfaceContainer = p.text,
        onSurfaceContainerVariant = p.textSecondary,
        surfaceContainerHigh = p.surfacePressed,
        onSurfaceContainerHigh = p.text,
        surfaceContainerHighest = p.surfacePressed,
        onSurfaceContainerHighest = p.text,

        outline = p.border,
        dividerLine = p.divider,
        windowDimming = p.scrim,

        error = p.danger,
        onError = p.onAccent,
        errorContainer = LedgerPalette.tint(p.danger, 0.12f, false),
        onErrorContainer = p.danger,
    )
}

/** 深色版：同一套映射规则，色值来自 `LedgerPalette.dark`。 */
internal fun ledgerDarkColors(): Colors {
    val p = LedgerPalette.dark
    return darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryVariant = p.accentPressed,
        onPrimaryVariant = p.onAccent,
        primaryContainer = LedgerPalette.tint(p.accent, 0.16f, true),
        onPrimaryContainer = p.onAccent,

        secondary = p.surfaceVariant,
        onSecondary = p.text,
        secondaryVariant = p.surfaceVariant,
        onSecondaryVariant = p.textSecondary,
        secondaryContainer = p.surfaceVariant,
        onSecondaryContainer = p.text,
        secondaryContainerVariant = p.surfaceVariant,
        onSecondaryContainerVariant = p.textSecondary,

        tertiaryContainer = p.surfaceVariant,
        onTertiaryContainer = p.text,
        tertiaryContainerVariant = p.surfaceVariant,

        background = p.page,
        onBackground = p.text,
        onBackgroundVariant = p.textSecondary,

        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceVariant,
        onSurfaceSecondary = p.textSecondary,
        onSurfaceVariantSummary = p.textSecondary,
        onSurfaceVariantActions = p.accent,
        disabledOnSurface = p.textTertiary,

        surfaceContainer = p.surface,
        onSurfaceContainer = p.text,
        onSurfaceContainerVariant = p.textSecondary,
        surfaceContainerHigh = p.surfacePressed,
        onSurfaceContainerHigh = p.text,
        surfaceContainerHighest = p.surfacePressed,
        onSurfaceContainerHighest = p.text,

        outline = p.border,
        dividerLine = p.divider,
        windowDimming = p.scrim,

        error = p.danger,
        onError = p.onAccent,
        errorContainer = LedgerPalette.tint(p.danger, 0.12f, true),
        onErrorContainer = p.danger,
    )
}

/** 预留：让外部知道哪些槽是"故意不覆盖"的，写文档/复查时对得上。 */
internal val MiuixSlotsIntentionallyDefault: Set<String> = setOf(
    "disabledPrimary", "disabledOnPrimary", "disabledPrimaryButton",
    "disabledOnPrimaryButton", "disabledPrimarySlider",
    "disabledSecondary", "disabledOnSecondary", "disabledSecondaryVariant",
    "disabledOnSecondaryVariant",
    "sliderKeyPoint", "sliderKeyPointForeground", "sliderBackground",
)