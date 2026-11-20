package com.eta.autoledger.ui.theme

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 把 [LedgerPalette] 的 21 个语义色灌进 Miuix 的 [Colors]。
 *
 * 这是「全局只有一个色源」的关键一步：Miuix 自己的 `lightColorScheme()` 是小米蓝
 * `#3482FF` 一套，本项目自绘部分用的是 `#2563EB` 一套，两套同时存在时同屏就会出现
 * 两种蓝、两种灰 —— 这就是用户说的「全局不统一」。
 *
 * 做法：以官方 scheme 为底（53 个槽里我们只覆盖真正影响视觉的 34 个，其余保留官方
 * 值，避免漏槽导致某控件配色断裂），逐槽替换成项目色板。
 *
 * 只覆盖这些槽的理由：
 * - `disabled*` 系列（10 个）由官方按主色推导，覆盖反而容易与真实禁用态不一致；
 * - `sliderKeyPoint*` 与 `sliderBackground`（3 个）只影响滑块，P3 调 SliderPreference 时再看；
 * - `secondaryContainer*` 系列（4 个）在 Miuix 里是灰底标签，官方中性灰在我们的近黑底上可直接用；
 * - `primaryContainer` / `tertiaryContainer*` 保留给 chip、选中态，用项目色的 12% 叠层。
 */

/** 浅色主题下的 Miuix 配色。 */
internal fun ledgerLightColors(): Colors {
    val p = LedgerPalette.light
    return lightColorScheme().apply {
        primary = p.accent
        onPrimary = p.onAccent
        primaryVariant = p.accentPressed
        onPrimaryVariant = p.onAccent
        primaryContainer = LedgerPalette.tint(p.accent, 0.12f, isDark = false)
        onPrimaryContainer = p.accent

        secondary = p.surfaceVariant
        onSecondary = p.text
        secondaryVariant = p.inset
        onSecondaryVariant = p.textSecondary

        tertiaryContainer = LedgerPalette.tint(p.income, 0.12f, isDark = false)
        onTertiaryContainer = p.income
        tertiaryContainerVariant = LedgerPalette.tint(p.income, 0.08f, isDark = false)

        background = p.page
        onBackground = p.text
        onBackgroundVariant = p.textSecondary

        surface = p.surface
        onSurface = p.text
        surfaceVariant = p.surfaceVariant
        onSurfaceSecondary = p.textSecondary
        onSurfaceVariantSummary = p.textSecondary
        onSurfaceVariantActions = p.textTertiary
        disabledOnSurface = p.textTertiary.copy(alpha = 0.38f)

        surfaceContainer = p.surface
        onSurfaceContainer = p.text
        surfaceContainerVariant = p.inset
        onSurfaceContainerVariant = p.textSecondary
        surfaceContainerHigh = p.surfaceVariant
        onSurfaceContainerHigh = p.text
        surfaceContainerHighest = p.surfaceVariant
        onSurfaceContainerHighest = p.textSecondary

        outline = p.border
        dividerLine = p.divider
        windowDimming = p.scrim

        error = p.danger
        onError = Color.White
        errorContainer = LedgerPalette.tint(p.danger, 0.10f, isDark = false)
        onErrorContainer = p.danger
    }
}

/** 深色主题下的 Miuix 配色。槽位与浅色一一对应，值取深色表。 */
internal fun ledgerDarkColors(): Colors {
    val p = LedgerPalette.dark
    return darkColorScheme().apply {
        primary = p.accent
        onPrimary = p.onAccent
        primaryVariant = p.accentPressed
        onPrimaryVariant = p.onAccent
        primaryContainer = LedgerPalette.tint(p.accent, 0.12f, isDark = true)
        onPrimaryContainer = p.accent

        secondary = p.surfaceVariant
        onSecondary = p.text
        secondaryVariant = p.inset
        onSecondaryVariant = p.textSecondary

        tertiaryContainer = LedgerPalette.tint(p.income, 0.12f, isDark = true)
        onTertiaryContainer = p.income
        tertiaryContainerVariant = LedgerPalette.tint(p.income, 0.08f, isDark = true)

        background = p.page
        onBackground = p.text
        onBackgroundVariant = p.textSecondary

        surface = p.surface
        onSurface = p.text
        surfaceVariant = p.surfaceVariant
        onSurfaceSecondary = p.textSecondary
        onSurfaceVariantSummary = p.textSecondary
        onSurfaceVariantActions = p.textTertiary
        disabledOnSurface = p.textTertiary.copy(alpha = 0.38f)

        surfaceContainer = p.surface
        onSurfaceContainer = p.text
        surfaceContainerVariant = p.inset
        onSurfaceContainerVariant = p.textSecondary
        surfaceContainerHigh = p.surfaceVariant
        onSurfaceContainerHigh = p.text
        surfaceContainerHighest = p.surfaceVariant
        onSurfaceContainerHighest = p.textSecondary

        outline = p.border
        dividerLine = p.divider
        windowDimming = p.scrim

        error = p.danger
        onError = p.onAccent
        errorContainer = LedgerPalette.tint(p.danger, 0.12f, isDark = true)
        onErrorContainer = p.danger
    }
}