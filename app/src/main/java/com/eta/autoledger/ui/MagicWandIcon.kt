package com.eta.autoledger.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 细线魔法棒与星芒，作为 AI 快速记账入口，颜色由调用方 tint 控制。 */
val MagicWandIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MagicWand",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            // 占位色只要不透明即可，实际渲染由 Icon(tint=...) 覆盖。
            stroke = SolidColor(Color(0f, 0f, 0f, 1f)),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4.5f, 19.5f)
            lineTo(14f, 10f)
            moveTo(15.8f, 8.2f)
            lineTo(19.5f, 4.5f)

            moveTo(16.8f, 3f)
            lineTo(16.8f, 5.4f)
            moveTo(15.6f, 4.2f)
            lineTo(18f, 4.2f)

            moveTo(19.6f, 10.2f)
            lineTo(19.6f, 12f)
            moveTo(18.7f, 11.1f)
            lineTo(20.5f, 11.1f)

            moveTo(5.4f, 5.1f)
            lineTo(5.4f, 7f)
            moveTo(4.5f, 6.05f)
            lineTo(6.3f, 6.05f)
        }
    }.build()
}
