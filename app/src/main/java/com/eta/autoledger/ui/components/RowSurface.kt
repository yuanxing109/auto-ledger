package com.eta.autoledger.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 列表行的「圆角背景 + 细描边」一次性绘制。
 *
 * 原先每行是三条 modifier 叠起来：
 * ```
 * .clip(shape).background(fill).border(0.5.dp, stroke, shape)
 * ```
 * 问题在于 `border` 会自己再建一个内缩的 outline 并单独走一个绘制节点，
 * 加上 `background` 一条 —— 每行每帧两次 outline 计算 + 两个绘制节点。
 * 滚动时一屏十几行，这部分是纯浪费。
 *
 * 这里在 `drawBehind` 里对同一个 outline 画两遍（先填充、再描边），
 * outline 只算一次，绘制节点也只有一个。
 *
 * 用法：
 * ```
 * Modifier.fillMaxWidth().clip(shape).drawBehind { drawRowSurface(shape, fill, stroke) }
 * ```
 * 注意 `clip` 仍要保留：描边是画在边界内侧的 0.5dp，若父级有背景色溢出需要它裁掉；
 * 且 clip 本身是图层裁剪，比 background 的绘制成本低。
 */
fun DrawScope.drawRowSurface(
    shape: Shape,
    fill: Color,
    stroke: Color,
    strokeWidthDp: Float = 0.5f,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, color = fill)
    drawOutline(outline, color = stroke, style = Stroke(width = strokeWidthDp.dp.toPx()))
}
