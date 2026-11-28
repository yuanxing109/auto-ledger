package com.eta.autoledger.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.ui.OilIcons
import com.eta.autoledger.ui.components.EntryEditDialog
import com.eta.autoledger.ui.components.OilCard
import com.eta.autoledger.ui.components.OilCardTone
import com.eta.autoledger.ui.components.OilIconBadge
import com.eta.autoledger.ui.components.OilIconButton
import com.eta.autoledger.ui.components.OilText
import com.eta.autoledger.ui.model.DayGroupUi
import com.eta.autoledger.ui.model.EntryUi
import com.eta.autoledger.ui.model.formatCategoryLabel
import com.eta.autoledger.ui.theme.OilTheme
import com.eta.autoledger.ui.theme.oilPageSurface
import com.eta.autoledger.ui.window.LocalSnackbar
import com.eta.autoledger.util.AppDateFormats
import com.eta.autoledger.viewmodel.HomeViewModel
import java.util.Calendar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    val colors = OilTheme.colors

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
        modifier = modifier.fillMaxSize().oilPageSurface(),
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
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OilText(
                    text = "最近账目",
                    style = OilTheme.typography.sectionTitle,
                    color = colors.ink,
                )
                Spacer(Modifier.width(8.dp))
                OilText(
                    text = "共 ${billsState.totalCount} 笔",
                    style = OilTheme.typography.caption,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.weight(1f))
                OilIconButton(
                    icon = OilIcons.Search,
                    contentDescription = "搜索",
                    onClick = onSearch,
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
                    OilCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
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
                vm.updateEntry(
                    entry.copy(
                        amount = amount,
                        category = category,
                        note = note,
                        isTransfer = false,
                        transferPairId = null,
                    ),
                )
                editingEntry = null
                scope.launch { snackbar.show("已更新") }
            },
            onReleaseTransfer = entry.transferPairId?.let { pairId ->
                {
                    editingEntry = null
                    scope.launch {
                        val released = runCatching { vm.releaseTransferPair(pairId).await() }.getOrNull()
                        if (released != null && released > 0) {
                            snackbar.show("已解除内部转账合并")
                        }
                    }
                }
            },
            onDelete = {
                val removed = entry
                editingEntry = null
                scope.launch {
                    // 先等删除真正落库，再弹撤销——否则用户可能在 delete 未完成时点撤销，
                    // restoreEntry 的 insert 会因 id 冲突被 IGNORE 丢弃，随后 delete 又把记录删掉。
                    val snapshot = vm.deleteEntry(removed.id).await()
                    val undone = snackbar.showUndo("已删除")
                    if (undone) vm.restoreEntry(snapshot ?: removed)
                }
            },
        )
    }
}

@Composable
private fun EmptyCard() {
    val colors = OilTheme.colors
    OilCard(modifier = Modifier.fillMaxWidth()) {
        OilText(
            text = "还没有账目",
            style = OilTheme.typography.sectionTitle,
            color = colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        OilText(
            text = "开启通知记账，或用控制中心截屏记第一笔",
            style = OilTheme.typography.body,
            color = colors.inkMuted,
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
    val colors = OilTheme.colors
    val typography = OilTheme.typography
    val dimen = OilTheme.dimens
    val remaining = (budget - budgetSpent).coerceAtLeast(0.0)
    val progress = if (visible && budget > 0.0) {
        (budgetSpent / budget).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val budgetLabel = if (budgetStartDay == 1) "月预算" else "本期预算"
    OilCard(
        modifier = Modifier.fillMaxWidth(),
        tone = OilCardTone.Hero,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = dimen.cardPadding,
                vertical = dimen.gapLarge,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    OilText(
                        text = dateLabel,
                        style = typography.caption,
                        color = colors.onHero,
                    )
                    Spacer(Modifier.height(4.dp))
                    OilText(
                        text = "本月支出",
                        style = typography.body,
                        color = colors.onHero,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(OilTheme.shapes.capsule)
                        .background(colors.heroMuted)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (autoRecordEnabled) colors.income else colors.warning),
                    )
                    Spacer(Modifier.width(7.dp))
                    OilText(
                        text = if (autoRecordEnabled) "自动记账开启" else "自动记账关闭",
                        style = typography.label,
                        color = colors.onHero,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OilText(
                    text = if (visible) "¥ %,.2f".format(monthExpense) else "¥ ****",
                    style = typography.heroAmount,
                    color = colors.onHero,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                OilIconButton(
                    icon = if (visible) OilIcons.Hide else OilIcons.Show,
                    contentDescription = if (visible) "隐藏金额" else "显示金额",
                    onClick = onToggle,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(dimen.gapLarge)) {
                HeroMetric(
                    label = "本月收入",
                    value = if (visible) "¥ %,.2f".format(monthIncome) else "¥ ****",
                    markerColor = colors.income,
                    modifier = Modifier.weight(1f),
                )
                HeroMetric(
                    label = "全部笔数",
                    value = "$totalCount 笔",
                    markerColor = colors.onHero,
                    modifier = Modifier.weight(1f),
                )
                HeroMetric(
                    label = "日均支出",
                    value = if (visible) "¥ %,.2f".format(dailyAverageExpense) else "¥ ****",
                    markerColor = colors.expense,
                    modifier = Modifier.weight(1f),
                )
            }

            if (showBudget) {
                Spacer(Modifier.height(dimen.gapLarge))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.heroMuted),
                )
                Spacer(Modifier.height(dimen.gap))
                OilText(
                    text = when {
                        budget <= 0.0 -> "$budgetLabel 未设置 · 可前往预算管理配置"
                        visible -> "$budgetLabel ¥ %,.2f · 剩余 ¥ %,.2f".format(budget, remaining)
                        else -> "$budgetLabel · 金额已隐藏"
                    },
                    style = typography.caption,
                    color = colors.onHero,
                )
                Spacer(Modifier.height(9.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.heroMuted),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(8.dp)
                            .background(colors.onHero),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    markerColor: Color,
) {
    val colors = OilTheme.colors
    val typography = OilTheme.typography
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 3.dp)
                .background(markerColor),
        )
        Spacer(Modifier.height(7.dp))
        OilText(
            text = label,
            style = typography.label,
            color = colors.onHero,
        )
        Spacer(Modifier.height(4.dp))
        OilText(
            text = value,
            style = typography.metric,
            color = colors.onHero,
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
        in 5..11 -> "早上好\n今天也把每一笔记清楚"
        in 12..17 -> "下午好\n每一笔消费都值得留痕"
        else -> "晚上好\n来复盘今天的收支"
    }
    val tip = rememberSaveable { HOME_TIPS.random() }
    val colors = OilTheme.colors
    val typography = OilTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
    ) {
        OilText(
            text = greeting,
            style = typography.pageTitle,
            color = colors.ink,
        )
        Spacer(Modifier.height(7.dp))
        OilText(
            text = tip,
            style = typography.body,
            color = colors.inkMuted,
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
        if (group.entries.any { it.ledger.isTransfer }) {
            if (isNotEmpty()) append(" · ")
            append("含内部转账")
        }
    }
    val colors = OilTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OilText(
            text = group.dateLabel,
            style = OilTheme.typography.bodyStrong,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(8.dp))
        OilText(
            text = summary,
            style = OilTheme.typography.caption.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum",
            ),
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
            textAlign = TextAlign.End,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: EntryUi) {
    val colors = OilTheme.colors
    val typography = OilTheme.typography
    val amountColor = when {
        entry.ledger.isTransfer -> colors.inkMuted
        entry.signedAmount > 0 -> colors.income
        else -> colors.expense
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            OilIconBadge(
                size = 44.dp,
                borderColor = colors.line,
                backgroundColor = colors.surfaceMuted,
            ) {
                OilText(
                    text = entry.emoji,
                    style = typography.sectionTitle,
                    color = colors.ink,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(entry.categoryColor),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OilText(
                    text = if (entry.ledger.isTransfer) {
                        "内部转账"
                    } else {
                        formatCategoryLabel(entry.category, entry.ledger.subCategory)
                    },
                    style = typography.bodyStrong,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .widthIn(max = 120.dp)
                        .clip(OilTheme.shapes.capsule)
                        .background(colors.surfaceMuted)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(entry.sourceColor),
                        )
                        Spacer(Modifier.width(5.dp))
                        OilText(
                            text = entry.source,
                            style = typography.label,
                            color = colors.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OilText(
                text = entry.noteLine,
                style = typography.caption,
                color = colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        OilText(
            text = entry.amountText,
            style = typography.listAmount.copy(textAlign = TextAlign.End),
            color = amountColor,
            modifier = Modifier.widthIn(min = 92.dp, max = 144.dp),
            maxLines = 1,
        )
    }
}
