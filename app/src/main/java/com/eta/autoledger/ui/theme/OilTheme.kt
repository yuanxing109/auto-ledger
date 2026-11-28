package com.eta.autoledger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

val LocalOilColors = staticCompositionLocalOf { OilPalette.light }
val LocalOilDimens = staticCompositionLocalOf { OilDimens.default }
val LocalOilTypography = staticCompositionLocalOf { OilTypography() }
val LocalOilShapes = staticCompositionLocalOf { OilShapes() }
val LocalOilWallpaperActive = staticCompositionLocalOf { false }

/**
 * 页面正文的实色账页。自定义壁纸开启时只在两侧露出壁纸，正文仍保持实色背景。
 */
@Composable
fun Modifier.oilPageSurface(): Modifier {
    val active = LocalOilWallpaperActive.current
    return if (active) {
        padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(OilTheme.colors.canvas)
    } else {
        background(OilTheme.colors.canvas)
    }
}

/** Oil UI 的单一主题入口。页面与基础组件只从这里读取视觉 Token。 */
object OilTheme {
    var dark: Boolean = false
        internal set

    val colors: OilColors
        @Composable @ReadOnlyComposable get() = LocalOilColors.current

    val dimens: OilDimens
        @Composable @ReadOnlyComposable get() = LocalOilDimens.current

    val typography: OilTypography
        @Composable @ReadOnlyComposable get() = LocalOilTypography.current

    val shapes: OilShapes
        @Composable @ReadOnlyComposable get() = LocalOilShapes.current
}

@Composable
fun OilThemeProvider(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    OilTheme.dark = dark
    CompositionLocalProvider(
        LocalOilColors provides OilPalette.of(dark),
        LocalOilDimens provides OilDimens.default,
        LocalOilTypography provides OilTypography(),
        LocalOilShapes provides OilShapes(),
        content = content,
    )
}
