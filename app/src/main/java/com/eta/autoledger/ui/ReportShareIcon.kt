package com.eta.autoledger.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 报表分享图标。占位填充色由调用方 Icon(tint = ...) 覆盖。 */
val ReportShareIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ReportShare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color(0f, 0f, 0f, 1f))) {
            moveTo(18f, 16.08f)
            curveTo(17.24f, 16.08f, 16.56f, 16.38f, 16.04f, 16.85f)
            lineTo(8.91f, 12.7f)
            curveTo(8.96f, 12.47f, 9f, 12.24f, 9f, 12f)
            curveTo(9f, 11.76f, 8.96f, 11.53f, 8.91f, 11.3f)
            lineTo(15.96f, 7.19f)
            curveTo(16.5f, 7.69f, 17.21f, 8f, 18f, 8f)
            curveTo(19.66f, 8f, 21f, 6.66f, 21f, 5f)
            curveTo(21f, 3.34f, 19.66f, 2f, 18f, 2f)
            curveTo(16.34f, 2f, 15f, 3.34f, 15f, 5f)
            curveTo(15f, 5.24f, 15.04f, 5.47f, 15.09f, 5.7f)
            lineTo(8.04f, 9.81f)
            curveTo(7.5f, 9.31f, 6.79f, 9f, 6f, 9f)
            curveTo(4.34f, 9f, 3f, 10.34f, 3f, 12f)
            curveTo(3f, 13.66f, 4.34f, 15f, 6f, 15f)
            curveTo(6.79f, 15f, 7.5f, 14.69f, 8.04f, 14.19f)
            lineTo(15.16f, 18.35f)
            curveTo(15.11f, 18.56f, 15.08f, 18.78f, 15.08f, 19f)
            curveTo(15.08f, 20.61f, 16.39f, 21.92f, 18f, 21.92f)
            curveTo(19.61f, 21.92f, 20.92f, 20.61f, 20.92f, 19f)
            curveTo(20.92f, 17.39f, 19.61f, 16.08f, 18f, 16.08f)
            close()
        }
    }.build()
}
