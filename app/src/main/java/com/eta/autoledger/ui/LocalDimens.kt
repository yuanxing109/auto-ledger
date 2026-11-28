package com.eta.autoledger.ui

import com.eta.autoledger.ui.theme.OilDimens

/**
 * 尺寸令牌的 CompositionLocal 入口。
 *
 * 与 [LedgerTheme] 的色值入口是同一套思路：
 *  - Composable 里用 `LocalDimens.current.gap` 拿间距；
 *  - Canvas / 默认参数这类拿不到 CompositionLocal 的地方，用 [LedgerDimens.default]。
 *
 * 目前只有一套（浅深色不改变尺寸），但入口先留出来 —— 以后要做「大屏放大」「紧凑模式」，
 * 只要换 CompositionLocal 的提供方，不用改任何页面代码。
 */
val LocalDimens = androidx.compose.runtime.staticCompositionLocalOf { OilDimens.default }
