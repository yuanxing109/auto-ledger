package com.eta.autoledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import com.eta.autoledger.R
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.WidgetTotals
import com.eta.autoledger.ui.theme.LedgerColors
import com.eta.autoledger.ui.theme.LedgerPalette
import com.eta.autoledger.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 2×2 / 4×2 数据卡片。
 *
 * RemoteViews 只承载一个 [android.widget.ImageView]。卡片背景、文字和近 7 日趋势都由
 * Compose [CanvasDrawScope] 离屏绘制成 Bitmap，因此能直接复用 [LedgerPalette] 的浅深 token，
 * 同时避开 RemoteViews 对自定义 View 和动态样式的限制。
 */
open class LedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val totals = LedgerRepository.widgetTotals(appContext)
                appWidgetIds.forEach { id ->
                    safeRender(appContext, appWidgetManager, id, totals, frameFor(appWidgetManager, id, prefersWide))
                }
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val totals = LedgerRepository.widgetTotals(appContext)
                safeRender(
                    appContext,
                    appWidgetManager,
                    appWidgetId,
                    totals,
                    frameFromOptions(newOptions, prefersWide),
                )
            } finally {
                pending?.finish()
            }
        }
    }

    /** 这个 provider 在拿不到尺寸时的默认版式；宽版子类覆盖为 true。 */
    protected open val prefersWide: Boolean get() = false

    companion object {
        private const val TAG = "AutoLedger"
        private const val WIDE_MIN_WIDTH_DP = 200
        private const val MAX_BITMAP_PIXELS = 220_000
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun render(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            totals: WidgetTotals,
            frame: WidgetFrame,
        ) {
            val bitmap = renderBitmap(context, totals, frame)
            val layout = if (frame.wide) R.layout.widget_ledger_wide else R.layout.widget_ledger
            val views = RemoteViews(context.packageName, layout).apply {
                setImageViewBitmap(R.id.widget_image, bitmap)
                setContentDescription(R.id.widget_image, widgetDescription(totals, frame.wide))
                setOnClickPendingIntent(R.id.widget_image, openAdd(context))
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun safeRender(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            totals: WidgetTotals,
            frame: WidgetFrame,
        ) {
            if (!frame.wide) {
                render(context, manager, widgetId, totals, frame)
                return
            }
            runCatching { render(context, manager, widgetId, totals, frame) }
                .onFailure {
                    Log.w(TAG, "宽版小部件渲染失败，回退 2×2：${it.message}")
                    render(context, manager, widgetId, totals, frame.copy(wide = false, widthDp = 140))
                }
        }

        private fun frameFor(manager: AppWidgetManager, widgetId: Int, prefersWide: Boolean): WidgetFrame =
            runCatching { manager.getAppWidgetOptions(widgetId) }
                .fold(
                    onSuccess = { frameFromOptions(it, prefersWide) },
                    onFailure = { defaultFrame(prefersWide) },
                )

        private fun frameFromOptions(options: Bundle, prefersWide: Boolean): WidgetFrame {
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val wide = prefersWide || width >= WIDE_MIN_WIDTH_DP
            return WidgetFrame(
                wide = wide,
                widthDp = width.takeIf { it > 0 } ?: if (wide) 280 else 140,
                heightDp = height.takeIf { it > 0 } ?: 120,
            )
        }

        private fun defaultFrame(prefersWide: Boolean): WidgetFrame = WidgetFrame(
            wide = prefersWide,
            widthDp = if (prefersWide) 280 else 140,
            heightDp = 120,
        )

        private fun openAdd(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_ADD, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun renderBitmap(context: Context, totals: WidgetTotals, frame: WidgetFrame): Bitmap {
            val metrics = context.resources.displayMetrics
            // ImageView 在 launcher 端按 fitXY 缩放；限制渲染密度可避开 RemoteViews Binder 1MB 限制。
            val renderDensity = min(metrics.density, 2f)
            val requestedWidth = (frame.widthDp * renderDensity).roundToInt().coerceAtLeast(220)
            val requestedHeight = (frame.heightDp * renderDensity).roundToInt().coerceAtLeast(220)
            val bitmapScale = min(
                1f,
                sqrt(MAX_BITMAP_PIXELS.toFloat() / (requestedWidth * requestedHeight).toFloat()),
            )
            val widthPx = (requestedWidth * bitmapScale).roundToInt()
            val heightPx = (requestedHeight * bitmapScale).roundToInt()
            val outputDensity = renderDensity * bitmapScale
            val image = ImageBitmap(widthPx, heightPx)
            val dark = isDark(context)
            val palette = LedgerPalette.of(dark)
            val fontScale = context.resources.configuration.fontScale
            CanvasDrawScope().draw(
                density = Density(outputDensity, fontScale),
                layoutDirection = LayoutDirection.Ltr,
                canvas = ComposeCanvas(image),
                size = Size(widthPx.toFloat(), heightPx.toFloat()),
            ) {
                drawWidgetCard(totals, frame.wide, palette, outputDensity, outputDensity * fontScale)
            }
            return image.asAndroidBitmap()
        }

        private fun isDark(context: Context): Boolean = when (AppPrefs.get(context).themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }

        private fun widgetDescription(totals: WidgetTotals, wide: Boolean): String =
            if (wide) {
                "今日支出 ${widgetMoney(totals.todayExpense)}，本月支出 ${widgetMoney(totals.monthExpense)}，" +
                    "本月收入 ${widgetMoney(totals.monthIncome)}。点击记一笔"
            } else {
                "今日支出 ${widgetMoney(totals.todayExpense)}。点击记一笔"
            }

        /** 任何写操作后刷新两种规格的全部实例。 */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            components(context).forEach { component ->
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return@forEach
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    setComponent(component)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        /** 请求钉到桌面：[wide] 决定 4×2 还是 2×2。 */
        fun requestPin(context: Context, wide: Boolean): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            val target = if (wide) LedgerWidgetWideProvider::class.java else LedgerWidgetProvider::class.java
            if (!manager.isRequestPinAppWidgetSupported) return false
            return runCatching {
                manager.requestPinAppWidget(ComponentName(context, target), Bundle(), null)
            }.getOrDefault(false)
        }

        fun components(context: Context): List<ComponentName> = listOf(
            ComponentName(context, LedgerWidgetProvider::class.java),
            ComponentName(context, LedgerWidgetWideProvider::class.java),
        )
    }
}

data class WidgetFrame(
    val wide: Boolean,
    val widthDp: Int,
    val heightDp: Int,
)

class LedgerWidgetWideProvider : LedgerWidgetProvider() {
    override val prefersWide: Boolean get() = true
}

private fun DrawScope.drawWidgetCard(
    totals: WidgetTotals,
    wide: Boolean,
    palette: LedgerColors,
    density: Float,
    scaledDensity: Float,
) {
    val px = { value: Float -> value * density }
    val inset = px(4f)
    val cardSize = Size(size.width - inset * 2, size.height - inset * 2)
    val radius = CornerRadius(px(24f))
    drawRoundRect(
        color = palette.page,
        topLeft = Offset(inset, inset),
        size = cardSize,
        cornerRadius = radius,
    )
    drawRoundRect(
        color = palette.surface,
        topLeft = Offset(inset, inset),
        size = cardSize,
        cornerRadius = radius,
    )
    drawRoundRect(
        color = palette.borderSoft,
        topLeft = Offset(inset, inset),
        size = cardSize,
        cornerRadius = radius,
        style = Stroke(width = max(1f, px(1f))),
    )

    if (wide) {
        drawWideContent(totals, palette, density, scaledDensity, inset)
    } else {
        drawSmallContent(totals, palette, density, scaledDensity)
    }
}

private fun DrawScope.drawWideContent(
    totals: WidgetTotals,
    palette: LedgerColors,
    density: Float,
    scaledDensity: Float,
    inset: Float,
) {
    val px = { value: Float -> value * density }
    val left = inset + px(16f)
    val top = inset + px(16f)
    val right = size.width - inset - px(14f)
    val split = left + (right - left) * 0.52f
    val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Calendar.getInstance().time)
    val canvas = drawContext.canvas.nativeCanvas

    canvas.drawText("今日支出", left, top, textPaint(palette.textSecondary, 11f, scaledDensity))
    canvas.drawText(
        widgetMoney(totals.todayExpense),
        left,
        top + px(27f),
        numberPaint(palette.text, 24f, scaledDensity),
    )
    canvas.drawText(
        "本月支出  ${widgetMoney(totals.monthExpense)}",
        left,
        size.height - inset - px(20f),
        textPaint(palette.textSecondary, 10f, scaledDensity),
    )
    canvas.drawText(
        "本月收入  ${widgetMoney(totals.monthIncome)}",
        left,
        size.height - inset - px(6f),
        textPaint(palette.income, 10f, scaledDensity),
    )
    canvas.drawText(
        date,
        right,
        top,
        textPaint(palette.textTertiary, 10f, scaledDensity, Paint.Align.RIGHT),
    )

    drawLine(
        color = palette.divider,
        start = Offset(split, top + px(2f)),
        end = Offset(split, size.height - inset - px(10f)),
        strokeWidth = max(1f, px(1f)),
    )
    val chartLeft = split + px(10f)
    val chartTop = top + px(21f)
    val chartBottom = size.height - inset - px(12f)
    canvas.drawText(
        "近7天",
        right,
        top + px(13f),
        textPaint(palette.textTertiary, 9f, scaledDensity, Paint.Align.RIGHT),
    )
    drawSevenDayTrend(
        values = totals.sevenDayExpenses,
        left = chartLeft,
        top = chartTop,
        right = right,
        bottom = chartBottom,
        lineColor = palette.accent,
        gridColor = palette.divider,
        surfaceColor = palette.surface,
    )
}

private fun DrawScope.drawSmallContent(
    totals: WidgetTotals,
    palette: LedgerColors,
    density: Float,
    scaledDensity: Float,
) {
    val px = { value: Float -> value * density }
    val canvas = drawContext.canvas.nativeCanvas
    val centerX = size.width / 2f
    val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Calendar.getInstance().time)
    canvas.drawText(
        "今日支出",
        centerX,
        size.height * 0.30f,
        textPaint(palette.textSecondary, 11f, scaledDensity, Paint.Align.CENTER),
    )
    canvas.drawText(
        widgetMoney(totals.todayExpense),
        centerX,
        size.height * 0.58f,
        numberPaint(palette.text, 26f, scaledDensity, Paint.Align.CENTER),
    )
    canvas.drawText(
        date,
        centerX,
        size.height * 0.76f,
        textPaint(palette.textTertiary, 10f, scaledDensity, Paint.Align.CENTER),
    )
    drawCircle(
        color = palette.accent.copy(alpha = 0.10f),
        radius = px(20f),
        center = Offset(size.width - px(18f), px(18f)),
    )
}

private fun DrawScope.drawSevenDayTrend(
    values: List<Double>,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    lineColor: Color,
    gridColor: Color,
    surfaceColor: Color,
) {
    if (right <= left || bottom <= top) return
    val points = values.take(7).let { list -> if (list.size == 7) list else List(7) { 0.0 } }
    val maxValue = max(points.maxOrNull() ?: 0.0, 1.0)
    drawLine(gridColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1f)
    drawLine(
        gridColor.copy(alpha = 0.55f),
        Offset(left, top),
        Offset(right, top),
        strokeWidth = 1f,
    )
    val stepX = (right - left) / 6f
    val plotHeight = max(1f, bottom - top - 5f)
    val offsets = points.mapIndexed { index, value ->
        Offset(
            x = left + stepX * index,
            y = bottom - (value / maxValue).toFloat() * plotHeight,
        )
    }
    val linePath = Path().apply {
        moveTo(offsets.first().x, offsets.first().y)
        offsets.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = linePath,
        color = lineColor,
        style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    offsets.forEach { point ->
        drawCircle(surfaceColor, radius = 3f, center = point)
        drawCircle(lineColor, radius = 1.5f, center = point)
    }
}

private fun textPaint(
    color: Color,
    textSizeSp: Float,
    scaledDensity: Float,
    align: Paint.Align = Paint.Align.LEFT,
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb()
    textSize = textSizeSp * scaledDensity
    textAlign = align
    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    isSubpixelText = true
}

private fun numberPaint(
    color: Color,
    textSizeSp: Float,
    scaledDensity: Float,
    align: Paint.Align = Paint.Align.LEFT,
): Paint = textPaint(color, textSizeSp, scaledDensity, align).apply {
    typeface = Typeface.create("sans-serif", Typeface.BOLD)
    fontFeatureSettings = "tnum"
}

private fun widgetMoney(value: Double): String = "¥%,.2f".format(Locale.CHINA, value)
