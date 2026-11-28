package com.eta.autoledger.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 行背景与边框共用一次 outline 计算，避免列表每行创建多个绘制节点。 */
fun DrawScope.drawOilRowSurface(
    shape: Shape,
    fill: Color,
    stroke: Color,
    strokeWidthDp: Float = 1f,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, color = fill)
    drawOutline(outline, color = stroke, style = Stroke(width = strokeWidthDp.dp.toPx()))
}
