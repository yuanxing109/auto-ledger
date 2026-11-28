package com.eta.autoledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Oil UI 的统一线性图标集，实际颜色统一由调用方 tint。 */
object OilIcons {
    val Add: ImageVector by lazy { lineIcon("OilAdd") { addCross() } }
    val Close: ImageVector by lazy { lineIcon("OilClose") { closeCross() } }
    val Back: ImageVector by lazy { lineIcon("OilBack") { chevron(left = true) } }
    val ChevronBackward: ImageVector by lazy { lineIcon("OilChevronBackward") { chevron(left = true) } }
    val ChevronForward: ImageVector by lazy { lineIcon("OilChevronForward") { chevron(left = false) } }
    val Home: ImageVector by lazy { lineIcon("OilHome") { home() } }
    val GridView: ImageVector by lazy { lineIcon("OilReport") { grid() } }
    val Search: ImageVector by lazy { lineIcon("OilSearch") { search() } }
    val Settings: ImageVector by lazy { lineIcon("OilSettings") { settings() } }
    val Scan: ImageVector by lazy { lineIcon("OilScan") { scan() } }
    val Photos: ImageVector by lazy { lineIcon("OilPhotos") { photos() } }
    val Months: ImageVector by lazy { lineIcon("OilMonths") { months() } }
    val Show: ImageVector by lazy { lineIcon("OilShow") { eye(hidden = false) } }
    val Hide: ImageVector by lazy { lineIcon("OilHide") { eye(hidden = true) } }
}

@Composable
fun OilIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(22.dp),
    tint: Color = com.eta.autoledger.ui.theme.OilTheme.colors.ink,
) {
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint),
    )
}

private fun lineIcon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0f, 0f, 0f, 1f)),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }.build()

private fun PathBuilder.addCross() {
    moveTo(12f, 5f)
    lineTo(12f, 19f)
    moveTo(5f, 12f)
    lineTo(19f, 12f)
}

private fun PathBuilder.closeCross() {
    moveTo(6f, 6f)
    lineTo(18f, 18f)
    moveTo(18f, 6f)
    lineTo(6f, 18f)
}

private fun PathBuilder.chevron(left: Boolean) {
    if (left) {
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    } else {
        moveTo(9f, 5f)
        lineTo(16f, 12f)
        lineTo(9f, 19f)
    }
}

private fun PathBuilder.home() {
    moveTo(4f, 10.5f)
    lineTo(12f, 4f)
    lineTo(20f, 10.5f)
    lineTo(20f, 20f)
    lineTo(4f, 20f)
    close()
    moveTo(9f, 20f)
    lineTo(9f, 14f)
    lineTo(15f, 14f)
    lineTo(15f, 20f)
}

private fun PathBuilder.grid() {
    moveTo(4f, 5f)
    lineTo(10f, 5f)
    lineTo(10f, 11f)
    lineTo(4f, 11f)
    close()
    moveTo(14f, 5f)
    lineTo(20f, 5f)
    lineTo(20f, 11f)
    lineTo(14f, 11f)
    close()
    moveTo(4f, 15f)
    lineTo(10f, 15f)
    lineTo(10f, 21f)
    lineTo(4f, 21f)
    close()
    moveTo(14f, 15f)
    lineTo(20f, 15f)
    lineTo(20f, 21f)
    lineTo(14f, 21f)
    close()
}

private fun PathBuilder.search() {
    moveTo(17.5f, 10.5f)
    curveTo(17.5f, 14.37f, 14.37f, 17.5f, 10.5f, 17.5f)
    curveTo(6.63f, 17.5f, 3.5f, 14.37f, 3.5f, 10.5f)
    curveTo(3.5f, 6.63f, 6.63f, 3.5f, 10.5f, 3.5f)
    curveTo(14.37f, 3.5f, 17.5f, 6.63f, 17.5f, 10.5f)
    close()
    moveTo(15.2f, 15.2f)
    lineTo(20.5f, 20.5f)
}

private fun PathBuilder.settings() {
    moveTo(4f, 7f)
    lineTo(20f, 7f)
    moveTo(4f, 12f)
    lineTo(20f, 12f)
    moveTo(4f, 17f)
    lineTo(20f, 17f)
    moveTo(8f, 4.5f)
    lineTo(8f, 9.5f)
    moveTo(16f, 9.5f)
    lineTo(16f, 14.5f)
    moveTo(10f, 14.5f)
    lineTo(10f, 19.5f)
}

private fun PathBuilder.scan() {
    moveTo(4f, 9f)
    lineTo(4f, 4f)
    lineTo(9f, 4f)
    moveTo(15f, 4f)
    lineTo(20f, 4f)
    lineTo(20f, 9f)
    moveTo(20f, 15f)
    lineTo(20f, 20f)
    lineTo(15f, 20f)
    moveTo(9f, 20f)
    lineTo(4f, 20f)
    lineTo(4f, 15f)
    moveTo(8f, 12f)
    lineTo(16f, 12f)
}

private fun PathBuilder.photos() {
    moveTo(4f, 5f)
    lineTo(20f, 5f)
    lineTo(20f, 19f)
    lineTo(4f, 19f)
    close()
    moveTo(5f, 17f)
    lineTo(9.5f, 12.5f)
    lineTo(13f, 16f)
    lineTo(15.2f, 13.8f)
    lineTo(19f, 17.6f)
    moveTo(16.5f, 9f)
    curveTo(16.5f, 9.83f, 15.83f, 10.5f, 15f, 10.5f)
    curveTo(14.17f, 10.5f, 13.5f, 9.83f, 13.5f, 9f)
    curveTo(13.5f, 8.17f, 14.17f, 7.5f, 15f, 7.5f)
    curveTo(15.83f, 7.5f, 16.5f, 8.17f, 16.5f, 9f)
    close()
}

private fun PathBuilder.months() {
    moveTo(5f, 6f)
    lineTo(19f, 6f)
    lineTo(19f, 20f)
    lineTo(5f, 20f)
    close()
    moveTo(8f, 4f)
    lineTo(8f, 8f)
    moveTo(16f, 4f)
    lineTo(16f, 8f)
    moveTo(5f, 10f)
    lineTo(19f, 10f)
    moveTo(8f, 14f)
    lineTo(10f, 14f)
    moveTo(13f, 14f)
    lineTo(15f, 14f)
    moveTo(8f, 17f)
    lineTo(10f, 17f)
    moveTo(13f, 17f)
    lineTo(15f, 17f)
}

private fun PathBuilder.eye(hidden: Boolean) {
    moveTo(3f, 12f)
    curveTo(5.2f, 8f, 8.25f, 6f, 12f, 6f)
    curveTo(15.75f, 6f, 18.8f, 8f, 21f, 12f)
    curveTo(18.8f, 16f, 15.75f, 18f, 12f, 18f)
    curveTo(8.25f, 18f, 5.2f, 16f, 3f, 12f)
    close()
    moveTo(15f, 12f)
    curveTo(15f, 13.66f, 13.66f, 15f, 12f, 15f)
    curveTo(10.34f, 15f, 9f, 13.66f, 9f, 12f)
    curveTo(9f, 10.34f, 10.34f, 9f, 12f, 9f)
    curveTo(13.66f, 9f, 15f, 10.34f, 15f, 12f)
    close()
    if (hidden) {
        moveTo(4f, 4f)
        lineTo(20f, 20f)
    }
}
