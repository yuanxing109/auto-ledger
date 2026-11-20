package com.eta.autoledger.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 扫描框 + AI 星芒，用来代替文字版「AI识图」按钮。 */
val AiScanIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AiScan",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4.5f, 9f)
            lineTo(4.5f, 5.5f)
            lineTo(8f, 5.5f)
            moveTo(16f, 5.5f)
            lineTo(19.5f, 5.5f)
            lineTo(19.5f, 9f)
            moveTo(19.5f, 15f)
            lineTo(19.5f, 18.5f)
            lineTo(16f, 18.5f)
            moveTo(8f, 18.5f)
            lineTo(4.5f, 18.5f)
            lineTo(4.5f, 15f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 8.6f)
            lineTo(12.9f, 11.1f)
            lineTo(15.4f, 12f)
            lineTo(12.9f, 12.9f)
            lineTo(12f, 15.4f)
            lineTo(11.1f, 12.9f)
            lineTo(8.6f, 12f)
            lineTo(11.1f, 11.1f)
            close()
        }
    }.build()
}