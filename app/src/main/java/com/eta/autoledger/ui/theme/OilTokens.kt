package com.eta.autoledger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Oil UI 的完整语义色板。色值只允许在本文件中定义。 */
@Immutable
data class OilColors(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceMuted: Color,
    val surfacePressed: Color,
    val line: Color,
    val divider: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val heroSurface: Color,
    val heroMuted: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val income: Color,
    val expense: Color,
    val danger: Color,
    val warning: Color,
    val scrim: Color,
    val shadow: Color,
    val chartGrid: Color,
    val chartMuted: Color,
    val chart1: Color,
    val chart2: Color,
    val chart3: Color,
    val chart4: Color,
    val chart5: Color,
    val chart6: Color,
) {
    // 旧数据展示代码仍读取这些语义名。它们只做映射，不形成第二套色源。
    val page: Color get() = canvas
    val surfaceVariant: Color get() = surfaceMuted
    val inset: Color get() = surfaceMuted
    val border: Color get() = line
    val borderSoft: Color get() = line
    val text: Color get() = ink
    val textSecondary: Color get() = inkMuted
    val textTertiary: Color get() = inkFaint
    val cardShadow: Color get() = shadow
    val backgroundOverlay: Color get() = canvas
    val budgetRemaining: Color get() = accent
    val budgetNearLimit: Color get() = warning
    val budgetOver: Color get() = danger
    val charts: List<Color> get() = listOf(chart1, chart2, chart3, chart4, chart5, chart6)
}

object OilPalette {
    val light = OilColors(
        canvas = Color(0xFFF1EEE5),
        surface = Color(0xFFFFFCF5),
        surfaceRaised = Color(0xFFFFFFFF),
        surfaceMuted = Color(0xFFE8E2D6),
        surfacePressed = Color(0xFFDDD6C8),
        line = Color(0xFFD7D0C3),
        divider = Color(0xFFE2DCCF),
        ink = Color(0xFF17201D),
        inkMuted = Color(0xFF5B655F),
        inkFaint = Color(0xFF858D87),
        accent = Color(0xFF0E5C63),
        accentPressed = Color(0xFF09484E),
        onAccent = Color(0xFFFFFCF5),
        heroSurface = Color(0xFF0E5C63),
        heroMuted = Color(0xFF09484E),
        onHero = Color(0xFFFFFCF5),
        onHeroMuted = Color(0xFFCFE0DB),
        income = Color(0xFF237A57),
        expense = Color(0xFFB74332),
        danger = Color(0xFFA9362B),
        warning = Color(0xFF996515),
        scrim = Color(0xB818201D),
        shadow = Color(0x2417201D),
        chartGrid = Color(0xFFE3DDD1),
        chartMuted = Color(0xFF9BA29C),
        chart1 = Color(0xFF0E5C63),
        chart2 = Color(0xFF237A57),
        chart3 = Color(0xFFC47D3E),
        chart4 = Color(0xFF436A8C),
        chart5 = Color(0xFF8B5D74),
        chart6 = Color(0xFF7B7F3D),
    )

    val dark = OilColors(
        canvas = Color(0xFF0F1412),
        surface = Color(0xFF171D1A),
        surfaceRaised = Color(0xFF202823),
        surfaceMuted = Color(0xFF252E29),
        surfacePressed = Color(0xFF2B3630),
        line = Color(0xFF323C36),
        divider = Color(0xFF28312C),
        ink = Color(0xFFF4F0E7),
        inkMuted = Color(0xFFB2B8B1),
        inkFaint = Color(0xFF7F8881),
        accent = Color(0xFF70C8CD),
        accentPressed = Color(0xFF95D9DD),
        onAccent = Color(0xFF0C2325),
        heroSurface = Color(0xFF12383A),
        heroMuted = Color(0xFF1B4B4D),
        onHero = Color(0xFFF4F0E7),
        onHeroMuted = Color(0xFFB7C4BE),
        income = Color(0xFF7BC7A4),
        expense = Color(0xFFF18A78),
        danger = Color(0xFFFF9A88),
        warning = Color(0xFFE1B15B),
        scrim = Color(0xC40A0E0C),
        shadow = Color(0x52000000),
        chartGrid = Color(0xFF2B3530),
        chartMuted = Color(0xFF59645D),
        chart1 = Color(0xFF70C8CD),
        chart2 = Color(0xFF7BC7A4),
        chart3 = Color(0xFFE6A15F),
        chart4 = Color(0xFF79A7CF),
        chart5 = Color(0xFFC88DA8),
        chart6 = Color(0xFFB4B873),
    )

    fun of(isDark: Boolean): OilColors = if (isDark) dark else light
}

@Immutable
data class OilDimens(
    val cardPadding: Dp = lg,
    val pagePadding: Dp = lg,
    val gap: Dp = md,
    val gapSmall: Dp = sm,
    val gapLarge: Dp = xl,
    val radiusSmall: Dp = radiusMd,
    val radiusCard: Dp = radiusXl,
    val radiusSheet: Dp = radiusDialog,
    val radiusCapsule: Dp = 50.dp,
) {
    companion object {
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 24.dp
        val xxl: Dp = 32.dp
        val minTouch: Dp = 48.dp
        val radiusSm: Dp = 8.dp
        val radiusMd: Dp = 12.dp
        val radiusLg: Dp = 16.dp
        val radiusXl: Dp = 24.dp
        val radiusDialog: Dp = 32.dp
        val default: OilDimens = OilDimens()
    }
}

@Immutable
data class OilTypography(
    val heroAmount: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontFeatureSettings = "tnum",
    ),
    val amount: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontFeatureSettings = "tnum",
    ),
    val pageTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    val sectionTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    val metric: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontFeatureSettings = "tnum",
    ),
    val chartLabel: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFeatureSettings = "tnum",
    ),
    val listAmount: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontFeatureSettings = "tnum",
    ),
)

@Immutable
data class OilShapes(
    val control: Shape = RoundedCornerShape(16.dp),
    val card: Shape = RoundedCornerShape(24.dp),
    val dialog: Shape = RoundedCornerShape(32.dp),
    val capsule: Shape = RoundedCornerShape(50),
)
