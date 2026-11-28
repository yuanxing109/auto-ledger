package com.eta.autoledger.ui.theme

/** 旧视图与桌面小部件读取 Oil 色板的兼容入口。 */
typealias LedgerColors = OilColors

object LedgerPalette {
    val light: LedgerColors get() = OilPalette.light
    val dark: LedgerColors get() = OilPalette.dark

    fun of(isDark: Boolean): LedgerColors = OilPalette.of(isDark)
}
