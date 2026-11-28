package com.eta.autoledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.eta.autoledger.ui.LedgerTheme

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
 * Oil 组件读取 CompositionLocal，Canvas、图表和暂时保留的旧视图通过
 * [LedgerTheme] 读取同一份语义色映射。
 */
@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    LedgerTheme.dark = dark
    OilThemeProvider(
        dark = dark,
        content = content,
    )
}
