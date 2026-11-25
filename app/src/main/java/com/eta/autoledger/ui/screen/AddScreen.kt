package com.eta.autoledger.ui.screen

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.MagicWandIcon
import com.eta.autoledger.ui.components.AppDialog
import com.eta.autoledger.ui.components.AiQuickEntryDialog
import com.eta.autoledger.ui.components.ThemeSurfaceCard
import com.eta.autoledger.ui.window.LocalSnackbar
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.ui.theme.amountDisplayStyle
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.RecurringRule
import com.eta.autoledger.data.RecurringExternalIds
import com.eta.autoledger.screenshot.AiQuickEntry
import com.eta.autoledger.service.RecurringWorker
import com.eta.autoledger.util.AppDateFormats
import com.eta.autoledger.ui.theme.LedgerDimens
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.launch

private val AccentBlue: Color get() = LedgerTheme.accent
private val TextPrimary: Color get() = LedgerTheme.text
private val TextSecondary: Color get() = LedgerTheme.textSecondary

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
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var type by rememberSaveable { mutableIntStateOf(0) }
    var amountInput by rememberSaveable { mutableStateOf("") }
    var negative by rememberSaveable { mutableStateOf(false) }
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

    fun displayAmount(): String {
        val value = amountInput.ifEmpty { "0.00" }
        return (if (negative) "-" else "") + value
    }

    fun pressKey(key: String) {
        when (key) {
            "⌫" -> amountInput = amountInput.dropLast(1)
            "." -> if (!amountInput.contains(".")) {
                amountInput = if (amountInput.isEmpty()) "0." else "$amountInput."
            }
            "+" -> negative = false
            "−" -> negative = true
            else -> {
                if (amountInput.length >= 9) return
                val dot = amountInput.indexOf('.')
                if (dot >= 0 && amountInput.length - dot > 2) return
                amountInput = when {
                    amountInput == "0" -> key
                    amountInput.isEmpty() && key == "0" -> "0"
                    else -> amountInput + key
                }
            }
        }
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
        negative = false
        selectedCategory = entry.category
        note = entry.note
        subCategory = ""
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
        val normalizedSubCategory = subCategory.trim().ifBlank { null }
        scope.launch {
            runCatching {
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
                    LedgerRepository.add(
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
                } else {
                    LedgerRepository.add(
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
                }
            }.onFailure {
                scope.launch { snackbar.showLong("保存失败：${it.message}") }
            }
        }
        scope.launch { snackbar.show("已记录 ${selectedCategory} ¥ %.2f".format(raw)) }
        amountInput = ""
        negative = false
        note = ""
        subCategory = ""
        if (!reset) onDone()
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Transparent)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Spacer(Modifier.height(12.dp))
            AmountCard(
                amount = displayAmount(),
                note = note,
                onNoteChange = { note = it },
                onAiQuickEntry = { showAiQuickEntry = true },
            )
            Spacer(Modifier.height(12.dp))
            CategoryCard(
                categories = currentCategories,
                selected = selectedCategory,
                onSelect = {
                    if (it != selectedCategory) subCategory = ""
                    selectedCategory = it
                },
            )
            Spacer(Modifier.height(dimens.gap))
            SubCategoryField(
                value = subCategory,
                onValueChange = { subCategory = it },
            )
            Spacer(Modifier.height(dimens.gap))
            RecurringCard(
                enabled = recurring,
                onEnabled = { recurring = it },
                periodIndex = recurringPeriod,
                onPeriodClick = { showPeriodPicker = true },
                dateHint = timeText,
            )
            Spacer(Modifier.height(16.dp))
        }

        Keyboard(
            onKey = ::pressKey,
            onSaveAgain = { save(reset = true) },
            onDone = { save(reset = false) },
        )
    }

    PeriodPickerDialog(
        show = showPeriodPicker,
        selected = recurringPeriod,
        onDismiss = { showPeriodPicker = false },
        onPick = { recurringPeriod = it; showPeriodPicker = false },
    )

    // 常驻组合、只切 show：父级若用 if (showPicker) 包起来，一点取消组件就被移出组合，
    // AppDialog 内部接管的那段退场动画永远播不到。
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
) {
    val dimens = LocalDimens.current
    ThemeSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "子分类（可选）",
            style = MiuixTheme.textStyles.footnote1,
            color = TextSecondary,
        )
        Spacer(Modifier.height(dimens.gapSmall))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
            cursorBrush = SolidColor(AccentBlue),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = "如：外卖 / 堂食",
                            style = MiuixTheme.textStyles.body2.copy(color = TextSecondary),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 周期账单开关 + 周期选择。
 *
 * 只做「每周 / 每月 / 每年」三种基础周期，具体日期取自当前选的记账时间，
 * 不再单独让用户选日期——同一笔账单的日期与周期日应当一致，
 * 分成两个选择器只会让用户对不上。
 */
@Composable
private fun RecurringCard(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    periodIndex: Int,
    onPeriodClick: () -> Unit,
    dateHint: String,
) {
    val periodLabel = listOf("每周", "每月", "每年")[periodIndex.coerceIn(0, 2)]
    ThemeSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "设为周期账单",
                    style = MiuixTheme.textStyles.title4,
                    color = LedgerTheme.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (enabled) "到期自动入账并通知" else "房租、订阅、工资等固定支出",
                    style = MiuixTheme.textStyles.footnote1,
                    color = LedgerTheme.textSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.switchColors(
                    checkedThumbColor = LedgerTheme.surface,
                    uncheckedThumbColor = LedgerTheme.textSecondary,
                    checkedTrackColor = LedgerTheme.accent,
                    uncheckedTrackColor = LedgerTheme.divider,
                ),
            )
        }
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onPeriodClick),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$periodLabel（按 ${dateHint.take(10)} 的日期）",
                        style = MiuixTheme.textStyles.body2,
                        color = LedgerTheme.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "更改",
                        style = MiuixTheme.textStyles.footnote1,
                        color = LedgerTheme.accent,
                    )
                }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f)) {
            listOf("支出", "收入", "其他").forEachIndexed { index, label ->
                val selected = type == index
                Column(
                    modifier = Modifier
                        .clickable { onType(index) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) TextPrimary else TextSecondary,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) AccentBlue else Color.Transparent),
                    )
                }
            }
        }
        DatePill(timeText = timeText, onClick = onTime)
    }
}

@Composable
private fun DatePill(timeText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, AccentBlue, RoundedCornerShape(50))
            .background(LedgerTheme.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Months,
            contentDescription = "时间",
            tint = AccentBlue,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text = timeText, style = MiuixTheme.textStyles.footnote2.copy(color = AccentBlue))
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
    // 以 show 为 key：每次重新打开都从调用方传进来的时间重建，不会残留上次改到一半的年月日。
    // 退场动画期间 show 已为 false，但 initial* 仍是调用方最后一次的值，重建结果与原值一致，不会跳。
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
        val c = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        year = c.get(Calendar.YEAR)
        month = c.get(Calendar.MONTH)
        day = day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH))
    }

    // 收编到统一 AppDialog：自带进出场动画、遮罩渐隐、外部点击与返回键关闭。
    // 内容区保持原布局（月份翻页 + 日历格 + 时分步进 + 取消/确认）。
    AppDialog(
        show = show,
        maxWidth = 400.dp,
        outsideMargin = DpSize(24.dp, 32.dp),
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = "编辑日期",
            style = MiuixTheme.textStyles.main.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { shiftMonth(-1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = "上个月",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = "${year}年${month + 1}月",
                style = TextStyle(fontSize = 15.sp, color = TextPrimary),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { shiftMonth(1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = "下个月",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    style =MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        cells.chunked(7).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { value ->
                    Box(
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (value != null) {
                            val selected = value == day
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { day = value },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) AccentBlue else Color.Transparent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = value.toString(),
                                        style = MiuixTheme.textStyles.footnote1.copy(
                                            color = if (selected) LedgerTheme.onAccent else TextPrimary,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                repeat(7 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(LedgerTheme.divider),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "时刻", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
            Spacer(Modifier.weight(1f))
            TimeStepper(
                value = hour,
                unit = "时",
                onUp = { hour = (hour + 1) % 24 },
                onDown = { hour = (hour + 23) % 24 },
            )
            Text(text = ":", style = TextStyle(fontSize = 18.sp, color = TextSecondary))
            TimeStepper(
                value = minute,
                unit = "分",
                onUp = { minute = (minute + 1) % 60 },
                onDown = { minute = (minute + 59) % 60 },
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "取消", style = MiuixTheme.textStyles.body2.copy(color = TextSecondary))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentBlue)
                    .clickable { onConfirm(year, month, day, hour, minute) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "确认", style = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.onAccent))
            }
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
            modifier = Modifier.size(48.dp).clickable(onClick = onUp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "▲", style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "%02d".format(value), style = TextStyle(fontSize = 24.sp, color = AccentBlue))
            Spacer(Modifier.width(4.dp))
            Text(text = unit, style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary))
        }
        Box(
            modifier = Modifier.size(48.dp).clickable(onClick = onDown),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "▼", style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary))
            }
        }
    }
}

@Composable
private fun AmountCard(
    amount: String,
    note: String,
    onNoteChange: (String) -> Unit,
    onAiQuickEntry: () -> Unit,
) {
    ThemeSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "¥", style = TextStyle(fontSize = 20.sp, color = TextPrimary))
            Spacer(Modifier.width(8.dp))
            Text(
                text = amount,
                style = amountDisplayStyle().copy(
                    color = if (amount == "0.00") LedgerTheme.textTertiary else LedgerTheme.text,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = LedgerDimens.minTouch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = note,
                onValueChange = onNoteChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
                cursorBrush = SolidColor(AccentBlue),
                decorationBox = { inner ->
                    Box {
                        if (note.isEmpty()) {
                            Text(
                                text = "点击添加备注",
                                style = MiuixTheme.textStyles.body2.copy(color = TextSecondary),
                            )
                        }
                        inner()
                    }
                },
            )
            AiQuickEntryButton(onClick = onAiQuickEntry)
        }
    }
}

@Composable
private fun AiQuickEntryButton(onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Row(
        modifier = Modifier
            .heightIn(min = LedgerDimens.minTouch)
            .clip(RoundedCornerShape(dimens.radiusCapsule))
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = LedgerDimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MagicWandIcon,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(LedgerDimens.xs))
        Text(
            text = "AI 帮我记",
            style = MiuixTheme.textStyles.button,
            color = AccentBlue,
        )
    }
}

@Composable
private fun CategoryCard(
    categories: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    ThemeSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        categories.chunked(5).forEachIndexed { index, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                repeat(5 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (index != categories.chunked(5).lastIndex) {
                Spacer(Modifier.height(12.dp))
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
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.border(2.dp, AccentBlue, CircleShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(category.color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (category.name == "添加") {
                    Icon(
                        imageVector = MiuixIcons.Add,
                        contentDescription = "添加分类",
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(text = category.emoji, style = MiuixTheme.textStyles.title3)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = category.name,
            style = MiuixTheme.textStyles.footnote2.copy(
                color = if (selected) TextPrimary else TextSecondary,
            ),
        )
    }
}

@Composable
private fun Keyboard(
    onKey: (String) -> Unit,
    onSaveAgain: () -> Unit,
    onDone: () -> Unit,
) {
    val dimens = LocalDimens.current
    val keyboardShape = RoundedCornerShape(topStart = dimens.radiusCard, topEnd = dimens.radiusCard)
    ThemeSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        shape = keyboardShape,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(dimens.cardPadding),
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(LedgerTheme.divider),
        )
        Spacer(Modifier.height(12.dp))

        val rows = listOf(
            listOf("1", "2", "3", "⌫"),
            listOf("4", "5", "6", "+"),
            listOf("7", "8", "9", "−"),
            listOf(".", "0", "再记一笔", "完成"),
        )
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val isDone = key == "完成"
                    val isAgain = key == "再记一笔"
                    val isAux = key == "⌫" || key == "+" || key == "−"
                    KeyboardKey(
                        label = key,
                        modifier = Modifier.weight(1f),
                        background = when {
                            isDone -> LedgerTheme.accent
                            isAgain -> Color.Transparent
                            isAux -> LedgerTheme.divider
                            else -> LedgerTheme.surfaceVariant
                        },
                        contentColor = when {
                            isDone -> LedgerTheme.onAccent
                            isAgain -> LedgerTheme.accent
                            else -> TextPrimary
                        },
                        style = TextStyle(
                            fontSize = if (isDone || isAgain) 12.sp else 18.sp,
                            fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        onClick = when (key) {
                            "再记一笔" -> onSaveAgain
                            "完成" -> onDone
                            else -> { { onKey(key) } }
                        },
                    )
                }
            }
            if (rowIndex != rows.lastIndex) Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    background: Color,
    contentColor: Color,
    style: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = style, color = contentColor)
    }
}

/** 周期选择：三项单选，选中态实心 accent。 */
@Composable
private fun PeriodPickerDialog(
    show: Boolean,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AppDialog(show = show, title = "重复周期", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            listOf(
                "每周" to "按当前记账日期的星期几，每周重复",
                "每月" to "按当前记账日期的号数，每月重复（上限 28 日）",
                "每年" to "按当前记账日期的月日，每年重复",
            ).forEachIndexed { index, (label, hint) ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) LedgerTheme.accent else LedgerTheme.inset)
                        .clickable { onPick(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body1.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) LedgerTheme.onAccent else LedgerTheme.text,
                        ),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = hint,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isSelected) LedgerTheme.onAccent else LedgerTheme.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
            }
        }
    }
}
