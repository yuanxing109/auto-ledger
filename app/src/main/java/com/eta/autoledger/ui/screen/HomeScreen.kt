package com.eta.autoledger.ui.screen

import com.eta.autoledger.ui.model.DayGroupUi
import com.eta.autoledger.ui.model.EntryUi
import com.eta.autoledger.ui.model.formatCategoryLabel
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.window.LocalSnackbar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.util.AppDateFormats
import top.yukonga.miuix.kmp.icon.extended.Search
import com.eta.autoledger.ui.components.EntryEditDialog
import com.eta.autoledger.ui.components.ThemeSurfaceCard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.viewmodel.HomeViewModel
import com.eta.autoledger.data.LedgerRepository

private val AccentBlue: Color get() = LedgerTheme.accent

/**
 * 概览卡右上角的日期。提到文件级：原先写在 item 的 lambda 里，
 * 日期格式统一来自 AppDateFormats，避免每次概览卡重组都构造重量级 formatter。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val prefs = remember { AppPrefs.get(context) }
    var amountVisible by rememberSaveable { mutableStateOf(true) }
    var editingEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    // 弹窗需要「常驻组合、只切 show」才能播完退场动画，所以关掉后还要留一份数据给退场帧渲染。
    // 直接读 editingEntry 会在关闭瞬间变成 null，弹窗内容当场清空。
    var lastEditingEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    LaunchedEffect(editingEntry) { if (editingEntry != null) lastEditingEntry = editingEntry }
    val vm: HomeViewModel = viewModel()
    val billsState by vm.billsState.collectAsStateWithLifecycle()
    val summary by vm.summaryState.collectAsStateWithLifecycle()
    val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val now = remember { Calendar.getInstance() }
    val monthExpense = summary.monthExpense
    val monthIncome = summary.monthIncome
    val dayOfMonth = now.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
    val dailyAverageExpense = monthExpense / dayOfMonth
    val autoRecordEnabled = prefs.smsEnabled || prefs.alipayEnabled || prefs.wechatEnabled || prefs.otherEnabled
    val budgetAmount = prefs.budgetAmount.toDoubleOrNull() ?: 0.0
    val showBudget = prefs.homeBudgetCard

    // 预算按「起始日」定义的周期算，与自然月分开：发薪日 25 号时，
    // 预算周期 = 25 号 ~ 次月 24 号，而首页月度收支仍按自然月展示。
    val budgetStartDay = prefs.budgetStartDay
    var budgetSpent by remember { mutableStateOf<Double?>(null) }
    // 依赖「内容版本号」而不是 entries.size：翻页只让 entries 变长，账目内容没变，
    // 原先每翻一页都白跑一次预算汇总 SQL。现在只有新增/删除（count 变）
    // 或编辑（editTick 变）才重算。
    LaunchedEffect(budgetStartDay, dataVersion, showBudget, budgetAmount) {
        budgetSpent = if (showBudget && budgetAmount > 0.0) {
            runCatching {
                LedgerRepository.budgetCycleExpense(context, budgetStartDay)
            }.getOrNull()
        } else {
            monthExpense
        }
    }
    // 分组已迁到 ViewModel 增量维护：loadMore 追加时只合并新页，不再全量重算。
    // 原实现 remember(entries) 的 key 是整个 List，每次翻页都触发 O(n) 重分组 → 累计 O(n²)。
    val groups = billsState.groups

    // 触底判断：拿 LazyColumn 的 item 下标与 item 总数比，两者同量纲。
    //
    // 原实现是 `last >= billsState.bills.size - 5`，但 layoutInfo 的 index 是
    // 「LazyColumn 里的第几个 item」，而 bills.size 只是账目条数 —— 中间还夹着
    // overview / budget / recent_header / 每个 DayHeader。列表一长，item 下标就比
    // 账目条数大得多，于是条件几乎永远成立：每帧都 loadMore，被 debounce(300) 拦成
    // 每 300ms 一次翻页，滚动时一路把后面的页全拉下来。
    // 改成与 totalItemsCount 比（同一个下标空间），并要求最后一个可见项确实是账目行，
    // 避免停在分组标题上时误触发。
    LaunchedEffect(listState, billsState.hasMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // 只上报「最后一个可见项的下标 + 总数 + 它是不是账目行」，三者一起做去重，
            // 相同快照不会重复触发。
            Triple(last?.index ?: -1, info.totalItemsCount, last?.contentType == "entry")
        }
            .distinctUntilChanged()
            .collect { (lastIndex, total, lastIsEntry) ->
                if (billsState.hasMore && lastIsEntry && lastIndex >= total - 3) vm.loadMore()
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(Color.Transparent),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 82.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "greeting") {
            HomeGreeting(nowMillis = now.timeInMillis)
        }
        item(key = "overview") {
            CoreAssetCard(
                monthExpense = monthExpense,
                monthIncome = monthIncome,
                totalCount = billsState.totalCount,
                dailyAverageExpense = dailyAverageExpense,
                dateLabel = AppDateFormats.format(now.timeInMillis, AppDateFormats.monthDayChinese),
                budget = budgetAmount,
                budgetSpent = budgetSpent ?: monthExpense,
                budgetStartDay = budgetStartDay,
                showBudget = showBudget,
                autoRecordEnabled = autoRecordEnabled,
                visible = amountVisible,
                onToggle = { amountVisible = !amountVisible },
            )
        }
        item(key = "recent_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp),
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
                // 48dp 透明热区 + 20dp 图标：文字按钮在 48dp 盒内会被挤成竖排，改用图标。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = onSearch,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = "搜索",
                        tint = LedgerTheme.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
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
                    ThemeSurfaceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(bottom = 8.dp),
                        contentPadding = PaddingValues(0.dp),
                        onClick = { editingEntry = entry.ledger },
                    ) {
                        EntryRow(entry)
                    }
                }
            }
        }
    }

    lastEditingEntry?.let { entry ->
        EntryEditDialog(
            show = editingEntry != null,
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { amount, category, note ->
                vm.updateEntry(entry.copy(amount = amount, category = category, note = note))
                editingEntry = null
                scope.launch { snackbar.show("已更新") }
            },
            onDelete = {
                val removed = entry
                editingEntry = null
                scope.launch {
                    // 先等删除真正落库，再弹撤销——否则用户可能在 delete 未完成时点撤销，
                    // restoreEntry 的 insert 会因 id 冲突被 IGNORE 丢弃，随后 delete 又把记录删掉。
                    vm.deleteEntry(removed.id).await()
                    val undone = snackbar.showUndo("已删除")
                    if (undone) vm.restoreEntry(removed)
                }
            },
        )
    }
}

@Composable
private fun EmptyCard() {
    ThemeSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "还没有账目", style = MiuixTheme.textStyles.title3)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "开启通知记账，或用控制中心截屏记第一笔",
            style = MiuixTheme.textStyles.body2,
            color = LedgerTheme.textSecondary,
        )
    }
}

@Composable
private fun CoreAssetCard(
    monthExpense: Double,
    monthIncome: Double,
    totalCount: Int,
    dailyAverageExpense: Double,
    dateLabel: String,
    budget: Double,
    budgetSpent: Double,
    budgetStartDay: Int,
    showBudget: Boolean,
    autoRecordEnabled: Boolean,
    visible: Boolean,
    onToggle: () -> Unit,
) {
    val cardShape = RoundedCornerShape(LocalDimens.current.radiusCard)
    val dimen = LocalDimens.current
    val heroStart = LedgerTheme.heroStart.copy(alpha = 0.94f)
    val heroEnd = LedgerTheme.heroEnd.copy(alpha = 0.86f)
    val heroBrush = remember(heroStart, heroEnd) { Brush.linearGradient(listOf(heroStart, heroEnd)) }
    val remaining = (budget - budgetSpent).coerceAtLeast(0.0)
    val progress = if (visible && budget > 0.0) {
        (budgetSpent / budget).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val budgetLabel = if (budgetStartDay == 1) "月预算" else "本期预算"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(heroBrush)
            .border(1.dp, LedgerTheme.onHero.copy(alpha = 0.2f), cardShape)
            .padding(horizontal = dimen.cardPadding, vertical = dimen.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = LedgerTheme.onHero.copy(alpha = 0.78f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "本月支出",
                    style = MiuixTheme.textStyles.body2,
                    color = LedgerTheme.onHero.copy(alpha = 0.86f),
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(LedgerTheme.onHero.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (autoRecordEnabled) LedgerTheme.onHero else LedgerTheme.onHero.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (autoRecordEnabled) "自动记账 ON" else "自动记账 OFF",
                    style = MiuixTheme.textStyles.footnote2,
                    color = LedgerTheme.onHero,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (visible) "¥ %,.2f".format(monthExpense) else "¥ ****",
                style = MiuixTheme.textStyles.headline1,
                color = LedgerTheme.onHero,
            )
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (visible) MiuixIcons.Show else MiuixIcons.Hide,
                    contentDescription = if (visible) "隐藏金额" else "显示金额",
                    tint = LedgerTheme.onHero.copy(alpha = 0.82f),
                )
            }
        }

        Spacer(Modifier.height(dimen.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(dimen.gap)) {
            HeroMetric(
                label = "本月收入",
                value = if (visible) "¥ %,.2f".format(monthIncome) else "¥ ****",
                modifier = Modifier.weight(1f),
            )
            HeroMetric(
                label = "全部笔数",
                value = "$totalCount 笔",
                modifier = Modifier.weight(1f),
            )
            HeroMetric(
                label = "日均支出",
                value = if (visible) "¥ %,.2f".format(dailyAverageExpense) else "¥ ****",
                modifier = Modifier.weight(1f),
            )
        }

        if (showBudget) {
            Spacer(Modifier.height(dimen.gap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(LedgerTheme.onHero.copy(alpha = 0.2f)),
            )
            Spacer(Modifier.height(dimen.gapSmall))
            Text(
                text = when {
                    budget <= 0.0 -> "$budgetLabel 未设置 · 可前往预算管理配置"
                    visible -> "$budgetLabel ¥ %,.2f · 剩余 ¥ %,.2f".format(budget, remaining)
                    else -> "$budgetLabel · 金额已隐藏"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.onHero.copy(alpha = 0.86f),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LedgerTheme.onHero.copy(alpha = 0.22f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(7.dp)
                        .background(LedgerTheme.onHero),
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = LedgerTheme.onHero.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = LedgerTheme.onHero,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeGreeting(nowMillis: Long) {
    val hour = remember(nowMillis) {
        Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
    }
    val greeting = when (hour) {
        in 5..11 -> "早上好，今天也要好好记账"
        in 12..17 -> "下午好，今天的每一笔都值得记下"
        else -> "晚上好，来复盘今天的收支吧"
    }
    val tip = rememberSaveable { HOME_TIPS.random() }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
        Text(
            text = greeting,
            style = MiuixTheme.textStyles.title2,
            color = LedgerTheme.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tip,
            style = MiuixTheme.textStyles.body2,
            color = LedgerTheme.textSecondary,
        )
    }
}

private val HOME_TIPS = listOf(
    "把每一笔记清楚，月底会轻松很多。",
    "预算不是限制，是给真正想做的事留空间。",
    "小账也值得认真记录，长期趋势更有价值。",
    "今天多看一眼，未来的自己少猜一次。",
    "花得明白，才能存得踏实。",
)

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
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: EntryUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(LocalDimens.current.radiusSmall))
                .background(entry.categoryColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = entry.emoji, style = MiuixTheme.textStyles.title3)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatCategoryLabel(entry.category, entry.ledger.subCategory),
                    style = MiuixTheme.textStyles.title3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(entry.sourceColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = entry.source, style = MiuixTheme.textStyles.footnote2, color = entry.sourceColor)
                }
            }
            Spacer(Modifier.height(4.dp))
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
