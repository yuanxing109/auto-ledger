package com.eta.autoledger.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.ui.LedgerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
    var range by rememberSaveable { mutableStateOf(0) }
    var type by rememberSaveable { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var minAmount by rememberSaveable { mutableStateOf("") }
    var maxAmount by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(0) }
    var showFilter by remember { mutableStateOf(false) }

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

    val expenseSum = remember(results) { results.filter { it.type != "income" }.sumOf { it.amount } }
    val incomeSum = remember(results) { results.filter { it.type == "income" }.sumOf { it.amount } }
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
            .statusBarsPadding(),
    ) {
        // ① 返回独占一行：搜索框不再被挤成一条缝
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 6.dp, end = 16.dp),
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

        // ② 全宽搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.surfaceVariant)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔍", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (keyword.isEmpty()) {
                        Text(
                            "搜索备注 / 商户 / 分类",
                            fontSize = 14.sp,
                            color = LedgerTheme.textTertiary,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = LedgerTheme.text),
                        cursorBrush = SolidColor(LedgerTheme.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (keyword.isNotEmpty()) {
                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        color = LedgerTheme.textTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { keyword = "" }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ③ 条件栏：只有已生效的条件才出现，且每个可单删
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
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
            Text("共 ${results.size} 笔", fontSize = 13.sp, color = LedgerTheme.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text("·", fontSize = 13.sp, color = LedgerTheme.divider)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "支出 ¥${money(expenseSum)}",
                fontSize = 13.sp,
                color = LedgerTheme.text,
            )
            if (incomeSum > 0) {
                Spacer(Modifier.width(8.dp))
                Text("·", fontSize = 13.sp, color = LedgerTheme.divider)
                Spacer(Modifier.width(8.dp))
                Text("收入 ¥${money(incomeSum)}", fontSize = 13.sp, color = LedgerTheme.income)
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
                        fontSize = 13.sp,
                        color = LedgerTheme.textSecondary,
                    )
                    if (activeCount > 0 || keyword.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(LedgerTheme.surfaceVariant)
                                .clickable {
                                    keyword = ""
                                    resetFilters()
                                }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text("清除全部筛选", fontSize = 13.sp, color = LedgerTheme.accent)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (day, list) ->
                    item(key = "h-$day") {
                        DayHeader(
                            dayTitle = dayTitle(day),
                            dayExpense = list.filter { it.type != "income" }.sumOf { it.amount },
                        )
                    }
                    items(list, key = { it.id }) { entry -> ResultRow(entry) }
                }
                if (truncated) {
                    item(key = "truncated") {
                        Text(
                            text = "⚠️ 已显示前 $SEARCH_LIMIT 条，收窄条件可看更早的账单",
                            fontSize = 12.sp,
                            color = LedgerTheme.textTertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (showFilter) {
        FilterSheet(
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
    val label = SimpleDateFormat("M月d日", Locale.CHINA).format(cal.time)
    return "$label ${WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
}

private fun money(value: Double): String = "%,.2f".format(abs(value))

@Composable
private fun DayHeader(dayTitle: String, dayExpense: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
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
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) LedgerTheme.tint(LedgerTheme.accent) else LedgerTheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = if (removable) "$label ✕" else label,
            style = MiuixTheme.textStyles.footnote1.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = if (selected) LedgerTheme.accent else LedgerTheme.textSecondary,
        )
    }
}

@Composable
private fun AmountBox(
    modifier: Modifier = Modifier,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LedgerTheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, style = MiuixTheme.textStyles.body2, color = LedgerTheme.textTertiary)
        }
        BasicTextField(
            value = value,
            onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' }) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.text),
            cursorBrush = SolidColor(LedgerTheme.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultRow(entry: LedgerEntry) {
    val income = entry.type == "income"
    val other = entry.type == "other"
    val stamp = remember(entry.timeMillis) {
        val millis = if (entry.timeMillis > 0) entry.timeMillis else 0L
        if (millis > 0) SimpleDateFormat("HH:mm", Locale.CHINA).format(millis) else entry.time
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LedgerTheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
                text = "${entry.category} · $stamp · ${entry.source}",
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
@Composable
private fun FilterSheet(
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(LedgerTheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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
                Text("筛选", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LedgerTheme.text)
                Spacer(Modifier.height(14.dp))

                SectionLabel("时间")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RANGE_LABELS) { entry ->
                        Chip(label = entry.second, selected = range == entry.first, onClick = { onRange(entry.first) })
                    }
                }
                Spacer(Modifier.height(14.dp))

                SectionLabel("类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPE_LABELS.forEach { entry ->
                        Chip(label = entry.second, selected = type == entry.first, onClick = { onType(entry.first) })
                    }
                }

                if (cats.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("分类（可多选）")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cats) { name ->
                            Chip(
                                label = name,
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

                Spacer(Modifier.height(14.dp))
                SectionLabel("金额（元）")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AmountBox(modifier = Modifier.weight(1f), value = minAmount, hint = "最低", onChange = onMin)
                    Text("—", fontSize = 13.sp, color = LedgerTheme.textTertiary)
                    AmountBox(modifier = Modifier.weight(1f), value = maxAmount, hint = "最高", onChange = onMax)
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("排序")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SORT_LABELS.forEach { entry ->
                        Chip(label = entry.second, selected = sort == entry.first, onClick = { onSort(entry.first) })
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onRange(0)
                                onReset()
                            }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("重置", fontSize = 15.sp, color = LedgerTheme.textSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LedgerTheme.accent)
                            .clickable { onDismiss() }
                            .padding(horizontal = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (preview == null) "应用" else "应用（${preview} 条）",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LedgerTheme.onAccent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = LedgerTheme.textTertiary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}