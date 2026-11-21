package com.eta.autoledger.ui.screen

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.glass.GlassDialog
import com.eta.autoledger.ui.glass.GlassAmbience
import com.eta.autoledger.ui.glass.GlassButton
import com.eta.autoledger.ui.glass.GlassCard
import com.eta.autoledger.ui.glass.GlassSurface
import com.eta.autoledger.ui.glass.glassHueDrag
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.widget.Toast
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.viewmodel.HomeViewModel
import com.eta.autoledger.data.LedgerRepository

private val HeroStart: Color get() = LedgerTheme.heroStart
private val HeroEnd: Color get() = LedgerTheme.heroEnd
private val AccentBlue: Color get() = LedgerTheme.accent
private val SourceSms: Color get() = LedgerTheme.chart5
private val SourceWechat: Color get() = LedgerTheme.chart2
private val SourceMeituan: Color get() = LedgerTheme.chart3
private val SourceBank: Color get() = LedgerTheme.chart4
private val White70: Color get() = LedgerTheme.onHero.copy(alpha = 0.72f)
private val White20: Color get() = LedgerTheme.onHero.copy(alpha = 0.2f)
private val White10: Color get() = LedgerTheme.onHero.copy(alpha = 0.1f)
private val White05: Color get() = LedgerTheme.onHero.copy(alpha = 0.06f)

private data class EntryUi(
    val ledger: LedgerEntry,
    val category: String,
    val emoji: String,
    val categoryColor: Color,
    val source: String,
    val sourceColor: Color,
    val time: String,
    val note: String,
    val signedAmount: Double,
    val timeText: String,
    val amountText: String,
    val noteLine: String,
)

private data class DayGroupUi(
    val dateLabel: String,
    val entries: List<EntryUi>,
    val expense: Double,
    val income: Double,
)

@Composable
fun HomeScreen(modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    var amountVisible by rememberSaveable { mutableStateOf(true) }
    var editingEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    val vm: HomeViewModel = viewModel()
    val billsState by vm.billsState.collectAsStateWithLifecycle()
    val summary by vm.summaryState.collectAsStateWithLifecycle()
    val entries = billsState.bills
    val listState = rememberLazyListState()

    val now = remember { Calendar.getInstance() }
    val todayExpense = summary.todayExpense
    val monthExpense = summary.monthExpense
    val monthIncome = summary.monthIncome
    val groups = remember(entries) { groupEntries(entries) }

    androidx.compose.runtime.LaunchedEffect(listState, billsState.bills.size, billsState.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (billsState.hasMore && last >= billsState.bills.size - 5) vm.loadMore()
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "overview") {
            OverviewCard(
                todayExpense = todayExpense,
                monthExpense = monthExpense,
                monthIncome = monthIncome,
                dateLabel = SimpleDateFormat("M月d日", Locale.CHINA).format(now.time),
                visible = amountVisible,
                onToggle = { amountVisible = !amountVisible },
            )
        }
        if (prefs.homeBudgetCard) {
            item(key = "budget") {
                BudgetCard(
                    budget = prefs.budgetAmount.toDoubleOrNull() ?: 0.0,
                    spent = monthExpense,
                )
            }
        }
        item(key = "recent_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "最近账目", style = MiuixTheme.textStyles.title4)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "共 ${billsState.totalCount} 笔",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = "搜索",
                    onClick = onSearch,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        if (groups.isEmpty()) {
            item(key = "empty") { EmptyCard() }
        } else {
            groups.forEach { group ->
                item(key = "h_${group.dateLabel}", contentType = "header") { DayHeader(group) }
                items(
                    items = group.entries,
                    key = { it.ledger.id },
                    contentType = { "entry" },
                ) { entry ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(LedgerTheme.surface.copy(alpha = 0.34f))
                            .border(0.5.dp, LedgerTheme.border.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
                    ) {
                        EntryRow(entry, onClick = { editingEntry = entry.ledger })
                    }
                }
            }
        }
    }

    editingEntry?.let { entry ->
        EntryEditDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { amount, category, note ->
                vm.updateEntry(entry.copy(amount = amount, category = category, note = note))
                editingEntry = null
                Toast.makeText(context, "已更新", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                vm.deleteEntry(entry.id)
                editingEntry = null
                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryEditDialog(
    entry: LedgerEntry,
    onDismiss: () -> Unit,
    onSave: (Double, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var amount by remember { mutableStateOf("%.2f".format(entry.amount)) }
    var category by remember { mutableStateOf(entry.category) }
    var note by remember { mutableStateOf(entry.note) }
    val typeIdx = when (entry.type) {
        "income" -> 1
        "other" -> 2
        else -> 0
    }
    // 分类选项来自「设置 → 记账分类管理」，与那里共用同一份 CategoryStore 数据
    val options = remember(entry.type) {
        (CategoryStore.names(context, typeIdx) + entry.category).distinct()
    }

    GlassDialog(
        show = true,
        title = "编辑账目",
        maxWidth = 560.dp,
        onDismissRequest = onDismiss,
    ) {
        Text("金额", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        Spacer(Modifier.height(6.dp))
        TextField(
            value = amount,
            onValueChange = { input -> amount = input.filter { it.isDigit() || it == '.' } },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )

        Spacer(Modifier.height(14.dp))
        Text("分类", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { name ->
                    if (name == category) {
                        Button(
                            onClick = { category = name },
                            cornerRadius = 10.dp,
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(name, style = MiuixTheme.textStyles.footnote1)
                        }
                    } else {
                        TextButton(
                            text = name,
                            onClick = { category = name },
                            cornerRadius = 10.dp,
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            textStyle = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("备注", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        Spacer(Modifier.height(6.dp))
        TextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
        )

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = "删除",
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    color = LedgerTheme.tint(LedgerTheme.danger, 0.14f),
                    textColor = LedgerTheme.danger,
                ),
            )
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                onClick = {
                    val parsed = amount.toDoubleOrNull() ?: entry.amount
                    onSave(parsed, category, note)
                },
                modifier = Modifier.weight(1f).height(48.dp),
                tint = LedgerTheme.accent.copy(alpha = 0.45f),
            ) {
                Text("保存", color = LedgerTheme.onAccent)
            }
        }
    }
}

@Composable
private fun BudgetCard(budget: Double, spent: Double) {
    val remaining = (budget - spent).coerceAtLeast(0.0)
    val progress = if (budget > 0) (spent / budget).toFloat().coerceIn(0f, 1f) else 0f
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        veil = LedgerTheme.surface.copy(alpha = 0.40f),
        contentPadding = PaddingValues(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "本月预算", style = MiuixTheme.textStyles.title4)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (budget > 0) "剩余 ¥ %,.2f".format(remaining) else "未设置预算",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(8.dp)
                        .background(
                            if (progress >= 0.9f) LedgerTheme.danger
                            else MiuixTheme.colorScheme.primary,
                        ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (budget > 0) "已用 ¥ %,.2f / ¥ %,.2f".format(spent, budget)
                else "去设置页配置预算金额与周期",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        veil = LedgerTheme.surface.copy(alpha = 0.40f),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column {
            Text(text = "还没有账目", style = MiuixTheme.textStyles.title3)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "开启通知记账，或用控制中心截屏记第一笔",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

@Composable
private fun OverviewCard(
    todayExpense: Double,
    monthExpense: Double,
    monthIncome: Double,
    dateLabel: String,
    visible: Boolean,
    onToggle: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().glassHueDrag(),
        shape = RoundedCornerShape(26.dp),
        tint = Color.hsv(GlassAmbience.hue, 0.45f, 0.32f).copy(alpha = 0.38f),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日支出",
                    style = MiuixTheme.textStyles.body2,
                    color = White70,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(White20)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(text = dateLabel, style = MiuixTheme.textStyles.footnote2, color = LedgerTheme.onHero)
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (visible) "¥ %,.2f".format(todayExpense) else "¥ ****",
                    style = MiuixTheme.textStyles.headline2,
                    color = LedgerTheme.onHero,
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (visible) MiuixIcons.Show else MiuixIcons.Hide,
                        contentDescription = if (visible) "隐藏金额" else "显示金额",
                        tint = White70,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(White20))
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本月支出", style = MiuixTheme.textStyles.footnote1, color = White70)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (visible) "¥ %,.2f".format(monthExpense) else "¥ ****",
                        style = MiuixTheme.textStyles.title2,
                        color = LedgerTheme.onHero,
                    )
                }
                Box(modifier = Modifier.width(0.5.dp).height(30.dp).background(White20))
                Column(modifier = Modifier.weight(1f).padding(start = 18.dp)) {
                    Text("本月收入", style = MiuixTheme.textStyles.footnote1, color = White70)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (visible) "¥ %,.2f".format(monthIncome) else "¥ ****",
                        style = MiuixTheme.textStyles.title2,
                        color = LedgerTheme.onHero,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "← 横向拖动卡片可切换氛围色 →",
                style = MiuixTheme.textStyles.footnote2,
                color = White70.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun DayHeader(group: DayGroupUi) {
    val summary = buildString {
        if (group.expense > 0) append("支出 %,.2f".format(group.expense))
        if (group.expense > 0 && group.income > 0) append(" · ")
        if (group.income > 0) append("收入 %,.2f".format(group.income))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = group.dateLabel, style = MiuixTheme.textStyles.title4, modifier = Modifier.weight(1f))
        Text(
            text = summary,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}

@Composable
private fun DayCard(group: DayGroupUi, onLongPress: (LedgerEntry) -> Unit) {
    val summary = buildString {
        if (group.expense > 0) append("支出 %,.2f".format(group.expense))
        if (group.expense > 0 && group.income > 0) append(" · ")
        if (group.income > 0) append("收入 %,.2f".format(group.income))
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        veil = LedgerTheme.surface.copy(alpha = 0.40f),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = group.dateLabel, style = MiuixTheme.textStyles.title4, modifier = Modifier.weight(1f))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
            group.entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 66.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.15f)),
                    )
                }
                EntryRow(entry, onClick = { onLongPress(entry.ledger) })
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: EntryUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(entry.categoryColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = entry.emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.category,
                    style = MiuixTheme.textStyles.title3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(entry.sourceColor.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(text = entry.source, style = MiuixTheme.textStyles.footnote2, color = entry.sourceColor)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = entry.noteLine,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.amountText,
            style = MiuixTheme.textStyles.title2,
            color = if (entry.signedAmount > 0) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun entryCalendar(entry: LedgerEntry): Calendar? =
    if (entry.timeMillis > 0) Calendar.getInstance().apply { timeInMillis = entry.timeMillis }
    else LedgerRepository.parseTime(entry.time)

private fun groupEntries(entries: List<LedgerEntry>): List<DayGroupUi> {
    val grouped = LinkedHashMap<String, MutableList<LedgerEntry>>()
    val calMap = HashMap<String, Calendar>()
    entries.forEach { entry ->
        val cal = entryCalendar(entry) ?: Calendar.getInstance()
        val key = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(cal.time)
        calMap[key] = cal
        grouped.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return grouped.map { (key, list) ->
        val cal = calMap[key] ?: Calendar.getInstance()
        val ui = list.map { it.toUi() }
        DayGroupUi(
            dateLabel = "$key ${LedgerRepository.weekday(cal)}",
            entries = ui,
            expense = ui.filter { it.signedAmount < 0 }.sumOf { -it.signedAmount },
            income = ui.filter { it.signedAmount > 0 }.sumOf { it.signedAmount },
        )
    }
}

private fun LedgerEntry.toUi(): EntryUi {
    val signed = if (type == "income") amount else -kotlin.math.abs(amount)
    val cal = entryCalendar(this) ?: Calendar.getInstance()
    val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(cal.time)
    return EntryUi(
        ledger = this,
        category = category.ifBlank { "其他" },
        emoji = categoryEmoji(category),
        categoryColor = categoryColor(category),
        source = source.ifBlank { "自动记账" },
        sourceColor = sourceColor(source),
        time = timeText,
        note = note,
        signedAmount = signed,
        timeText = timeText,
        amountText = if (signed < 0) "-%,.2f".format(-signed) else "+%,.2f".format(signed),
        noteLine = if (note.isBlank()) timeText else "$timeText · $note",
    )
}

private fun isSameDay(entry: LedgerEntry, now: Calendar): Boolean {
    val cal = entryCalendar(entry) ?: return false
    return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
        cal.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)
}

private fun isSameMonth(entry: LedgerEntry, now: Calendar): Boolean {
    val cal = entryCalendar(entry) ?: return false
    return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
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
    else -> LedgerTheme.chart5
}

private fun sourceColor(source: String): Color = when {
    source.contains("短信") -> SourceSms
    source.contains("微信") -> SourceWechat
    source.contains("美团") -> SourceMeituan
    source.contains("银行") -> SourceBank
    source.contains("截屏") -> AccentBlue
    else -> LedgerTheme.chart2
}