package com.eta.autoledger.ui.screen

import com.eta.autoledger.ui.LedgerTheme
import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import kotlinx.coroutines.launch

private val PageBg: Color get() = LedgerTheme.page
private val AccentBlue: Color get() = LedgerTheme.accent
private val TextPrimary: Color get() = LedgerTheme.text
private val TextSecondary: Color get() = LedgerTheme.textSecondary

private data class Category(
    val name: String,
    val emoji: String,
    val color: Color,
)

private val expenseCategories = listOf(
    Category("其他", "⚪", LedgerTheme.textSecondary),
    Category("餐饮", "🍜", LedgerTheme.chart3),
    Category("购物", "🛍️", LedgerTheme.chart4),
    Category("转账", "💸", LedgerTheme.accent),
    Category("交通", "🚌", LedgerTheme.chart5),
    Category("消费", "💳", LedgerTheme.chart3),
    Category("人情社交", "👥", LedgerTheme.accent),
    Category("娱乐", "🎮", LedgerTheme.chart3),
    Category("教育", "📚", LedgerTheme.chart4),
    Category("通讯", "📱", LedgerTheme.chart5),
    Category("美容", "💄", LedgerTheme.chart6),
    Category("医疗", "🏥", LedgerTheme.danger),
    Category("红包", "🧧", LedgerTheme.chart6),
    Category("旅行", "✈️", LedgerTheme.chart5),
    Category("宠物", "🐾", LedgerTheme.chart3),
    Category("住房", "🏠", LedgerTheme.chart2),
    Category("代付", "🤝", LedgerTheme.chart3),
    Category("投资", "📈", LedgerTheme.chart4),
    Category("借出", "📤", LedgerTheme.accent),
    Category("还债", "💰", LedgerTheme.chart3),
    Category("亲子", "👨‍👩‍👧", LedgerTheme.chart6),
    Category("添加", "＋", LedgerTheme.accent),
)

private val incomeCategories = listOf(
    Category("转账", "💳", LedgerTheme.accent),
    Category("红包", "🧧", LedgerTheme.chart6),
    Category("退款", "↩️", LedgerTheme.chart2),
    Category("其他", "⚪", LedgerTheme.textSecondary),
    Category("薪资", "💼", LedgerTheme.income),
    Category("理财", "📈", LedgerTheme.chart4),
    Category("借入", "📥", LedgerTheme.chart3),
    Category("收债", "📤", LedgerTheme.chart5),
    Category("添加", "＋", LedgerTheme.accent),
)

private val otherCategories = listOf(
    Category("提现", "💵", LedgerTheme.chart3),
    Category("还款", "💳", LedgerTheme.accent),
    Category("充值", "⚡", LedgerTheme.chart3),
    Category("代付", "🤝", LedgerTheme.chart6),
)

private fun categoriesFor(context: android.content.Context, type: Int): List<Category> =
    CategoryStore.names(context, type).map {
        Category(it, CategoryStore.emoji(it), CategoryStore.color(it))
    }

@Composable
fun AddScreen(modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableIntStateOf(0) }
    var amountInput by rememberSaveable { mutableStateOf("") }
    var negative by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(CategoryStore.expense.first()) }
    val calendar = remember { Calendar.getInstance() }
    var timeText by rememberSaveable {
        mutableStateOf(SimpleDateFormat("今天 HH:mm", Locale.CHINA).format(calendar.time))
    }
    var showPicker by remember { mutableStateOf(false) }
    var pickYear by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var pickMonth by remember { mutableIntStateOf(calendar.get(Calendar.MONTH)) }
    var pickDay by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }
    var pickHour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var pickMinute by remember { mutableIntStateOf(calendar.get(Calendar.MINUTE)) }
    val currentCategories = categoriesFor(context, type)

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

    fun applyDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        calendar.set(year, month, day, hour, minute)
        val now = Calendar.getInstance()
        val sameDay = now.get(Calendar.YEAR) == year &&
            now.get(Calendar.MONTH) == month &&
            now.get(Calendar.DAY_OF_MONTH) == day
        timeText = SimpleDateFormat(
            if (sameDay) "今天 HH:mm" else "MM月dd日 HH:mm",
            Locale.CHINA,
        ).format(calendar.time)
        showPicker = false
    }

    fun save(reset: Boolean) {
        val raw = amountInput.toDoubleOrNull() ?: 0.0
        if (raw <= 0.0) {
            Toast.makeText(context, "请输入金额", Toast.LENGTH_SHORT).show()
            return
        }
        val typeValue = when (type) {
            1 -> "income"
            2 -> "other"
            else -> "expense"
        }
        scope.launch {
            runCatching {
                LedgerRepository.add(
                    context,
                    LedgerEntry(
                        id = 0L,
                        amount = raw,
                        type = typeValue,
                        category = selectedCategory,
                        time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(calendar.time),
                        note = note,
                        source = "手动记账",
                    ),
                )
            }.onFailure {
                Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        Toast.makeText(context, "已记录 ${selectedCategory} ¥ %.2f".format(raw), Toast.LENGTH_SHORT).show()
        amountInput = ""
        negative = false
        note = ""
        if (!reset) onDone()
    }

    Column(modifier = modifier.fillMaxSize().background(PageBg)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TypeDateRow(
                type = type,
                onType = {
                    type = it
                    selectedCategory = categoriesFor(context, it).first().name
                },
                timeText = timeText,
                onTime = { openPicker() },
            )
            Spacer(Modifier.height(12.dp))
            AmountCard(
                amount = displayAmount(),
                note = note,
                onNoteChange = { note = it },
                onCamera = { Toast.makeText(context, "拍照待接入", Toast.LENGTH_SHORT).show() },
                onGallery = { Toast.makeText(context, "相册待接入", Toast.LENGTH_SHORT).show() },
            )
            Spacer(Modifier.height(12.dp))
            CategoryCard(
                categories = currentCategories,
                selected = selectedCategory,
                onSelect = { selectedCategory = it },
            )
            Spacer(Modifier.height(16.dp))
        }

        Keyboard(
            onKey = ::pressKey,
            onSaveAgain = { save(reset = true) },
            onDone = { save(reset = false) },
        )
    }

    if (showPicker) {
        DateTimeDialog(
            initialYear = pickYear,
            initialMonth = pickMonth,
            initialDay = pickDay,
            initialHour = pickHour,
            initialMinute = pickMinute,
            onDismiss = { showPicker = false },
            onConfirm = { y, m, d, h, mi -> applyDateTime(y, m, d, h, mi) },
        )
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
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) TextPrimary else TextSecondary,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Months,
            contentDescription = "时间",
            tint = AccentBlue,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text = timeText, style = TextStyle(fontSize = 11.sp, color = AccentBlue))
    }
}

@Composable
private fun DateTimeDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int, Int) -> Unit,
) {
    var year by remember { mutableIntStateOf(initialYear) }
    var month by remember { mutableIntStateOf(initialMonth) }
    var day by remember { mutableIntStateOf(initialDay) }
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }

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

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(LedgerTheme.card)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Column {
                Text(
                    text = "编辑日期",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
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
                            .size(32.dp)
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
                            style = TextStyle(fontSize = 10.sp, color = TextSecondary),
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
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) AccentBlue else Color.Transparent)
                                            .clickable { day = value },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = value.toString(),
                                            style = TextStyle(
                                                fontSize = 13.sp,
                                                color = if (selected) LedgerTheme.onAccent else TextPrimary,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        repeat(7 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
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
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LedgerTheme.inset)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "取消", style = TextStyle(fontSize = 14.sp, color = TextSecondary))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentBlue)
                            .clickable { onConfirm(year, month, day, hour, minute) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "确认", style = TextStyle(fontSize = 14.sp, color = LedgerTheme.onAccent))
                    }
                }
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
            modifier = Modifier.size(24.dp).clickable(onClick = onUp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "▲", style = TextStyle(fontSize = 9.sp, color = TextSecondary))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "%02d".format(value), style = TextStyle(fontSize = 24.sp, color = AccentBlue))
            Spacer(Modifier.width(2.dp))
            Text(text = unit, style = TextStyle(fontSize = 10.sp, color = TextSecondary))
        }
        Box(
            modifier = Modifier.size(24.dp).clickable(onClick = onDown),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "▼", style = TextStyle(fontSize = 9.sp, color = TextSecondary))
        }
    }
}

@Composable
private fun AmountCard(
    amount: String,
    note: String,
    onNoteChange: (String) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false),
        cornerRadius = 20.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "¥", style = TextStyle(fontSize = 20.sp, color = TextPrimary))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = amount,
                    style = TextStyle(
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = if (amount == "0.00") LedgerTheme.textTertiary else LedgerTheme.text,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = note,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                cursorBrush = SolidColor(AccentBlue),
                decorationBox = { inner ->
                    Box {
                        if (note.isEmpty()) {
                            Text(
                                text = "点击添加备注",
                                style = TextStyle(fontSize = 14.sp, color = TextSecondary),
                            )
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(LedgerTheme.divider),
            )
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCamera) {
                    Text("📷", fontSize = 19.sp)
                }
                IconButton(onClick = onGallery) {
                    Text("🖼️", fontSize = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    categories: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false),
        cornerRadius = 20.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) {
            categories.chunked(5).forEachIndexed { index, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                    if (selected) Modifier.border(1.5.dp, AccentBlue, CircleShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
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
                    Text(text = category.emoji, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = category.name,
            style = TextStyle(
                fontSize = 10.sp,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LedgerTheme.card)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(LedgerTheme.divider),
        )
        Spacer(Modifier.height(10.dp))

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
            .clip(RoundedCornerShape(13.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = style, color = contentColor)
    }
}