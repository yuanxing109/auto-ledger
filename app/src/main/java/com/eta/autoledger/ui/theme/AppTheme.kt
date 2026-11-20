package com.eta.autoledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.eta.autoledger.ui.LedgerTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 「外观」设置的可选值，与 `AppPrefs.themeMode` 存进 SharedPreferences 的字符串一致。 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    val all: List<String> = listOf(SYSTEM, LIGHT, DARK)
}

/**
 * 全站唯一的主题根。
 *
 * 做三件事：
 * 1. 解析 `themeMode`（跟随系统 / 浅色 / 深色）成布尔深色状态；
 * 2. 把项目色板灌进 [ThemeController]，此后 **Miuix 组件与自绘控件读的是同一套色值**；
 * 3. 把深色状态同步给 [LedgerTheme]，供 `Canvas` / 图表 / 函数默认参数这类拿不到
 *    `CompositionLocal` 的地方使用。
 *
 * 为什么 `colorSchemeMode` 用 `System` 而不是直接 Light/Dark：`System` 是 Miuix 的原生
 * 能力，同时传 `isDark = dark` 可以保证"我们算出的深色"与"Miuix 内部算出的深色"永远
 * 一致（否则系统切深色时可能出现自绘浅色 + Miuix 深色的半深半浅状态）。
 *
 * 唯一调用点：`LedgerApp`。任何页面都不得再包一层 `MiuixTheme` / `MaterialTheme`。
 */
@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    // 自绘侧的唯一入口，必须在建 controller 之前写，保证同一帧内两侧一致。
    LedgerTheme.dark = dark

    val controller = remember(mode, dark) {
        ThemeController(
            colorSchemeMode = when (mode) {
                ThemeMode.LIGHT -> ColorSchemeMode.Light
                ThemeMode.DARK -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            },
            // 两套 Colors 各建一次即可；它们内部是 mutableStateOf，不能在重组里反复 new。
            lightColors = ledgerLightColors(),
            darkColors = ledgerDarkColors(),
            isDark = dark,
        )
    }

    MiuixTheme(
        controller = controller,
        textStyles = ledgerTextStyles(),
        content = content,
    )
}