package com.eta.autoledger.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.RecurringExternalIds
import com.eta.autoledger.data.RecurringRule
import com.eta.autoledger.screenshot.AiQuickEntry
import com.eta.autoledger.service.RecurringWorker
import com.eta.autoledger.ui.MagicWandIcon
import com.eta.autoledger.ui.OilIcons
import com.eta.autoledger.ui.components.AiQuickEntryDialog
import com.eta.autoledger.ui.components.OilButton
import com.eta.autoledger.ui.components.OilButtonVariant
import com.eta.autoledger.ui.components.OilCard
import com.eta.autoledger.ui.components.OilCardTone
import com.eta.autoledger.ui.components.OilChoiceChip
import com.eta.autoledger.ui.components.OilDialog
import com.eta.autoledger.ui.components.OilIconButton
import com.eta.autoledger.ui.components.OilKeyboardInsetContainer
import com.eta.autoledger.ui.components.OilSwitch
import com.eta.autoledger.ui.components.OilText
import com.eta.autoledger.ui.components.OilTextField
import com.eta.autoledger.ui.theme.OilTheme
import com.eta.autoledger.ui.theme.oilPageSurface
import com.eta.autoledger.ui.window.LocalSnackbar
import com.eta.autoledger.util.AppDateFormats
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Category(
    val name: String,
    val emoji: String,
    val color: Color,
)

private fun categoriesFor(context: android.content.Context, type: Int): List<Category> =
    CategoryStore.names(context, type).map {
        Category(it, CategoryStore.emoji(it), CategoryStore.color(it))
    }

@Composable
fun AddScreen(modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var type by rememberSaveable { mutableIntStateOf(0) }
    var amountInput by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(CategoryStore.expense.first()) }
    var subCategory by rememberSaveable { mutableStateOf("") }
    // 记账时间用 saveable 的 epoch 毫秒保存：旋转/进程重建后不会悄悄退回「现在」，
    // 否则显示时间与保存时间会不一致（原来 calendar 用 remember，重建即丢失）。
    var timeMillis by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    val calendar = remember { Calendar.getInstance().apply { timeInMillis = timeMillis } }
    var timeText by rememberSaveable {
        mutableStateOf(AppDateFormats.format(calendar, AppDateFormats.todayTime))
    }
    var showPicker by remember { mutableStateOf(false) }
    // 周期账单：开启后这条记录变成模板（同时也会作为本期实账入库）
    var recurring by rememberSaveable { mutableStateOf(false) }
    var recurringPeriod by rememberSaveable { mutableIntStateOf(1) } // 0=每周 1=每月 2=每年
    var showPeriodPicker by remember { mutableStateOf(false) }
    var showAiQuickEntry by rememberSaveable { mutableStateOf(false) }
    var pickYear by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var pickMonth by remember { mutableIntStateOf(calendar.get(Calendar.MONTH)) }
    var pickDay by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }
    var pickHour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var pickMinute by remember { mutableIntStateOf(calendar.get(Calendar.MINUTE)) }
    // 分类列表只在「切类型」时才需要重读：CategoryStore.names 每次都要
    // getSharedPreferences + getString + JSONArray 全量解析 + 逐项查 emoji/配色，
    // 原来写在组合体里，按一次数字键（amountInput 变化 → 整页重组）就重跑一遍。
    // 键用 type 而不是空：切到「收入/其他」时分类表本来就不同，必须重新取。
    val currentCategories = remember(type) { categoriesFor(context, type) }
    val amountFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var categoryRevision by remember { mutableIntStateOf(0) }
    val subCategorySuggestions = remember(type, selectedCategory, categoryRevision) {
        CategoryStore.subCategories(context, type, selectedCategory)
    }

    fun updateAmountInput(raw: String) {
        val filtered = raw.filter { it in '0'..'9' || it == '.' }
        if (filtered.length > 9 || filtered.count { it == '.' } > 1) return
        if (filtered.substringAfter('.', "").length > 2) return
        val normalized = when {
            filtered == "." -> "0."
            filtered.length > 1 && filtered.startsWith('0') && filtered[1] != '.' -> {
                val withoutLeadingZeros = filtered.dropWhile { it == '0' }
                when {
                    withoutLeadingZeros.isEmpty() -> "0"
                    withoutLeadingZeros.startsWith('.') -> "0$withoutLeadingZeros"
                    else -> withoutLeadingZeros
                }
            }
            else -> filtered
        }
        amountInput = normalized
    }

    LaunchedEffect(Unit) {
        delay(180)
        amountFocusRequester.requestFocus()
        keyboardController?.show()
    }

    fun openPicker() {
        pickYear = calendar.get(Calendar.YEAR)
        pickMonth = calendar.get(Calendar.MONTH)
        pickDay = calendar.get(Calendar.DAY_OF_MONTH)
        pickHour = calendar.get(Calendar.HOUR_OF_DAY)
        pickMinute = calendar.get(Calendar.MINUTE)
        showPicker = true
    }

    fun refreshTimeText() {
        val now = Calendar.getInstance()
        val sameDay = now.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
            now.get(Calendar.MONTH) == calendar.get(Calendar.MONTH) &&
            now.get(Calendar.DAY_OF_MONTH) == calendar.get(Calendar.DAY_OF_MONTH)
        timeText = AppDateFormats.format(
            calendar,
            if (sameDay) AppDateFormats.todayTime else AppDateFormats.monthDayTime,
        )
    }

    fun applyDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        calendar.set(year, month, day, hour, minute)
        timeMillis = calendar.timeInMillis
        refreshTimeText()
        showPicker = false
    }

    fun applyAiQuickEntry(entry: AiQuickEntry) {
        type = if (entry.type == "income") 1 else 0
        amountInput = BigDecimal.valueOf(entry.amount)
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        selectedCategory = entry.category
        note = entry.note
        subCategory = entry.subCategory.orEmpty()
        calendar.timeInMillis = entry.timeMillis
        timeMillis = entry.timeMillis
        refreshTimeText()
        scope.launch { snackbar.show("AI 已填写，请确认后保存") }
    }

    fun save(reset: Boolean) {
        val raw = amountInput.toDoubleOrNull() ?: 0.0
        if (raw <= 0.0) {
            scope.launch { snackbar.show("请输入金额") }
            return
        }
        val typeValue = when (type) {
            1 -> "income"
            2 -> "other"
            else -> "expense"
        }
        val timeTextNow = AppDateFormats.format(calendar, AppDateFormats.dateTime)
        val normalizedSubCategory = CategoryStore.normalizeSubCategoryName(subCategory)
        scope.launch {
            runCatching {
                if (normalizedSubCategory != null) {
                    val added = CategoryStore.addSubCategory(
                        context,
                        type,
                        selectedCategory,
                        normalizedSubCategory,
                    )
                    if (!added && CategoryStore.subCategories(context, type, selectedCategory)
                            .none { it == normalizedSubCategory }
                    ) {
                        error("子分类未能保存，请检查名称")
                    }
                }
                if (recurring) {
                    // 周期账单 = 一条模板 + 当期一笔实账。
                    //
                    // 只存模板的话，用户今天记的这笔不会出现在账目里（模板被统计排除），
                    // 要等下一个周期日才「凭空」出现一笔，体验很怪。所以两行一起写：
                    //   1. 模板（is_recurring=1）：不参与任何统计，只供 Worker 匹配；
                    //   2. 当期实账（is_recurring=0）：用户今天就能看到。
                    // 幂等靠 external_id 唯一索引，不靠备注（备注可能为空或重名）。
                    val rule = RecurringRule.fromDate(
                        when (recurringPeriod) {
                            0 -> RecurringRule.Period.WEEKLY
                            2 -> RecurringRule.Period.YEARLY
                            else -> RecurringRule.Period.MONTHLY
                        },
                        calendar,
                    )
                    val templateId = LedgerRepository.add(
                        context,
                        LedgerEntry(
                            id = 0L,
                            amount = raw,
                            type = typeValue,
                            category = selectedCategory,
                            subCategory = normalizedSubCategory,
                            time = timeTextNow,
                            note = note,
                            source = RecurringWorker.SOURCE_TEMPLATE,
                            externalId = "tmpl_" + java.util.UUID.randomUUID().toString().take(12),
                            isRecurring = true,
                            recurringRule = rule.raw,
                        ),
                    )
                    val newId = LedgerRepository.add(
                        context,
                        LedgerEntry(
                            id = 0L,
                            amount = raw,
                            type = typeValue,
                            category = selectedCategory,
                            subCategory = normalizedSubCategory,
                            time = timeTextNow,
                            note = note,
                            source = "手动记账",
                            externalId = RecurringExternalIds.generate(templateId, calendar),
                            isRecurring = false,
                        ),
                    )
                    if (newId > 0L) {
                        snackbar.show("已记录 ${selectedCategory} ¥ %.2f".format(raw))
                    }
                } else {
                    val newId = LedgerRepository.add(
                        context,
                        LedgerEntry(
                            id = 0L,
                            amount = raw,
                            type = typeValue,
                            category = selectedCategory,
                            subCategory = normalizedSubCategory,
                            time = timeTextNow,
                            note = note,
                            source = "手动记账",
                        ),
                    )
                    if (newId > 0L) {
                        snackbar.show(
                            "已记录 ${selectedCategory} ¥ %.2f".format(raw),
                        )
                    }
                }
                categoryRevision++
            }.onFailure {
                scope.launch { snackbar.showLong("保存失败：${it.message}") }
            }
        }
        amountInput = ""
        note = ""
        subCategory = ""
        if (!reset) onDone()
    }

    OilKeyboardInsetContainer(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .oilPageSurface(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OilTheme.dimens.pagePadding, vertical = OilTheme.dimens.gap),
                verticalArrangement = Arrangement.spacedBy(OilTheme.dimens.gap),
            ) {
                TypeDateRow(
                    type = type,
                    onType = {
                        type = it
                        selectedCategory = categoriesFor(context, it).first().name
                        subCategory = ""
                    },
                    timeText = timeText,
                    onTime = { openPicker() },
                )
                AmountCard(
                    type = type,
                    amount = amountInput,
                    onAmountChange = ::updateAmountInput,
                    note = note,
                    onNoteChange = { note = it },
                    onAiQuickEntry = { showAiQuickEntry = true },
                    focusRequester = amountFocusRequester,
                    onImeDone = { keyboardController?.hide() },
                )
                CategoryCard(
                    categories = currentCategories,
                    selected = selectedCategory,
                    onSelect = {
                        if (it != selectedCategory) subCategory = ""
                        selectedCategory = it
                    },
                )
                SubCategoryField(
                    value = subCategory,
                    onValueChange = { subCategory = it },
                    suggestions = subCategorySuggestions,
                )
                RecurringCard(
                    enabled = recurring,
                    onEnabled = { recurring = it },
                    periodIndex = recurringPeriod,
                    onPeriodClick = { showPeriodPicker = true },
                    dateHint = timeText,
                )
            }

            SaveBar(onSave = { save(reset = false) })
        }
    }

    PeriodPickerDialog(
        show = showPeriodPicker,
        selected = recurringPeriod,
        onDismiss = { showPeriodPicker = false },
        onPick = { recurringPeriod = it; showPeriodPicker = false },
    )

    // 常驻组合、只切 show：父级若用 if (showPicker) 包起来，一点取消组件就被移出组合，
    // OilDialog 内部接管的那段退场动画永远播不到。
    DateTimeDialog(
        show = showPicker,
        initialYear = pickYear,
        initialMonth = pickMonth,
        initialDay = pickDay,
        initialHour = pickHour,
        initialMinute = pickMinute,
        onDismiss = { showPicker = false },
        onConfirm = { y, m, d, h, mi -> applyDateTime(y, m, d, h, mi) },
    )

    AiQuickEntryDialog(
        show = showAiQuickEntry,
        onDismiss = { showAiQuickEntry = false },
        onParsed = ::applyAiQuickEntry,
    )
}

@Composable
private fun SubCategoryField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
) {
    val colors = OilTheme.colors
    OilCard(modifier = Modifier.fillMaxWidth()) {
        OilText(
            text = "子分类（可选）",
            style = OilTheme.typography.label,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(OilTheme.dimens.gapSmall))
        OilTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "如：外卖 / 堂食",
        )
        if (value.isNotBlank() && suggestions.isNotEmpty()) {
            Spacer(Modifier.height(OilTheme.dimens.gap))
            Column(verticalArrangement = Arrangement.spacedBy(OilTheme.dimens.gapSmall)) {
                suggestions.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(OilTheme.dimens.gapSmall)) {
                        rowItems.forEach { suggestion ->
                            OilChoiceChip(
                                text = suggestion,
                                selected = suggestion == value,
                                onClick = { onValueChange(suggestion) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 周期账单开关与周期入口。日期来自当前记账时间，避免两个日期源互相冲突。
 */
@Composable
private fun RecurringCard(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    periodIndex: Int,
    onPeriodClick: () -> Unit,
    dateHint: String,
) {
    val colors = OilTheme.colors
    val periodLabel = listOf("每周", "每月", "每年")[periodIndex.coerceIn(0, 2)]
    OilCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                OilText(
                    text = "周期账单",
                    style = OilTheme.typography.bodyStrong,
                    color = colors.ink,
                )
                Spacer(Modifier.height(3.dp))
                OilText(
                    text = if (enabled) "到期自动入账并通知" else "房租、订阅、工资等固定项目",
                    style = OilTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
            OilSwitch(
                checked = enabled,
                onCheckedChange = onEnabled,
            )
        }
        if (enabled) {
            Spacer(Modifier.height(OilTheme.dimens.gap))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(OilTheme.shapes.control)
                    .background(colors.surfaceMuted)
                    .clickable(onClick = onPeriodClick)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OilText(
                        text = periodLabel,
                        style = OilTheme.typography.bodyStrong,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    OilText(
                        text = "按 ${dateHint.take(10)} 的日期重复",
                        style = OilTheme.typography.caption,
                        color = colors.inkMuted,
                    )
                }
                Image(
                    painter = rememberVectorPainter(OilIcons.ChevronForward),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    colorFilter = ColorFilter.tint(colors.inkMuted),
                )
            }
        }
    }
}

@Composable
private fun TypeDateRow(
    type: Int,
    onType: (Int) -> Unit,
    timeText: String,
    onTime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OilTheme.dimens.gapSmall)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("支出", "收入", "其他").forEachIndexed { index, label ->
                OilTypeTab(
                    text = label,
                    selected = type == index,
                    onClick = { onType(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            DatePill(timeText = timeText, onClick = onTime)
        }
    }
}

@Composable
private fun OilTypeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OilTheme.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceMuted,
        label = "add-type-background",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.onAccent else colors.inkMuted,
        label = "add-type-content",
    )
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(OilTheme.shapes.capsule)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        OilText(
            text = text,
            style = OilTheme.typography.bodyStrong,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun DatePill(timeText: String, onClick: () -> Unit) {
    val colors = OilTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(OilTheme.shapes.capsule)
            .background(colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberVectorPainter(OilIcons.Months),
            contentDescription = "选择时间",
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(colors.accent),
        )
        Spacer(Modifier.width(6.dp))
        OilText(
            text = timeText,
            style = OilTheme.typography.caption,
            color = colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun DateTimeDialog(
    show: Boolean,
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int, Int) -> Unit,
) {
    var year by remember(show) { mutableIntStateOf(initialYear) }
    var month by remember(show) { mutableIntStateOf(initialMonth) }
    var day by remember(show) { mutableIntStateOf(initialDay) }
    var hour by remember(show) { mutableIntStateOf(initialHour) }
    var minute by remember(show) { mutableIntStateOf(initialMinute) }

    val monthCal = remember(year, month) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val firstOffset = (monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = List<Int?>(firstOffset) { null } + (1..daysInMonth).toList()

    fun shiftMonth(delta: Int) {
        val next = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        year = next.get(Calendar.YEAR)
        month = next.get(Calendar.MONTH)
        day = day.coerceAtMost(next.getActualMaximum(Calendar.DAY_OF_MONTH))
    }

    OilDialog(
        show = show,
        title = "选择记账时间",
        maxWidth = 400.dp,
        outsideMargin = DpSize(20.dp, 28.dp),
        onDismissRequest = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OilIconButton(
                icon = OilIcons.ChevronBackward,
                contentDescription = "上个月",
                onClick = { shiftMonth(-1) },
                size = 40.dp,
            )
            OilText(
                text = "${year}年${month + 1}月",
                style = OilTheme.typography.sectionTitle.copy(textAlign = TextAlign.Center),
                color = OilTheme.colors.ink,
                modifier = Modifier.weight(1f),
            )
            OilIconButton(
                icon = OilIcons.ChevronForward,
                contentDescription = "下个月",
                onClick = { shiftMonth(1) },
                size = 40.dp,
            )
        }

        Spacer(Modifier.height(OilTheme.dimens.gap))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                OilText(
                    text = label,
                    style = OilTheme.typography.label.copy(textAlign = TextAlign.Center),
                    color = OilTheme.colors.inkMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(OilTheme.dimens.gapSmall))

        cells.chunked(7).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { value ->
                    Box(
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (value != null) {
                            val selected = value == day
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(OilTheme.shapes.control)
                                    .background(
                                        if (selected) OilTheme.colors.accent else OilTheme.colors.surfaceMuted,
                                    )
                                    .clickable { day = value },
                                contentAlignment = Alignment.Center,
                            ) {
                                OilText(
                                    text = value.toString(),
                                    style = OilTheme.typography.caption,
                                    color = if (selected) OilTheme.colors.onAccent else OilTheme.colors.ink,
                                )
                            }
                        }
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(OilTheme.dimens.gap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OilTheme.colors.divider),
        )
        Spacer(Modifier.height(OilTheme.dimens.gap))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OilText(
                text = "时刻",
                style = OilTheme.typography.label,
                color = OilTheme.colors.inkMuted,
            )
            Spacer(Modifier.weight(1f))
            TimeStepper(
                value = hour,
                unit = "时",
                onUp = { hour = (hour + 1) % 24 },
                onDown = { hour = (hour + 23) % 24 },
            )
            OilText(
                text = ":",
                style = OilTheme.typography.amount,
                color = OilTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TimeStepper(
                value = minute,
                unit = "分",
                onUp = { minute = (minute + 1) % 60 },
                onDown = { minute = (minute + 59) % 60 },
            )
        }

        Spacer(Modifier.height(OilTheme.dimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(OilTheme.dimens.gapSmall)) {
            OilButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                variant = OilButtonVariant.Secondary,
            )
            OilButton(
                text = "确认时间",
                onClick = { onConfirm(year, month, day, hour, minute) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimeStepper(
    value: Int,
    unit: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(OilTheme.colors.surfaceMuted)
                .clickable(onClick = onUp),
            contentAlignment = Alignment.Center,
        ) {
            OilText("+", style = OilTheme.typography.bodyStrong, color = OilTheme.colors.ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OilText(
                text = "%02d".format(value),
                style = OilTheme.typography.amount,
                color = OilTheme.colors.accent,
            )
            Spacer(Modifier.width(3.dp))
            OilText(
                text = unit,
                style = OilTheme.typography.label,
                color = OilTheme.colors.inkMuted,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(OilTheme.colors.surfaceMuted)
                .clickable(onClick = onDown),
            contentAlignment = Alignment.Center,
        ) {
            OilText("−", style = OilTheme.typography.bodyStrong, color = OilTheme.colors.ink)
        }
    }
}

@Composable
private fun AmountCard(
    type: Int,
    amount: String,
    onAmountChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onAiQuickEntry: () -> Unit,
    focusRequester: FocusRequester,
    onImeDone: () -> Unit,
) {
    val colors = OilTheme.colors
    val notePlaceholder = when (type) {
        1 -> "这笔收入来自哪里"
        2 -> "补充这笔收支的说明"
        else -> "这笔钱花在哪里"
    }
    OilCard(
        modifier = Modifier.fillMaxWidth(),
        tone = OilCardTone.Hero,
        contentPadding = PaddingValues(22.dp),
    ) {
        OilText(
            text = "本次金额",
            style = OilTheme.typography.label,
            color = colors.onHero,
        )
        Spacer(Modifier.height(OilTheme.dimens.gapSmall))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OilText(
                text = "¥",
                style = OilTheme.typography.amount,
                color = colors.onHero,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = amount,
                onValueChange = onAmountChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .focusRequester(focusRequester),
                textStyle = OilTheme.typography.heroAmount.copy(color = colors.onHero),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onImeDone() }),
                cursorBrush = SolidColor(colors.onHero),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (amount.isEmpty()) {
                            OilText(
                                text = "0.00",
                                style = OilTheme.typography.heroAmount,
                                color = colors.onHeroMuted,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Spacer(Modifier.height(OilTheme.dimens.gap))
        OilTextField(
            value = note,
            onValueChange = onNoteChange,
            label = "备注",
            placeholder = notePlaceholder,
            labelColor = colors.onHero,
            singleLine = true,
        )
        Spacer(Modifier.height(OilTheme.dimens.gap))
        OilButton(
            text = "AI 帮我记",
            onClick = onAiQuickEntry,
            modifier = Modifier.fillMaxWidth(),
            variant = OilButtonVariant.Secondary,
            leadingIcon = MagicWandIcon,
        )
    }
}

@Composable
private fun CategoryCard(
    categories: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = OilTheme.colors
    OilCard(modifier = Modifier.fillMaxWidth()) {
        OilText(
            text = "选择分类",
            style = OilTheme.typography.sectionTitle,
            color = colors.ink,
        )
        Spacer(Modifier.height(OilTheme.dimens.gap))
        categories.chunked(4).forEachIndexed { index, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { category ->
                    CategoryCell(
                        category = category,
                        selected = selected == category.name,
                        onClick = {
                            if (category.name != "添加") onSelect(category.name)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            if (index != categories.chunked(4).lastIndex) {
                Spacer(Modifier.height(OilTheme.dimens.gap))
            }
        }
    }
}

@Composable
private fun CategoryCell(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OilTheme.colors
    val circleColor = when {
        selected -> colors.accent
        category.name == "添加" -> colors.surfaceMuted
        else -> lerp(category.color, colors.surface, 0.80f)
    }
    Column(
        modifier = modifier
            .clip(OilTheme.shapes.control)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center,
        ) {
            if (category.name == "添加") {
                Image(
                    painter = rememberVectorPainter(OilIcons.Add),
                    contentDescription = "添加分类",
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(colors.ink),
                )
            } else {
                OilText(
                    text = category.emoji,
                    style = OilTheme.typography.sectionTitle,
                    color = if (selected) colors.onAccent else colors.ink,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OilText(
            text = category.name,
            style = OilTheme.typography.label,
            color = if (selected) colors.ink else colors.inkMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun SaveBar(onSave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OilTheme.colors.canvas)
            .navigationBarsPadding()
            .padding(horizontal = OilTheme.dimens.pagePadding, vertical = OilTheme.dimens.gap),
    ) {
        OilButton(
            text = "保存这笔",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
@Composable
private fun PeriodPickerDialog(
    show: Boolean,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    OilDialog(show = show, title = "重复周期", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            listOf(
                "每周" to "按当前记账日期的星期几，每周重复",
                "每月" to "按当前记账日期的号数，每月重复（上限 28 日）",
                "每年" to "按当前记账日期的月日，每年重复",
            ).forEachIndexed { index, (label, hint) ->
                if (index > 0) Spacer(Modifier.height(OilTheme.dimens.gapSmall))
                val selectedItem = index == selected
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OilTheme.shapes.control)
                        .background(
                            if (selectedItem) OilTheme.colors.accent else OilTheme.colors.surfaceMuted,
                        )
                        .clickable { onPick(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    OilText(
                        text = label,
                        style = OilTheme.typography.bodyStrong,
                        color = if (selectedItem) OilTheme.colors.onAccent else OilTheme.colors.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    OilText(
                        text = hint,
                        style = OilTheme.typography.caption,
                        color = if (selectedItem) OilTheme.colors.onAccent else OilTheme.colors.inkMuted,
                    )
                }
            }
            Spacer(Modifier.height(OilTheme.dimens.gap))
            OilButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = OilButtonVariant.Secondary,
            )
        }
    }
}
