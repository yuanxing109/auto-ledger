package com.eta.autoledger.ui.screen

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.util.AppDateFormats
import com.eta.autoledger.ui.rememberMotionDuration
import com.eta.autoledger.ui.components.AppDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eta.autoledger.data.CategoryAgg
import com.eta.autoledger.data.CategoryBreakdown
import com.eta.autoledger.data.ReportAggregate
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.ReportRepository
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.SubCategoryShare
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PageBg: Color get() = LedgerTheme.page
private val CardBorder: Color get() = LedgerTheme.borderSoft
private val TextPrimary: Color get() = LedgerTheme.text
private val TextSecondary: Color get() = LedgerTheme.textSecondary
private val AccentBlue: Color get() = LedgerTheme.accent
private val AccentSoft: Color get() = LedgerTheme.inset
private val TrackGray: Color get() = LedgerTheme.divider

/**
 * 一次「周期 key + 渲染数据」的快照。
 *
 * 交给 AnimatedContent 当 targetState：内容相等即不触发转场，内容不同则进出两帧
 * 各自持有自己那份数据 —— 交叉淡入时看到的才是「上个月的图」淡成「这个月的图」，
 * 而不是同一张图淡给自己看。
 *
 * 用 data class 是为了拿结构相等：每帧新建实例也不会被误判成状态变化。
 */
private data class ReportFrame(
    val tab: Int,
    val offset: Int,
    val income: Boolean,
    val subCategory: Boolean,
    val data: ReportData,
)

/**
 * 一次查询的结果连同「是哪个周期、哪个口径查出来的」。
 *
 * 单存 aggregate 不够：界面上的 tab/offset 可能已经走到下一次查询了，
 * 光看 aggregate 分不清这份数字属于哪个周期。把 key 一起存下来，
 * 渲染时就不会出现「标题已是年报、数字还是月报」。
 */
private data class LoadedReport(
    val tab: Int,
    val offset: Int,
    val income: Boolean,
    val aggregate: ReportAggregate,
)

/**
 * 周期/口径变化后的合并窗口。只用于「已有数据」之后的切换：
 * 连点箭头、或切 Tab 触发 offset 归零时，把一次交互里的多次状态变化合成一次查询。
 * 首次进页面不等这个时间。
 */
private const val QUERY_DEBOUNCE_MS = 120L

private data class TrendPoint(val label: String, val value: Double)

private val DonutPalette: List<Color> get() = LedgerTheme.chartSeries

private data class CategorySlice(
    val name: String,
    val displayName: String = name,
    val emoji: String,
    val color: Color,
    val amount: Double,
    val count: Int,
    val percent: Float,
    val subCategories: List<SubCategoryShare> = emptyList(),
) {
    val hasSubCategories: Boolean
        get() = subCategories.any { !it.name.isNullOrBlank() }
}

private data class CategorySource(
    val aggregate: CategoryAgg,
    val subCategories: List<SubCategoryShare>,
)

private data class ReportData(
    val periodLabel: String,
    val periodName: String,
    val compareLabel: String,
    val total: Double,
    val dailyAvg: Double,
    val changeAmount: Double,
    val balance: Double,
    val trend: List<TrendPoint>,
    val bars: List<TrendPoint>,
    val categories: List<CategorySlice>,
)

@Composable
fun ReportScreen(modifier: Modifier = Modifier, tab: Int = 1) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var income by rememberSaveable { mutableStateOf(false) }
    var subCategory by rememberSaveable { mutableStateOf(false) }
    var detailSlice by remember { mutableStateOf<CategorySlice?>(null) }
    // 常驻组合、只切 show：外面用 detailSlice?.let { } 包起来的话，一关闭组件就被移出组合，
    // AppDialog 接管的退场动画播不出来。留一份最后一次的切片给退场帧渲染。
    var lastDetailSlice by remember { mutableStateOf<CategorySlice?>(null) }
    LaunchedEffect(detailSlice) { if (detailSlice != null) lastDetailSlice = detailSlice }

    LaunchedEffect(tab) { offset = 0 }
    val context = LocalContext.current
    // 报表口径跟随「预算起始日」：起始日非 1 时，「本月」= 起始日 ~ 次月前一日。
    // 折线、柱状、分类环图、明细列表全部走同一个 range，保证四张图数据范围一致。
    val startDay = AppPrefs.get(context).budgetStartDay

    // null 表示「还没拿到第一份数据」。保留上一份直到新查询回来，
    // 这样切周期时数字不会先掉到 0 再跳回来。
    var loaded by remember { mutableStateOf<LoadedReport?>(null) }
    LaunchedEffect(tab, offset, income, startDay) {
        // 首次进页面立刻查，不再无条件干等。
        // 之后周期/口径变化时等一小段，把「连点箭头」和「切 Tab 时 offset 归零」
        // 这类同一次交互里的多次状态变化合并成一次查询 —— 这是原先 delay(220)
        // 想做的事，但它连首屏也一起罚了 220ms。
        if (loaded != null) delay(QUERY_DEBOUNCE_MS)
        ReportRepository.observe(context, tab, offset, income, startDay).collect {
            loaded = LoadedReport(tab, offset, income, it)
        }
    }

    // 转场：周期/口径变化时整块内容淡入淡出。
    // 时长走 rememberMotionDuration，系统「移除动画」开启时压到 0。
    val motion = rememberMotionDuration(180)

    // 转场要淡出的是「上一份数据」，不是「新数据再画一遍」。
    // 只按 tab/offset 换 key、内容却读外层最新状态的话，进出两帧画面完全一致，
    // 交叉淡入等于什么都没发生，还白搭一次全量重组（4 张 Canvas + 分类列表）。
    // 这里把「周期 + 口径 + 算好的数据」整体打包成快照交给 AnimatedContent，
    // 正在淡出的那一帧天然持有旧快照，不用手动留存上一份。
    val frame = remember(loaded, subCategory) {
        loaded?.let {
            ReportFrame(
                tab = it.tab,
                offset = it.offset,
                income = it.income,
                subCategory = subCategory,
                data = toReportData(it.aggregate, it.tab, it.income, subCategory),
            )
        }
    }
    // 滚动位置放在 AnimatedContent 外面：放里面的话每次切周期都会新建一个
    // ScrollState，用户滚到一半换个月就弹回顶部。
    val reportScroll = rememberScrollState()

    Box(modifier = modifier.fillMaxSize().background(PageBg)) {
        AnimatedContent(
            targetState = frame,
            transitionSpec = { fadeIn(tween(motion)) togetherWith fadeOut(tween(motion)) },
            // contentKey 只认「周期 + 口径」：同一周期内数据被刷新（别处记了一笔、
            // 周期账单补跑）时原地重组即可，不该整块淡一次 —— 那会让报表无端闪。
            contentKey = { it?.let { f -> listOf(f.tab, f.offset, f.income, f.subCategory) } },
            label = "report-period",
        ) { snapshot ->
            // 首帧还没有数据：给一句轻提示，而不是先画一屏 0 元、空折线、空环图。
            if (snapshot == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "正在统计…",
                        style = MiuixTheme.textStyles.footnote1,
                        color = TextSecondary,
                    )
                }
                return@AnimatedContent
            }
            val frameData = snapshot.data
            // 淡出帧用自己的 tab/income 渲染，否则「月报」淡出时标题已变成「年报」，
            // 画面自相矛盾。交互回调仍改外层 state，触发下一次查询。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(reportScroll)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 82.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeriodRow(
                    periodLabel = frameData.periodLabel,
                    income = snapshot.income,
                    onPrev = { offset += 1 },
                    onNext = { if (offset > 0) offset -= 1 },
                    onMode = { income = it },
                )

                SummaryCard(data = frameData, tab = snapshot.tab, income = snapshot.income)

                LineCard(data = frameData, income = snapshot.income)

                BarCard(data = frameData, income = snapshot.income)

                DonutCard(
                    data = frameData,
                    income = snapshot.income,
                    subCategory = subCategory,
                    onSubCategory = { subCategory = it },
                    onOpenSlice = { detailSlice = it },
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    val range = remember(tab, offset, startDay) { ReportRepository.periodRange(tab, offset, startDay) }
    lastDetailSlice?.let { slice ->
        CategoryDetailDialog(
            show = detailSlice != null,
            slice = slice,
            startMillis = range.first,
            endMillis = range.second,
            onDismiss = { detailSlice = null },
        )
    }
}

@Composable
private fun PeriodRow(
    periodLabel: String,
    income: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMode: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleArrow(onClick = onPrev, forward = false)
        Text(
            text = periodLabel,
            style = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        CircleArrow(onClick = onNext, forward = true)
        Spacer(Modifier.width(8.dp))
        ModePill(income = income, onMode = onMode)
    }
}

@Composable
private fun CircleArrow(onClick: () -> Unit, forward: Boolean) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(1.dp, CardBorder, CircleShape)
                .background(LedgerTheme.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (forward) MiuixIcons.ChevronForward else MiuixIcons.ChevronBackward,
                contentDescription = if (forward) "下一周期" else "上一周期",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ModePill(income: Boolean, onMode: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AccentSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "支出", true to "收入").forEach { (isIncome, label) ->
            val selected = income == isIncome
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) AccentBlue else Color.Transparent)
                    .clickable { onMode(isIncome) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    style = TextStyle(fontSize = 12.sp, color = if (selected) LedgerTheme.onAccent else TextSecondary),
                )
            }
        }
    }
}

@Composable
private fun ReportCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cardShape = RoundedCornerShape(LocalDimens.current.radiusCard)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, cardShape),
    ) {
        content()
    }
}

@Composable
private fun Sparkle() {
    Text(text = "✦", style = MiuixTheme.textStyles.body2.copy(color = AccentBlue))
}

@Composable
private fun SummaryCard(data: ReportData, tab: Int, income: Boolean) {
    ReportCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                SummaryCell(
                    label = "${data.periodName}${if (income) "收入" else "支出"}（元）",
                    value = "%,.2f".format(data.total),
                    modifier = Modifier.weight(1f),
                )
                SummaryCell(
                    label = "日均${if (income) "收入" else "支出"}（元）",
                    value = "%,.2f".format(data.dailyAvg),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row {
                SummaryCell(
                    label = "${data.compareLabel}${if (income) "收入" else "支出"}（元）",
                    value = "%+,.2f".format(data.changeAmount),
                    valueColor = if (data.changeAmount < 0) LedgerTheme.danger else TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                SummaryCell(
                    label = "收支结余（元）",
                    value = "%+,.2f".format(data.balance),
                    valueColor = AccentBlue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
) {
    Column(modifier = modifier) {
        Text(text = label, style = TextStyle(fontSize = 12.sp, color = TextSecondary))
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = TextStyle(fontSize = 22.sp, color = valueColor))
    }
}

@Composable
private fun LineCard(data: ReportData, income: Boolean) {
    ReportCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkle()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${data.periodName}趋势",
                    style = MiuixTheme.textStyles.body1.copy(color = TextPrimary),
                )
            }
            Spacer(Modifier.height(8.dp))
            LineChart(points = data.trend, income = income)
        }
    }
}

@Composable
private fun LineChart(points: List<TrendPoint>, income: Boolean) {
    val density = LocalDensity.current
    val padLeft = with(density) { 44.dp.toPx() }
    val padRight = with(density) { 8.dp.toPx() }
    val padTop = with(density) { 12.dp.toPx() }
    val padBottom = with(density) { 8.dp.toPx() }
    val chartHeight = 176.dp
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val lineColor = if (income) AccentBlue else AccentBlue
    val gridColor = LedgerTheme.divider
    val labelStyle =MiuixTheme.textStyles.footnote2.copy(color = TextSecondary)
    val bubbleWidth = with(density) { 108.dp.toPx() }
    val bubbleHeight = with(density) { 44.dp.toPx() }

    fun xAt(index: Int): Float {
        if (points.size <= 1) return padLeft
        return padLeft + index * (chartSize.width - padLeft - padRight) / (points.size - 1)
    }

    fun yAt(value: Double, max: Double): Float {
        val usable = chartSize.height - padTop - padBottom
        return padTop + (1f - (value / max).toFloat()) * usable
    }

    fun pointIndexAt(x: Float): Int {
        if (points.isEmpty() || chartSize.width == 0) return -1
        if (points.size == 1) return 0
        val step = (chartSize.width - padLeft - padRight) / (points.size - 1)
        return ((x - padLeft) / step).roundToInt().coerceIn(0, points.size - 1)
    }

    fun applySelection(x: Float) {
        val i = pointIndexAt(x)
        if (i >= 0) selected = i
    }

    Column {
        Box(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .onSizeChanged { chartSize = it }
                    .pointerInput(points) {
                        detectTapGestures { tap -> applySelection(tap.x) }
                    }
                    .pointerInput(points) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> applySelection(offset.x) },
                            onHorizontalDrag = { change, _ ->
                                applySelection(change.position.x)
                                change.consume()
                            },
                        )
                    },
            ) {
                if (points.isEmpty() || chartSize.width == 0) return@Canvas
                val max = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

                for (i in 0..4) {
                    val value = max * i / 4.0
                    val y = yAt(value, max)
                    drawLine(
                        color = gridColor,
                        start = Offset(padLeft, y),
                        end = Offset(chartSize.width - padRight, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                    val label = if (i == 0) "0" else "%,.2f".format(value)
                    val layout = textMeasurer.measure(label, labelStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(0f, y - layout.size.height / 2f),
                    )
                }

                val line = Path()
                points.forEachIndexed { index, point ->
                    val x = xAt(index)
                    val y = yAt(point.value, max)
                    if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(xAt(points.lastIndex), chartSize.height - padBottom)
                    lineTo(xAt(0), chartSize.height - padBottom)
                    close()
                }
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        listOf(AccentBlue.copy(alpha = 0.16f), AccentBlue.copy(alpha = 0.01f)),
                    ),
                )
                drawPath(
                    path = line,
                    color = lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                val guideIndex = selected ?: -1
                if (guideIndex in points.indices) {
                    val gx = xAt(guideIndex)
                    drawLine(
                        color = lineColor.copy(alpha = 0.35f),
                        start = Offset(gx, padTop),
                        end = Offset(gx, chartSize.height - padBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }
                points.forEachIndexed { index, point ->
                    val center = Offset(xAt(index), yAt(point.value, max))
                    // 端点用 onAccent：浅色主题下是白点压在蓝线上，深色主题下是深点压在亮蓝线上，两个方向都清晰
                    drawCircle(LedgerTheme.onAccent, radius = 4.dp.toPx(), center = center)
                    drawCircle(lineColor, radius = 2.5.dp.toPx(), center = center)
                }
            }

            val index = selected ?: -1
            if (index >= 0 && chartSize.width > 0 && points.isNotEmpty()) {
                val max = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)
                val px = xAt(index)
                val py = yAt(points[index].value, max)
                val left = (px - bubbleWidth / 2).coerceIn(0f, (chartSize.width - bubbleWidth).coerceAtLeast(0f))
                val top = (py - bubbleHeight - with(density) { 6.dp.toPx() }).coerceAtLeast(0f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .width(108.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentBlue)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            text = "${points[index].label}${if (income) "收入" else "支出"}",
                            style =MiuixTheme.textStyles.footnote2.copy(color = LedgerTheme.onAccent.copy(alpha = 0.8f)),
                        )
                        Text(
                            text = "¥ %,.2f".format(points[index].value),
                            style = MiuixTheme.textStyles.footnote2.copy(color = LedgerTheme.onAccent),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 44.dp, top = 4.dp, end = 8.dp),
        ) {
            points.forEach { point ->
                Text(
                    text = point.label,
                    style =MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BarCard(data: ReportData, income: Boolean) {
    ReportCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkle()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${if (income) "收入" else "支出"}趋势",
                    style = MiuixTheme.textStyles.body1.copy(color = TextPrimary),
                )
            }
            Spacer(Modifier.height(8.dp))
            BarChart(points = data.bars, income = income)
        }
    }
}

@Composable
private fun BarChart(points: List<TrendPoint>, income: Boolean) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle =MiuixTheme.textStyles.footnote2.copy(color = TextSecondary)
    val gridColor = LedgerTheme.divider
    val barColor = AccentBlue
    val padLeftDp = 44.dp
    val chartHeight = 176.dp
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val bubbleWidth = with(density) { 112.dp.toPx() }
    val bubbleHeight = with(density) { 42.dp.toPx() }

    fun barIndexAt(x: Float): Int {
        if (points.isEmpty() || chartSize.width == 0) return -1
        val left = with(density) { padLeftDp.toPx() }
        val right = with(density) { 4.dp.toPx() }
        val gap = with(density) { 8.dp.toPx() }
        val plotWidth = chartSize.width - left - right
        val barWidth = (plotWidth - gap * (points.size - 1)) / points.size
        return ((x - left) / (barWidth + gap)).toInt().coerceIn(0, points.size - 1)
    }

    fun applyBarSelection(x: Float) {
        val i = barIndexAt(x)
        if (i >= 0) selected = i
    }

    Column {
        Box(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .onSizeChanged { chartSize = it }
                    .pointerInput(points) {
                        detectTapGestures { tap -> applyBarSelection(tap.x) }
                    }
                    .pointerInput(points) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> applyBarSelection(offset.x) },
                            onHorizontalDrag = { change, _ ->
                                applyBarSelection(change.position.x)
                                change.consume()
                            },
                        )
                    },
            ) {
                if (points.isEmpty() || chartSize.width == 0) return@Canvas
                val left = padLeftDp.toPx()
                val right = 4.dp.toPx()
                val bottom = size.height - 6.dp.toPx()
                val top = 6.dp.toPx()
                val max = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

                for (i in 0..4) {
                    val value = max * i / 4.0
                    val y = bottom - (value / max * (bottom - top)).toFloat()
                    drawLine(
                        color = gridColor,
                        start = Offset(left, y),
                        end = Offset(size.width - right, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                    val label = if (i == 0) "0" else "%,.2f".format(value)
                    val layout = textMeasurer.measure(label, labelStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(0f, y - layout.size.height / 2f),
                    )
                }

                val gap = 8.dp.toPx()
                val plotWidth = size.width - left - right
                val slotWidth = (plotWidth - gap * (points.size - 1)) / points.size
                val barWidth = slotWidth * 0.81f
                val activeIndex = selected ?: -1
                points.forEachIndexed { index, point ->
                    val barHeight = (point.value / max * (bottom - top)).toFloat()
                    val x = left + index * (slotWidth + gap) + (slotWidth - barWidth) / 2f
                    val isActive = index == activeIndex
                    val isLast = index == points.lastIndex
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                barColor.copy(alpha = if (isActive || isLast) 0.95f else 0.55f),
                                barColor.copy(alpha = if (isActive || isLast) 0.7f else 0.28f),
                            ),
                        ),
                        topLeft = Offset(x, bottom - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                    )
                }
            }

            val index = selected ?: -1
            if (index >= 0 && chartSize.width > 0 && points.isNotEmpty()) {
                val leftPad = with(density) { padLeftDp.toPx() }
                val rightPad = with(density) { 4.dp.toPx() }
                val gap = with(density) { 8.dp.toPx() }
                val plotWidth = chartSize.width - leftPad - rightPad
                val barWidth = (plotWidth - gap * (points.size - 1)) / points.size
                val max = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)
                val bottom = chartSize.height - with(density) { 6.dp.toPx() }
                val top = with(density) { 6.dp.toPx() }
                val barHeight = (points[index].value / max * (bottom - top)).toFloat()
                val centerX = leftPad + index * (barWidth + gap) + barWidth / 2f
                val left = (centerX - bubbleWidth / 2f)
                    .coerceIn(0f, (chartSize.width - bubbleWidth).coerceAtLeast(0f))
                val topY = (bottom - barHeight - bubbleHeight - with(density) { 4.dp.toPx() })
                    .coerceAtLeast(0f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), topY.roundToInt()) }
                        .width(112.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentBlue)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            text = "¥ %,.2f".format(points[index].value),
                            style = MiuixTheme.textStyles.footnote2.copy(color = LedgerTheme.onAccent),
                        )
                        Text(
                            text = points[index].label,
                            style =MiuixTheme.textStyles.footnote2.copy(color = LedgerTheme.onAccent.copy(alpha = 0.8f)),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = padLeftDp, top = 4.dp, end = 4.dp),
        ) {
            points.forEach { point ->
                Text(
                    text = point.label,
                    style =MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DonutCard(
    data: ReportData,
    income: Boolean,
    subCategory: Boolean,
    onSubCategory: (Boolean) -> Unit,
    onOpenSlice: (CategorySlice) -> Unit,
) {
    val dimens = LocalDimens.current
    val expandMillis = rememberMotionDuration(160)
    var expandedCategory by rememberSaveable(
        subCategory,
        data.categories.map { it.displayName },
    ) { mutableStateOf<String?>(null) }
    ReportCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkle()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${if (income) "收入" else "支出"}分类构成",
                    style = MiuixTheme.textStyles.body1.copy(color = TextPrimary),
                )
                Spacer(Modifier.weight(1f))
                SubCategoryPill(subCategory = subCategory, onSelect = onSubCategory)
            }
            Spacer(Modifier.height(4.dp))
            if (subCategory && data.categories.isEmpty()) {
                Text(
                    text = "暂无子分类数据",
                    style = MiuixTheme.textStyles.body2,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimens.gapLarge * 2),
                )
            } else {
                DonutChart(slices = data.categories, income = income)
                Spacer(Modifier.height(8.dp))
                // 排序移进 remember：原来直接写在组合体里，DonutCard 每次重组（切 Tab、
                // 点切片、父级任意状态变化）都要重排一遍。categories 引用不变就不重排。
                val sortedSlices = remember(data.categories) {
                    data.categories.sortedByDescending { it.amount }
                }
                sortedSlices.forEachIndexed { index, slice ->
                    CategoryRow(
                        rank = index + 1,
                        slice = slice,
                        expanded = expandedCategory == slice.name,
                        motionMillis = expandMillis,
                        detailEnabled = !subCategory,
                        onToggle = {
                            expandedCategory = if (expandedCategory == slice.name) null else slice.name
                        },
                        onOpen = { onOpenSlice(slice) },
                    )
                    if (index != sortedSlices.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SubCategoryPill(subCategory: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AccentSoft)
            .padding(4.dp),
    ) {
        listOf(false to "主分类", true to "子分类").forEach { (isSub, label) ->
            val selected = subCategory == isSub
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) AccentBlue else Color.Transparent)
                    .clickable { onSelect(isSub) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    style =MiuixTheme.textStyles.footnote2.copy(color = if (selected) LedgerTheme.onAccent else TextSecondary),
                )
            }
        }
    }
}

@Composable
private fun DonutChart(slices: List<CategorySlice>, income: Boolean) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle =MiuixTheme.textStyles.footnote2.copy(color = LedgerTheme.textSecondary)
    val centerAmountStyle = MiuixTheme.textStyles.main.copy(color = TextPrimary)
    val centerLabelStyle =MiuixTheme.textStyles.footnote2.copy(color = LedgerTheme.textTertiary)
    val total = slices.sumOf { it.amount }
    Box(modifier = Modifier.fillMaxWidth().height(272.dp)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (slices.isEmpty()) return@Canvas
            val stroke = 26.dp.toPx()
            val center = Offset(size.width / 2, size.height / 2)
            val labelColumn = 86.dp.toPx()
            val radius = (size.width / 2 - labelColumn - stroke / 2)
                .coerceAtMost(size.height / 2 - stroke / 2 - 12.dp.toPx())

            fun xAt(deg: Float, r: Float) = center.x + cos(Math.toRadians(deg.toDouble())).toFloat() * r
            fun yAt(deg: Float, r: Float) = center.y + sin(Math.toRadians(deg.toDouble())).toFloat() * r

            // 归一化到 360°，避免百分比累计误差在闭合处留下缺口
            val totalPercent = slices.sumOf { it.percent.toDouble() }.coerceAtLeast(0.0001)
            val sweeps = FloatArray(slices.size)
            val mids = FloatArray(slices.size)
            var angle = -90f
            slices.forEachIndexed { i, slice ->
                val sweep = (slice.percent / totalPercent * 360.0).toFloat()
                sweeps[i] = sweep
                mids[i] = angle + sweep / 2
                angle += sweep
            }

            var start = -90f
            slices.forEachIndexed { i, slice ->
                // 每段之间留一条细白间隙，小扇形按比例收窄，不再被切掉一大块
                val gap = (sweeps[i] * 0.14f).coerceIn(0.5f, 1.6f)
                drawArc(
                    color = slice.color,
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweeps[i] - gap).coerceAtLeast(0.3f),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                start += sweeps[i]
            }

            // 中心留白：放置合计金额
            val amountLayout = textMeasurer.measure("¥ %,.2f".format(total), centerAmountStyle)
            val capLayout = textMeasurer.measure(if (income) "收入合计" else "支出合计", centerLabelStyle)
            val blockHeight = amountLayout.size.height + 3.dp.toPx() + capLayout.size.height
            val blockTop = center.y - blockHeight / 2f
            drawText(
                textLayoutResult = amountLayout,
                topLeft = Offset(center.x - amountLayout.size.width / 2f, blockTop),
            )
            drawText(
                textLayoutResult = capLayout,
                topLeft = Offset(
                    center.x - capLayout.size.width / 2f,
                    blockTop + amountLayout.size.height + 3.dp.toPx(),
                ),
            )

            val outerR = radius + stroke / 2
            val minGap = 17.dp.toPx()
            val topLimit = 10.dp.toPx()
            val bottomLimit = size.height - 10.dp.toPx()

            fun resolvePositions(raw: List<Pair<Int, Float>>): List<Pair<Int, Float>> {
                val sorted = raw.sortedBy { it.second }.toMutableList()
                for (k in 1 until sorted.size) {
                    val prev = sorted[k - 1].second + minGap
                    if (sorted[k].second < prev) sorted[k] = sorted[k].first to prev
                }
                var overflow = (sorted.lastOrNull()?.second ?: 0f) - bottomLimit
                if (overflow > 0f) {
                    for (k in sorted.indices.reversed()) {
                        val limit = if (k == sorted.lastIndex) bottomLimit else sorted[k + 1].second - minGap
                        if (sorted[k].second > limit) sorted[k] = sorted[k].first to limit
                    }
                }
                overflow = topLimit - (sorted.firstOrNull()?.second ?: topLimit)
                if (overflow > 0f) {
                    for (k in sorted.indices) sorted[k] = sorted[k].first to (sorted[k].second + overflow)
                }
                return sorted
            }

            val rightRaw = mutableListOf<Pair<Int, Float>>()
            val leftRaw = mutableListOf<Pair<Int, Float>>()
            mids.forEachIndexed { i, deg ->
                val y = (center.y + sin(Math.toRadians(deg.toDouble())).toFloat() * outerR * 0.98f)
                    .coerceIn(topLimit, bottomLimit)
                if (cos(Math.toRadians(deg.toDouble())) >= 0) rightRaw += i to y else leftRaw += i to y
            }
            val placements = resolvePositions(rightRaw) + resolvePositions(leftRaw)

            placements.forEach { (i, labelY) ->
                val deg = mids[i]
                val sign = if (cos(Math.toRadians(deg.toDouble())) >= 0) 1f else -1f
                val edge = Offset(xAt(deg, outerR), yAt(deg, outerR))
                val stub = Offset(xAt(deg, outerR + 7.dp.toPx()), yAt(deg, outerR + 7.dp.toPx()))
                val elbowX = center.x + sign * (outerR + 12.dp.toPx())
                val kink = Offset(elbowX, labelY)
                val end = Offset(elbowX + sign * 5.dp.toPx(), labelY)
                val lineColor = slices[i].color.copy(alpha = 0.55f)
                drawLine(lineColor, edge, stub, strokeWidth = 1.dp.toPx())
                drawLine(lineColor, stub, kink, strokeWidth = 1.dp.toPx())
                drawLine(lineColor, kink, end, strokeWidth = 1.dp.toPx())

                val percent = sweeps[i] / 3.6f
                val label = "${slices[i].displayName} %.2f%%".format(percent)
                val layout = textMeasurer.measure(label, labelStyle)
                val textLeft = if (sign > 0) end.x + 3.dp.toPx()
                else end.x - layout.size.width - 3.dp.toPx()
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(textLeft, labelY - layout.size.height / 2f),
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    rank: Int,
    slice: CategorySlice,
    expanded: Boolean,
    motionMillis: Int,
    detailEnabled: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val dimens = LocalDimens.current
    val rowShape = RoundedCornerShape(dimens.radiusSmall)
    val interactionSource = remember { MutableInteractionSource() }
    val arrowRotation by animateFloatAsState(
        targetValue = if (slice.hasSubCategories && expanded) 90f else 0f,
        animationSpec = tween(motionMillis),
        label = "report-category-chevron",
    )
    val actionLabel = when {
        slice.hasSubCategories && expanded -> "收起${slice.name}子分类"
        slice.hasSubCategories -> "展开${slice.name}子分类"
        else -> "查看${slice.name}分类明细"
    }
    val sortedSubCategories = remember(slice.subCategories) {
        slice.subCategories.sortedByDescending { it.total }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(rowShape)
                .clickable(
                    enabled = detailEnabled,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClickLabel = actionLabel.takeIf { detailEnabled },
                ) {
                    if (slice.hasSubCategories) onToggle() else onOpen()
                }
                .semantics {
                    if (detailEnabled) {
                        role = Role.Button
                        if (slice.hasSubCategories) {
                            stateDescription = if (expanded) "已展开" else "已折叠"
                        }
                    }
                }
                .padding(vertical = dimens.gapSmall / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = TextStyle(fontSize = 12.sp, color = TextSecondary),
                modifier = Modifier.width(20.dp),
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(slice.color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = slice.emoji, fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = slice.displayName,
                        style = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${slice.count}笔",
                        style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "%.2f%% · ¥ %,.2f".format(slice.percent, slice.amount),
                        style = TextStyle(fontSize = 12.sp, color = TextPrimary),
                    )
                    if (detailEnabled) {
                        Icon(
                            imageVector = MiuixIcons.ChevronForward,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(arrowRotation),
                        )
                    } else {
                        Spacer(Modifier.width(14.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TrackGray),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((slice.percent / 100f).coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(slice.color),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && slice.hasSubCategories,
            enter = expandVertically(tween(motionMillis), expandFrom = Alignment.Top) +
                fadeIn(tween(motionMillis)),
            exit = shrinkVertically(tween(motionMillis), shrinkTowards = Alignment.Top) +
                fadeOut(tween(motionMillis)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dimens.cardPadding, top = dimens.gapSmall),
            ) {
                sortedSubCategories.forEach { child ->
                    SubCategoryRow(child)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClickLabel = "查看${slice.name}分类明细",
                        ) { onOpen() }
                        .padding(vertical = dimens.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "查看分类明细",
                        style = MiuixTheme.textStyles.footnote1.copy(color = AccentBlue),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubCategoryRow(share: SubCategoryShare) {
    val dimens = LocalDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = share.name?.takeIf { it.isNotBlank() } ?: "未分类",
            style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.2f%% · ¥ %,.2f · %d笔".format(share.percent, share.total, share.count),
            style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
        )
    }
}

private fun toReportData(
    aggregate: ReportAggregate,
    tab: Int,
    income: Boolean,
    subCategory: Boolean,
): ReportData {
    val trend = when (tab) {
        0 -> aggregate.trend.map { TrendPoint(it.bucket.takeLast(5).replace("-", "."), it.total) }
        1 -> {
            if (aggregate.trend.isEmpty()) emptyList()
            else {
                val grouped = aggregate.trend.chunked(3).map { chunk ->
                    val label = chunk.first().bucket.takeLast(5).replace("-", ".")
                    TrendPoint(label, chunk.sumOf { it.total })
                }
                grouped
            }
        }
        else -> aggregate.trend.map { bucket ->
            val month = bucket.bucket.substringAfter("-").toIntOrNull() ?: 0
            TrendPoint("${month}月", bucket.total)
        }
    }
    val bars = aggregate.bars.map { bucket ->
        val label = when (tab) {
            0 -> bucket.bucket.substringAfter("-").toIntOrNull()?.let { "${it}周" } ?: bucket.bucket
            1 -> bucket.bucket.substringAfter("-").toIntOrNull()?.let { "${it}月" } ?: bucket.bucket
            else -> bucket.bucket
        }
        TrendPoint(label, bucket.total)
    }
    val categories = if (subCategory) {
        toSubCategorySlices(aggregate)
    } else {
        toMainCategorySlices(aggregate)
    }
    return ReportData(
        periodLabel = aggregate.periodLabel,
        periodName = aggregate.periodName,
        compareLabel = aggregate.compareLabel,
        total = aggregate.total,
        dailyAvg = if (aggregate.dayCount > 0) aggregate.total / aggregate.dayCount else 0.0,
        changeAmount = aggregate.total - aggregate.previousTotal,
        balance = aggregate.income - aggregate.expense,
        trend = trend,
        bars = bars,
        categories = categories,
    )
}

/** 主分类模式保留 Top6 + 其他逻辑，同时把子分类挂到对应主分类。 */
private fun toMainCategorySlices(aggregate: ReportAggregate): List<CategorySlice> {
    val total = aggregate.categories.sumOf { it.total }.coerceAtLeast(0.0001)
    val breakdowns = aggregate.categoryBreakdowns.associateBy { it.name }
    val sources = aggregate.categories.take(6)
        .map { category -> categorySource(category, breakdowns) }
        .toMutableList()
    val tail = aggregate.categories.drop(6)
    if (tail.isNotEmpty()) {
        val tailSources = tail.map { category -> categorySource(category, breakdowns) }
        val tailAmount = tail.sumOf { it.total }
        val tailCount = tail.sumOf { it.count }
        val otherIndex = sources.indexOfFirst { it.aggregate.name == "其他" }
        if (otherIndex >= 0) {
            val existing = sources[otherIndex]
            val mergedTotal = existing.aggregate.total + tailAmount
            sources[otherIndex] = CategorySource(
                aggregate = existing.aggregate.copy(
                    total = mergedTotal,
                    count = existing.aggregate.count + tailCount,
                ),
                subCategories = mergeSubCategories(
                    existing.subCategories + tailSources.flatMap { it.subCategories },
                    mergedTotal,
                ),
            )
        } else {
            sources += CategorySource(
                aggregate = tail.first().copy(
                    name = "其他",
                    total = tailAmount,
                    count = tailCount,
                ),
                subCategories = mergeSubCategories(
                    tailSources.flatMap { it.subCategories },
                    tailAmount,
                ),
            )
        }
    }

    val usedColors = mutableListOf<Color>()
    return sources.map { source ->
        val category = source.aggregate
        CategorySlice(
            name = category.name.ifBlank { "其他" },
            emoji = categoryEmoji(category.name),
            color = allocateCategoryColor(category.name, usedColors),
            amount = category.total,
            count = category.count,
            percent = (category.total / total * 100f).toFloat(),
            subCategories = source.subCategories,
        )
    }
}

/** 子分类模式把所有真实非空子分类展平，并用全量分类总额重算占比。 */
private fun toSubCategorySlices(aggregate: ReportAggregate): List<CategorySlice> {
    if (aggregate.categoryBreakdowns.none { it.hasSubCategories }) return emptyList()
    val rows = aggregate.categoryBreakdowns.flatMap { breakdown ->
        breakdown.subCategories.map { child -> breakdown to child }
    }
    val total = rows.sumOf { it.second.total }.coerceAtLeast(0.0001)
    val usedColors = mutableListOf<Color>()
    return rows.map { (breakdown, child) ->
        val childName = child.name?.takeIf { it.isNotBlank() } ?: "未分类"
        CategorySlice(
            name = breakdown.name.ifBlank { "其他" },
            displayName = "${breakdown.name.ifBlank { "其他" }} · $childName",
            emoji = categoryEmoji(breakdown.name),
            color = allocateCategoryColor(breakdown.name, usedColors),
            amount = child.total,
            count = child.count,
            percent = (child.total / total * 100f).toFloat(),
        )
    }.sortedByDescending { it.amount }
}

private fun categorySource(
    category: CategoryAgg,
    breakdowns: Map<String, CategoryBreakdown>,
): CategorySource {
    val children = breakdowns[category.name]?.subCategories.orEmpty().ifEmpty {
        if (category.total > 0.0) {
            listOf(SubCategoryShare(null, category.total, category.count, 100f))
        } else {
            emptyList()
        }
    }
    return CategorySource(category, children)
}

private fun mergeSubCategories(
    shares: List<SubCategoryShare>,
    total: Double,
): List<SubCategoryShare> =
    shares.groupBy { it.name?.takeIf { name -> name.isNotBlank() } }
        .map { (name, group) ->
            val amount = group.sumOf { it.total }
            val count = group.sumOf { it.count }
            SubCategoryShare(
                name = name,
                total = amount,
                count = count,
                percent = if (total > 0.0) (amount / total * 100.0).toFloat() else 0f,
            )
        }
        .sortedWith(compareByDescending<SubCategoryShare> { it.total }.thenBy { it.name.orEmpty() })

private fun allocateCategoryColor(name: String, usedColors: MutableList<Color>): Color {
    val base = categoryColor(name)
    val color = if (base in usedColors) {
        DonutPalette.firstOrNull { it !in usedColors } ?: base
    } else {
        base
    }
    usedColors += color
    return color
}

private fun categoryEmoji(category: String): String = when (category) {
    "餐饮" -> "🍜"
    "购物" -> "🛍️"
    "转账" -> "💸"
    "交通" -> "🚌"
    "消费" -> "💳"
    "人情社交" -> "👥"
    "娱乐" -> "🎮"
    "教育" -> "📚"
    "通讯" -> "📱"
    "美容" -> "💄"
    "医疗" -> "🏥"
    "红包" -> "🧧"
    "旅行" -> "✈️"
    "宠物" -> "🐾"
    "住房" -> "🏠"
    "代付" -> "🤝"
    "投资" -> "📈"
    "借出" -> "📤"
    "还债" -> "💰"
    "亲子" -> "👨‍👩‍👧"
    "工资", "薪资" -> "💼"
    "退款" -> "↩️"
    "理财" -> "📈"
    "借入" -> "📥"
    "收债" -> "📤"
    "提现" -> "💵"
    "还款" -> "💳"
    "充值" -> "⚡"
    else -> "⚪"
}

private fun categoryColor(category: String): Color = when (category) {
    "餐饮" -> LedgerTheme.chart3
    "购物" -> LedgerTheme.chart4
    "转账" -> AccentBlue
    "交通" -> LedgerTheme.chart5
    "红包" -> LedgerTheme.danger
    "旅行" -> LedgerTheme.chart5
    "医疗" -> LedgerTheme.danger
    "教育" -> LedgerTheme.chart4
    "娱乐" -> LedgerTheme.chart3
    "薪资", "工资" -> LedgerTheme.chart2
    "理财", "投资" -> LedgerTheme.chart4
    "退款" -> LedgerTheme.chart2
    "提现", "充值" -> LedgerTheme.chart3
    else -> LedgerTheme.chart5
}

@Composable
private fun CategoryDetailDialog(
    show: Boolean,
    slice: CategorySlice,
    startMillis: Long,
    endMillis: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val entries by produceState<List<LedgerEntry>>(initialValue = emptyList(), slice.name, startMillis) {
        LedgerRepository.search(
            context,
            minAmount = 0.0,
            maxAmount = Double.MAX_VALUE,
            start = startMillis,
            end = endMillis,
            type = "",
            categories = listOf(slice.name),
            keyword = "",
            limit = 300,
        ).collect { value = it }
    }
    val maxListHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.5f
    }
    AppDialog(
        show = show,
        title = "${slice.emoji} ${slice.name} 明细",
        summary = "%.2f%% · ¥ %,.2f · %d笔".format(slice.percent, slice.amount, slice.count),
        maxWidth = 640.dp,
        outsideMargin = DpSize(8.dp, 12.dp),
        insideMargin = DpSize(16.dp, 16.dp),
        onDismissRequest = onDismiss,
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
            if (entries.isEmpty()) {
                Text(
                    text = "该分类在此期间没有账目",
                    style = MiuixTheme.textStyles.body2,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                ) {
                    // key 必须用账单主键：用下标做 key 时，列表复用会把旧行的
                    // remember 状态（滚动位置、动画进度）套到另一条账目上，
                    // 表现为"滚一下就串数据"。entries 里同一 id 唯一（Room 主键）。
                    items(entries.size, key = { entries[it].id }) { i ->
                        DetailEntryRow(entries[i])
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(
            text = "关闭",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DetailEntryRow(entry: LedgerEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LedgerTheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.note.ifBlank { entry.category },
                style = MiuixTheme.textStyles.body1,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = detailTimeLabel(entry),
                style = MiuixTheme.textStyles.footnote2,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        val sign = when (entry.type) {
            "income" -> "+"
            "other" -> ""
            else -> "-"
        }
        Text(
            text = sign + "¥" + "%,.2f".format(entry.amount),
            style = MiuixTheme.textStyles.title4,
            color = if (entry.type == "income") LedgerTheme.income else TextPrimary,
        )
    }
}

private fun detailTimeLabel(entry: LedgerEntry): String =
    if (entry.timeMillis > 0) AppDateFormats.format(entry.timeMillis, AppDateFormats.dashMonthDayTime)
    else entry.time
