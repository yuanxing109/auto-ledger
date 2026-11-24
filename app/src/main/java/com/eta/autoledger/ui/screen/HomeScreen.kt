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
import com.eta.autoledger.ui.components.drawRowSurface
import com.eta.autoledger.ui.components.EntryEditDialog
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
import androidx.compose.ui.draw.drawBehind
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
    val todayExpense = summary.todayExpense
    val monthExpense = summary.monthExpense
    val monthIncome = summary.monthIncome

    // 预算按「起始日」定义的周期算，与自然月分开：发薪日 25 号时，
    // 预算周期 = 25 号 ~ 次月 24 号，而首页月度收支仍按自然月展示。
    val budgetStartDay = prefs.budgetStartDay
    var budgetSpent by remember { mutableStateOf<Double?>(null) }
    // 依赖「内容版本号」而不是 entries.size：翻页只让 entries 变长，账目内容没变，
    // 原先每翻一页都白跑一次预算汇总 SQL。现在只有新增/删除（count 变）
    // 或编辑（editTick 变）才重算。
    LaunchedEffect(budgetStartDay, dataVersion) {
        budgetSpent = runCatching {
            LedgerRepository.budgetCycleExpense(context, budgetStartDay)
        }.getOrNull()
    }
    // 分组已迁到 ViewModel 增量维护：loadMore 追加时只合并新页，不再全量重算。
    // 原实现 remember(entries) 的 key 是整个 List，每次翻页都触发 O(n) 重分组 → 累计 O(n²)。
    val groups = billsState.groups

    // 行容器样式：提到 items{} 之外只算一次。
    // 原来写在 items 的 lambda 里，每个 item 每次重组都要 new 2 个 RoundedCornerShape
    // + 2 个 Color 副本；一屏 10 行、每秒多次重组就是几十次无谓分配。
    // LocalDimens.current 是 CompositionLocal，必须在组合体内取值后传给 remember，
    // 不能写进 remember 的 lambda（那个 lambda 不是 @Composable）。
    val cardRadius = LocalDimens.current.radiusCard
    val rowShape = remember(cardRadius) { RoundedCornerShape(cardRadius) }
    val rowBg = LedgerTheme.surface.copy(alpha = 0.34f)
    val rowBorder = LedgerTheme.border.copy(alpha = 0.55f)

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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 82.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "overview") {
            OverviewCard(
                todayExpense = todayExpense,
                monthExpense = monthExpense,
                monthIncome = monthIncome,
                dateLabel = AppDateFormats.format(now.timeInMillis, AppDateFormats.monthDayChinese),
                visible = amountVisible,
                onToggle = { amountVisible = !amountVisible },
            )
        }
        if (prefs.homeBudgetCard) {
            item(key = "budget") {
                BudgetCard(
                    budget = prefs.budgetAmount.toDoubleOrNull() ?: 0.0,
                    spent = budgetSpent ?: monthExpense,
                    startDay = budgetStartDay,
                )
            }
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(bottom = 8.dp)
                            .clip(rowShape)
                            // 一次绘制同时铺背景和描边：原先是 background + border 两条
                            // modifier，每行每帧要走两遍绘制节点，且 border 还要再建一个
                            // 内缩的 outline。drawBehind 里 outline 只算一次。
                            .drawBehind { drawRowSurface(rowShape, rowBg, rowBorder) },
                    ) {
                        EntryRow(entry, onClick = { editingEntry = entry.ledger })
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
private fun BudgetCard(budget: Double, spent: Double, startDay: Int = 1) {
    val remaining = (budget - spent).coerceAtLeast(0.0)
    val progress = if (budget > 0) (spent / budget).toFloat().coerceIn(0f, 1f) else 0f
    // 起始日非 1 时标题点明周期口径，避免用户误以为和上方「本月支出」是同一个数
    val title = if (startDay == 1) "本月预算" else "本期预算（$startDay 日起）"
    val cardShape = RoundedCornerShape(LocalDimens.current.radiusCard)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, cardShape)
            .padding(16.dp),
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MiuixTheme.textStyles.title4)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (budget > 0) "剩余 ¥ %,.2f".format(remaining) else "未设置预算",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
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

@Composable
private fun EmptyCard() {
    val cardShape = RoundedCornerShape(LocalDimens.current.radiusCard)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, cardShape)
            .padding(16.dp),
    ) {
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
private fun OverviewCard(
    todayExpense: Double,
    monthExpense: Double,
    monthIncome: Double,
    dateLabel: String,
    visible: Boolean,
    onToggle: () -> Unit,
) {
    val cardShape = RoundedCornerShape(LocalDimens.current.radiusCard)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, cardShape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日支出",
                    style = MiuixTheme.textStyles.body2,
                    color = LedgerTheme.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(LedgerTheme.inset)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = dateLabel, style = MiuixTheme.textStyles.footnote2, color = LedgerTheme.textSecondary)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (visible) "¥ %,.2f".format(todayExpense) else "¥ ****",
                    style = MiuixTheme.textStyles.headline2,
                    color = LedgerTheme.text,
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (visible) MiuixIcons.Show else MiuixIcons.Hide,
                        contentDescription = if (visible) "隐藏金额" else "显示金额",
                        tint = LedgerTheme.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(LedgerTheme.divider))
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本月支出", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (visible) "¥ %,.2f".format(monthExpense) else "¥ ****",
                        style = MiuixTheme.textStyles.title2,
                        color = LedgerTheme.text,
                    )
                }
                Box(modifier = Modifier.width(0.5.dp).height(30.dp).background(LedgerTheme.divider))
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("本月收入", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (visible) "¥ %,.2f".format(monthIncome) else "¥ ****",
                        style = MiuixTheme.textStyles.title2,
                        color = LedgerTheme.text,
                    )
                }
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
