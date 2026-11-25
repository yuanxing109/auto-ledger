package com.eta.autoledger.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.util.AppDateFormats
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.ui.components.AppDialog
import com.eta.autoledger.ui.components.CategoryChip
import com.eta.autoledger.ui.components.EntryEditDialog
import com.eta.autoledger.ui.components.drawRowSurface
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.model.formatCategoryLabel
import com.eta.autoledger.ui.window.LocalSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.util.Calendar
import kotlin.math.abs

/**
 * 全屏搜索页。
 *
 * 信息架构（见 `docs/06-UI统一改造方案.md` §4）：
 *   ① 返回独占一行  ② 搜索框全宽（输入 300ms 防抖）
 *   ③ 条件栏只显示「⚙ 筛选」+ 已生效条件（可单删）
 *   ④ 汇总条吸顶    ⑤ 结果按日分组 + 日汇总
 *   ⑥ 命中上限时给截断提示    ⑦ 空状态带「清除全部筛选」
 *
 * 筛选器全部收进 [FilterSheet]，主界面不再被两组 chip 占掉 100dp。
 * 所有条件都进 SQL（`BETWEEN` + `LIKE` + `IN`），不在内存里过滤；只有排序在内存做，
 * 因为结果已被 `LIMIT 300` 截断，300 条排序的开销可以忽略，而参数化 ORDER BY
 * 在 Room 里只能靠 CASE WHEN 硬凑，可读性太差。
 */
@Composable
fun SearchScreen(onClose: () -> Unit) {
    val context = LocalContext.current

    var keyword by rememberSaveable { mutableStateOf("") }
    var debouncedKeyword by remember { mutableStateOf("") }
    var range by rememberSaveable { mutableIntStateOf(0) }
    var type by rememberSaveable { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var minAmount by rememberSaveable { mutableStateOf("") }
    var maxAmount by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var showFilter by remember { mutableStateOf(false) }
    // 点开的结果项：与首页共用同一个编辑弹窗（EntryEditDialog）
    var editingEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    // 每键即查会让 Flow 反复重建，300ms 防抖后只查一次
    LaunchedEffect(keyword) {
        delay(300)
        debouncedKeyword = keyword
    }

    BackHandler { onClose() }

    val (start, end) = remember(range) { rangeOf(range) }
    val minValue = minAmount.trim().toDoubleOrNull() ?: 0.0
    val maxValue = maxAmount.trim().toDoubleOrNull() ?: Double.MAX_VALUE

    val resultsFlow = remember(debouncedKeyword, minValue, maxValue, start, end, type, categories, sort) {
        LedgerRepository.search(
            context = context,
            minAmount = minValue,
            maxAmount = maxValue,
            start = start,
            end = end,
            type = type,
            categories = categories,
            keyword = debouncedKeyword.trim(),
        ).map { list ->
            when (sort) {
                1 -> list.sortedByDescending { it.amount }
                2 -> list.sortedBy { it.amount }
                else -> list // SQL 已按 timestamp DESC, id DESC
            }
        }
    }
    val results by resultsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // 一次遍历同时算支出和收入：原来是两次 filter + 两次 sumOf，等于把结果集扫四遍。
    // 搜索结果上限 300 条，虽然不算大，但这行代码在每次筛选变化时都会跑。
    val (expenseSum, incomeSum) = remember(results) {
        var expense = 0.0
        var income = 0.0
        results.forEach { entry ->
            when (entry.type) {
                "expense" -> expense += entry.amount
                "income" -> income += entry.amount
            }
        }
        expense to income
    }
    val truncated = results.size >= SEARCH_LIMIT
    val groups = remember(results) { results.groupBy { dayStartOf(it) } }

    val activeCount = listOf(
        range != 0,
        type.isNotEmpty(),
        categories.isNotEmpty(),
        minAmount.isNotBlank(),
        maxAmount.isNotBlank(),
        sort != 0,
    ).count { it }

    fun resetFilters() {
        range = 0
        type = ""
        categories = emptyList()
        minAmount = ""
        maxAmount = ""
        sort = 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LedgerTheme.page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ① 返回独占一行：搜索框不再被挤成一条缝
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = LedgerTheme.text,
                )
            }
            Spacer(Modifier.width(2.dp))
            Text("搜索账单", style = MiuixTheme.textStyles.title3, color = LedgerTheme.text)
        }

        // ② Miuix 搜索框（全宽，300ms 防抖在 LaunchedEffect 里）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InputField(
                query = keyword,
                onQueryChange = { keyword = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                label = "搜索备注 / 商户 / 分类",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ③ 条件栏：只有已生效的条件才出现，且每个可单删
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(
                label = if (activeCount > 0) "⚙ 筛选 $activeCount" else "⚙ 筛选",
                selected = activeCount > 0,
                onClick = { showFilter = true },
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (range != 0) {
                    item { Chip(label = RANGE_LABELS.first { it.first == range }.second, selected = false, removable = true, onClick = { range = 0 }) }
                }
                if (type.isNotEmpty()) {
                    item { Chip(label = TYPE_LABELS.first { it.first == type }.second, selected = false, removable = true, onClick = { type = ""; categories = emptyList() }) }
                }
                if (categories.isNotEmpty()) {
                    item {
                        Chip(
                            label = if (categories.size == 1) categories.first() else "${categories.size} 个分类",
                            selected = false,
                            removable = true,
                            onClick = { categories = emptyList() },
                        )
                    }
                }
                if (minAmount.isNotBlank()) {
                    item { Chip(label = "≥ ¥$minAmount", selected = false, removable = true, onClick = { minAmount = "" }) }
                }
                if (maxAmount.isNotBlank()) {
                    item { Chip(label = "≤ ¥$maxAmount", selected = false, removable = true, onClick = { maxAmount = "" }) }
                }
                if (sort != 0) {
                    item { Chip(label = SORT_LABELS.first { it.first == sort }.second, selected = false, removable = true, onClick = { sort = 0 }) }
                }
            }
        }

        // ④ 汇总条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("共 ${results.size} 笔", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary))
            Spacer(Modifier.width(8.dp))
            Text("·", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.divider))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "支出 ¥${money(expenseSum)}",
                style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.text),
            )
            if (incomeSum > 0) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.divider))
                Spacer(Modifier.width(8.dp))
                Text("收入 ¥${money(incomeSum)}", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.income))
            }
        }

        // ⑤ 结果：按日分组
        if (results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", fontSize = 40.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("没有匹配的账单", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = LedgerTheme.text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "试试减少筛选条件，或换个关键字",
                        style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary),
                    )
                    if (activeCount > 0 || keyword.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(LedgerTheme.surfaceVariant)
                                .clickable {
                                    keyword = ""
                                    resetFilters()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("清除全部筛选", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.accent))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 82.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (day, list) ->
                    // 当天支出也走单次遍历：原来是 filter 出一份新 list 再 sumOf，
                    // 每个日期分组都要多分配一个 List。
                    val dayExpense = list.sumOf { if (it.type == "expense") it.amount else 0.0 }
                    item(key = "h-$day", contentType = "header") {
                        DayHeader(
                            dayTitle = dayTitle(day),
                            dayExpense = dayExpense,
                        )
                    }
                    items(list, key = { it.id }, contentType = { "entry" }) { entry ->
                        // 与首页一致：结果集变化时行会平滑地淡入/移位，
                        // 而不是「啪」一下整体换掉。修筛选条件时尤其明显。
                        Box(modifier = Modifier.animateItem()) {
                            ResultRow(
                                entry = entry,
                                onClick = { editingEntry = entry },
                                onLongClick = {
                                    // 长按即删，配「撤销」——比先进弹窗再点删除少两步
                                    scope.launch {
                                        LedgerRepository.delete(context, entry.id)
                                        if (snackbar.showUndo("已删除")) {
                                            LedgerRepository.restore(context, entry)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                if (truncated) {
                    item(key = "truncated") {
                        Text(
                            text = "⚠️ 已显示前 $SEARCH_LIMIT 条，收窄条件可看更早的账单",
                            style = MiuixTheme.textStyles.footnote1,
                            color = LedgerTheme.textSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    // 点开一条结果 → 与首页一模一样的编辑弹窗；保存/删除都直接落库，
    // 结果集是 Room Flow，改完自己就刷新（不需要手动重建 resultsFlow）。
    // 常驻组合、只切 show：编辑弹窗的退场动画才能播完（外面用 editingEntry?.let 包起来会瞬消）。
    var lastEditing by remember { mutableStateOf<LedgerEntry?>(null) }
    LaunchedEffect(editingEntry) { if (editingEntry != null) lastEditing = editingEntry }
    lastEditing?.let { entry ->
        EntryEditDialog(
            show = editingEntry != null,
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { amount, category, note ->
                val updated = entry.copy(amount = amount, category = category, note = note)
                editingEntry = null
                scope.launch {
                    LedgerRepository.update(context, updated)
                    snackbar.show("已更新")
                }
            },
            onDelete = {
                val removed = entry
                editingEntry = null
                scope.launch {
                    // 先等删除落库再弹撤销，否则撤销的 insert 会被 id 冲突 IGNORE 掉
                    LedgerRepository.delete(context, removed.id)
                    if (snackbar.showUndo("已删除")) {
                        LedgerRepository.restore(context, removed)
                    }
                }
            },
        )
    }

    // 常驻组合、只切 show：外面用 if (showFilter) 包起来的话，一点「应用」组件就被移出组合，
    // AppDialog 接管的退场动画没机会播（底部面板会直接消失）。
    FilterSheet(
        show = showFilter,
        range = range,
        type = type,
        categories = categories,
        minAmount = minAmount,
        maxAmount = maxAmount,
        sort = sort,
        keyword = debouncedKeyword.trim(),
        onRange = { range = it },
        onType = { picked ->
            type = picked
            // 换类型时把不属于新类型的分类剔掉，否则会查出 0 条还找不到原因
            if (picked.isEmpty()) categories = emptyList()
            else {
                val allowed = catsOf(context, picked)
                categories = categories.filter { it in allowed }
            }
        },
        onCategories = { categories = it },
        onMin = { minAmount = it },
        onMax = { maxAmount = it },
        onSort = { sort = it },
        onReset = { resetFilters() },
        onDismiss = { showFilter = false },
        )
}

private const val SEARCH_LIMIT = 300

private val RANGE_LABELS = listOf(
    0 to "全部",
    1 to "本月",
    2 to "上月",
    3 to "近三月",
    4 to "今年",
)

private val TYPE_LABELS = listOf(
    "" to "全部",
    "expense" to "支出",
    "income" to "收入",
    "other" to "其他",
)

private val SORT_LABELS = listOf(
    0 to "时间 ↓",
    1 to "金额 ↓",
    2 to "金额 ↑",
)

/** 「全部」类型下列出三类分类的并集。 */
private fun catsOf(context: Context, type: String): List<String> =
    if (type.isEmpty()) {
        (CategoryStore.names(context, 0) + CategoryStore.names(context, 1) + CategoryStore.names(context, 2)).distinct()
    } else {
        CategoryStore.names(context, typeIndex(type))
    }

private fun typeIndex(type: String): Int = when (type) {
    "expense" -> 0
    "income" -> 1
    "other" -> 2
    else -> 0
}

/** 返回 [start, end) 毫秒区间；「全部」用 0..Long.MAX_VALUE。 */
private fun rangeOf(range: Int): Pair<Long, Long> {
    val now = Calendar.getInstance()
    val todayStart = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val monthStart = (todayStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
    return when (range) {
        1 -> monthStart.timeInMillis to (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        2 -> (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, -1) }.timeInMillis to monthStart.timeInMillis
        3 -> (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, -2) }.timeInMillis to
            (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        4 -> (monthStart.clone() as Calendar).apply { set(Calendar.MONTH, 0) }.timeInMillis to
            (monthStart.clone() as Calendar).apply { set(Calendar.MONTH, 0); add(Calendar.YEAR, 1) }.timeInMillis
        else -> 0L to Long.MAX_VALUE
    }
}

/** 条目所在自然日的 0 点，用来做分组 key（同时保证同一天只有一组）。 */
private fun dayStartOf(entry: LedgerEntry): Long {
    val millis = if (entry.timeMillis > 0) {
        entry.timeMillis
    } else {
        LedgerRepository.parseTime(entry.time)?.timeInMillis ?: 0L
    }
    if (millis <= 0L) return 0L
    return (Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }).timeInMillis
}

private val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

private fun dayTitle(dayMillis: Long): String {
    if (dayMillis <= 0L) return "未知日期"
    val cal = Calendar.getInstance().apply { timeInMillis = dayMillis }
    val label = AppDateFormats.format(dayMillis, AppDateFormats.monthDayChinese)
    return "$label ${WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
}

private fun money(value: Double): String = "%,.2f".format(abs(value))

@Composable
private fun DayHeader(dayTitle: String, dayExpense: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayTitle, style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        Spacer(Modifier.weight(1f))
        if (dayExpense > 0) {
            Text("支出 ${money(dayExpense)}", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    removable: Boolean = false,
) {
    val text = if (removable) "$label ✕" else label
    val shape = RoundedCornerShape(50)
    // 选中态用实心 accent 底 + onAccent 文字 + accent 边，保证「蓝底/蓝边」清晰可辨
    val bg = if (selected) LedgerTheme.accent else LedgerTheme.surface
    val bd = if (selected) LedgerTheme.accentPressed else LedgerTheme.border
    val fg = if (selected) LedgerTheme.onAccent else LedgerTheme.textSecondary
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(0.5.dp, bd, shape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1, color = fg)
    }
}

@Composable
private fun AmountBox(
    modifier: Modifier = Modifier,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' }) },
            textStyle = MiuixTheme.textStyles.body1.copy(color = LedgerTheme.text, fontWeight = FontWeight.Medium),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(LedgerTheme.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
                    }
                    inner()
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    entry: LedgerEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val income = entry.type == "income"
    val other = entry.type == "other"
    val stamp = remember(entry.timeMillis) {
        val millis = if (entry.timeMillis > 0) entry.timeMillis else 0L
        if (millis > 0) AppDateFormats.format(millis, AppDateFormats.timeMinute) else entry.time
    }
    // 圆角只建一次：原来 clip 和 border 各 new 一个 RoundedCornerShape(16.dp)。
    val rowShape = remember { RoundedCornerShape(16.dp) }
    val rowBg = LedgerTheme.surface
    val rowBorder = LedgerTheme.borderSoft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            // 背景 + 描边合成一次绘制：省掉 border 那条 modifier 的独立绘制节点，
            // 也让 outline 只计算一次（滚动时每行都在走这段）。
            .drawBehind { drawRowSurface(rowShape, rowBg, rowBorder) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.note.ifBlank { entry.category },
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                color = LedgerTheme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${formatCategoryLabel(entry.category, entry.subCategory)} · $stamp · ${entry.source}",
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = (if (income) "+" else if (other) "" else "-") + "¥" + money(entry.amount),
            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                income -> LedgerTheme.income
                other -> LedgerTheme.textSecondary
                else -> LedgerTheme.text
            },
        )
    }
}

/**
 * 筛选面板。
 *
 * 所有控件改的是本地 draft，点「应用」才写回父状态 —— 这样中途关掉不会把界面弄得半新半旧。
 * 底部按钮实时显示命中条数（200ms 防抖跑一次 count 查询），避免"筛出一堆空结果"。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    show: Boolean,
    range: Int,
    type: String,
    categories: List<String>,
    minAmount: String,
    maxAmount: String,
    sort: Int,
    keyword: String,
    onRange: (Int) -> Unit,
    onType: (String) -> Unit,
    onCategories: (List<String>) -> Unit,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
    onSort: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cats = remember(type) { catsOf(context, type) }
    var preview by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(range, type, categories, minAmount, maxAmount, keyword) {
        delay(200)
        val (start, end) = rangeOf(range)
        preview = runCatching {
            LedgerRepository.countSearch(
                context = context,
                minAmount = minAmount.trim().toDoubleOrNull() ?: 0.0,
                maxAmount = maxAmount.trim().toDoubleOrNull() ?: Double.MAX_VALUE,
                start = start,
                end = end,
                type = type,
                categories = categories,
                keyword = keyword,
            )
        }.getOrNull()
    }

    // 收编到统一 AppDialog：底部半屏面板用 align=BottomCenter + heightFraction，
    // 只圆上面两角；面板自身铺满宽度、内容区自己撑高，所以 contentPadding 传 0。
    AppDialog(
        show = show,
        align = Alignment.BottomCenter,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        widthFraction = 1f,
        heightFraction = 0.9f,
        outsideMargin = DpSize(0.dp, 0.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.navigationBarsPadding(),
        onDismissRequest = onDismiss,
    ) {
        // ---- 固定头部：拖拽条 + 标题 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LedgerTheme.divider),
            )
            Spacer(Modifier.height(14.dp))
            Text("筛选", style = MiuixTheme.textStyles.main.copy(fontWeight = FontWeight.SemiBold, color = LedgerTheme.text))
        }

        // ---- 可滚动内容区（weight 占满剩余高度，内容超长上下滑动）----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp),
        ) {
            SectionLabel("时间")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RANGE_LABELS.forEach { (v, label) ->
                    Chip(label = label, selected = range == v, onClick = { onRange(v) })
                }
            }
            Spacer(Modifier.height(14.dp))

            SectionLabel("类型")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TYPE_LABELS.forEach { (v, label) ->
                    Chip(label = label, selected = type == v, onClick = { onType(v) })
                }
            }

            if (cats.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionLabel("分类（可多选）")
                // 分类数量多：单独限高 + 内滚动，避免把整个弹窗撑爆
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        cats.forEach { name ->
                            // selected 用纯分类名比较；图标与文字分开传
                            CategoryChip(
                                text = name,
                                icon = CategoryStore.emoji(name),
                                selected = name in categories,
                                onClick = {
                                    onCategories(
                                        if (name in categories) categories - name else categories + name,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("金额（元）")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AmountBox(modifier = Modifier.weight(1f), value = minAmount, hint = "最低", onChange = onMin)
                Text("—", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textTertiary))
                AmountBox(modifier = Modifier.weight(1f), value = maxAmount, hint = "最高", onChange = onMax)
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("排序")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SORT_LABELS.forEach { (v, label) ->
                    Chip(label = label, selected = sort == v, onClick = { onSort(v) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---- 固定底部操作栏：始终可见、可点击 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LedgerTheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val resetShape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .weight(0.62f)
                    .height(48.dp)
                    .clip(resetShape)
                    .background(LedgerTheme.surfacePressed)
                    .border(1.dp, LedgerTheme.border, resetShape)
                    .clickable {
                        onRange(0)
                        onReset()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("重置", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
            }
            val applyInteraction = remember { MutableInteractionSource() }
            val applyPressed by applyInteraction.collectIsPressedAsState()
            val applyShape = RoundedCornerShape(LocalDimens.current.radiusCapsule)
            Box(
                modifier = Modifier
                    .weight(1.38f)
                    .height(48.dp)
                    .clip(applyShape)
                    .background(if (applyPressed) LedgerTheme.accentPressed else LedgerTheme.accent)
                    .clickable(
                        interactionSource = applyInteraction,
                        indication = LocalIndication.current,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (preview == null) "应用" else "应用（${preview} 条）",
                    style = MiuixTheme.textStyles.button,
                    color = LedgerTheme.onAccent,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    SmallTitle(text = text, modifier = Modifier.padding(bottom = 8.dp))
}
