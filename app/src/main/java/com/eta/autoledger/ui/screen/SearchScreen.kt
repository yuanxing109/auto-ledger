package com.eta.autoledger.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.ui.LedgerTheme
import top.yukonga.miuix.kmp.basic.Text
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 全屏搜索页。
 *
 * 所有条件都直接进 SQL（`BETWEEN` + `LIKE`），不在内存里过滤 —— 库里有 1600+ 条时
 * 内存过滤会随着数据增长逐渐卡顿，而索引扫描是恒定的。
 */
@Composable
fun SearchScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var keyword by rememberSaveable { mutableStateOf("") }
    var minAmount by rememberSaveable { mutableStateOf("") }
    var maxAmount by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf(0) }

    BackHandler { onClose() }

    val (start, end) = remember(range) { rangeOf(range) }
    val minValue = minAmount.trim().toDoubleOrNull() ?: 0.0
    val maxValue = maxAmount.trim().toDoubleOrNull() ?: Double.MAX_VALUE
    val cats = remember(type) { CategoryStore.names(context, typeIndex(type)) }

    val resultsFlow = remember(keyword, minValue, maxValue, start, end, type, category) {
        LedgerRepository.search(
            context = context,
            minAmount = minValue,
            maxAmount = maxValue,
            start = start,
            end = end,
            type = type,
            category = category,
            keyword = keyword.trim(),
        )
    }
    val results by resultsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val total = remember(results) { results.sumOf { if (it.type == "income") it.amount else -it.amount } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LedgerTheme.page)
            .statusBarsPadding(),
    ) {
        // 顶部：返回 + 搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                fontSize = 22.sp,
                color = LedgerTheme.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.card)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (keyword.isEmpty()) {
                    Text("搜索备注 / 分类 / 来源", fontSize = 14.sp, color = LedgerTheme.textSecondary)
                }
                BasicTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = LedgerTheme.text,
                    ),
                    cursorBrush = SolidColor(LedgerTheme.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (keyword.isNotEmpty()) {
                Text(
                    text = "清除",
                    fontSize = 13.sp,
                    color = LedgerTheme.accent,
                    modifier = Modifier
                        .clickable { keyword = "" }
                        .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                )
            }
        }

        // 时间范围
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(RANGE_LABELS) { entry ->
                Chip(
                    label = entry.second,
                    selected = range == entry.first,
                    onClick = { range = entry.first },
                )
            }
        }

        // 类型 + 金额区间
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TYPE_LABELS.forEach { entry ->
                Chip(
                    label = entry.second,
                    selected = type == entry.first,
                    onClick = {
                        type = entry.first
                        category = ""
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            AmountBox(value = minAmount, hint = "最低", onChange = { minAmount = it }, width = 62)
            Text("—", fontSize = 12.sp, color = LedgerTheme.textSecondary)
            AmountBox(value = maxAmount, hint = "最高", onChange = { maxAmount = it }, width = 62)
        }

        // 分类（选了类型才有意义）
        if (type.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip(label = "全部分类", selected = category.isEmpty(), onClick = { category = "" }) }
                items(cats) { name ->
                    Chip(label = name, selected = category == name, onClick = { category = name })
                }
            }
        }

        // 结果统计
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${results.size} 笔",
                fontSize = 13.sp,
                color = LedgerTheme.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = (if (total >= 0) "+" else "-") + "¥ " + "%,.2f".format(kotlin.math.abs(total)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (total >= 0) LedgerTheme.income else LedgerTheme.text,
            )
        }

        if (results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("没有符合条件的账单", fontSize = 14.sp, color = LedgerTheme.textSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id }) { entry ->
                    ResultRow(entry)
                }
            }
        }
    }
}

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
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
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

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) LedgerTheme.accent else LedgerTheme.card)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else LedgerTheme.textSecondary,
        )
    }
}

@Composable
private fun AmountBox(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    width: Int,
) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(LedgerTheme.card)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, fontSize = 12.sp, color = LedgerTheme.textSecondary)
        }
        BasicTextField(
            value = value,
            onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' }) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = LedgerTheme.text,
            ),
            cursorBrush = SolidColor(LedgerTheme.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultRow(entry: LedgerEntry) {
    val income = entry.type == "income"
    val day = remember(entry.timeMillis) {
        val millis = if (entry.timeMillis > 0) entry.timeMillis else LedgerRepository.parseTime(entry.time)?.timeInMillis ?: 0L
        if (millis > 0) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(millis) else entry.time
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LedgerTheme.card)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.note.ifBlank { entry.category },
                fontSize = 14.sp,
                color = LedgerTheme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${entry.category} · $day · ${entry.source}",
                fontSize = 11.sp,
                color = LedgerTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = (if (income) "+" else if (entry.type == "other") "" else "-") + "¥ " + "%,.2f".format(entry.amount),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (income) LedgerTheme.income else LedgerTheme.text,
        )
    }
}