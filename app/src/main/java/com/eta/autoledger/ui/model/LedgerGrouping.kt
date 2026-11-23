package com.eta.autoledger.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.ui.LedgerTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * 账单分组的纯数据层。
 *
 * 从 HomeScreen.kt 搬出来是为了让 [HomeViewModel] 也能调用 —— 分组不再在组合期做，
 * 而是随分页增量维护。这里只依赖 [LedgerTheme]（它是普通 object + getter，
 * 不是 CompositionLocal），所以离开 @Composable 上下文依然可用。
 *
 * 日期格式化器提到文件级，并且用 java.time.DateTimeFormatter 而不是 SimpleDateFormat：
 * - 构造成本：SimpleDateFormat 每次 new 要建 DateFormatSymbols 并加载 locale 数据，是重操作。
 *   原实现每条账目 new 两次（分组一次、toUi 一次），2525 条就是 5000+ 次构造。
 * - 线程安全：SimpleDateFormat 内部有可变 Calendar，跨线程用会算出错乱的日期。
 *   本文件的 mergeIntoTail 现在跑在 Dispatchers.Default 上（HomeViewModel），
 *   首屏 groupEntries 也在 ViewModel 协程里，必须换成不可变的 DateTimeFormatter。
 */
private val dayKeyFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.CHINA).withZone(ZoneId.systemDefault())

private val clockFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())

/**
 * DateTimeFormatter 只认 TemporalAccessor，java.util.Date 不是，所以统一走 Instant。
 * 两个小函数把「毫秒 → 字符串」收在一处，调用点不必再碰 Calendar。
 */
private fun dayKeyOf(millis: Long): String = dayKeyFmt.format(Instant.ofEpochMilli(millis))

private fun clockOf(millis: Long): String = clockFmt.format(Instant.ofEpochMilli(millis))

/**
 * 标记为 @Immutable：所有字段都是 val 且类型不可变（String/Double/Color/ImmutableList），
 * Compose 可以据此跳过不必要的重组。不加这个注解时，编译器只看到 data class，
 * 无法证明调用方不会改它，每次父级重组都会重新执行子 Composable。
 */
@Immutable
data class EntryUi(
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

/**
 * [entries] 用 ImmutableList 而非 List：`List` 是接口，Compose 无法证明它不会被原地修改，
 * 于是把整个 DayGroupUi 判为不稳定；ImmutableList 是带稳定性契约的实现类型，
 * 配合 @Immutable 才能让「分组没变的日子」在翻页时被真正跳过。
 */
@Immutable
data class DayGroupUi(
    /** 纯日期键（yyyy.MM.dd），用于增量合并时判断「是否同一天」；dateLabel 带星期，不适合比较。 */
    val dayKey: String,
    val dateLabel: String,
    val entries: ImmutableList<EntryUi>,
    val expense: Double,
    val income: Double,
)

fun entryCalendar(entry: LedgerEntry): Calendar? =
    if (entry.timeMillis > 0) Calendar.getInstance().apply { timeInMillis = entry.timeMillis }
    else LedgerRepository.parseTime(entry.time)

/** 全量分组。仅用于首屏与整表刷新；翻页走 [mergeIntoTail]。 */
fun groupEntries(entries: List<LedgerEntry>): List<DayGroupUi> {
    if (entries.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<LedgerEntry>>()
    entries.forEach { entry ->
        val cal = entryCalendar(entry) ?: Calendar.getInstance()
        val key = dayKeyOf(cal.timeInMillis)
        grouped.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return grouped.map { (key, list) -> buildGroup(key, list) }
}

/**
 * 增量合并：把「更旧的一页」接到已有分组尾部，只对 [newRows] 做转换。
 *
 * 这是消除 O(n²) 的关键。原实现在 UI 用 `remember(entries)` 缓存分组，
 * 而 loadMore 会 `extraPages + more` 产生新 List → key 变化 → 已加载的 N 条全部重新分组。
 * 翻到第 k 页时单次开销 = N，累计 ΣN ≈ O(n²)。
 *
 * 账单按 timestamp DESC 排列，所以新页一定接在尾部：
 * - 若新页首行与末组同一天，并入末组（只重算这一组的合计）
 * - 其余行按日期新开组
 *
 * 复杂度从「每次翻页 O(已加载总数)」降到「每次翻页 O(新增页大小)」。
 */
fun mergeIntoTail(
    existing: List<DayGroupUi>,
    newRows: List<LedgerEntry>,
): List<DayGroupUi> {
    if (newRows.isEmpty()) return existing
    if (existing.isEmpty()) return groupEntries(newRows)

    val result = existing.toMutableList()
    var consumed = 0

    // 第一段：可能并入末组
    val firstKey = dayKeyOf((entryCalendar(newRows[0]) ?: Calendar.getInstance()).timeInMillis)
    val last = result.last()
    if (last.dayKey == firstKey) {
        val sameDay = ArrayList<LedgerEntry>()
        for (row in newRows) {
            val key = dayKeyOf((entryCalendar(row) ?: Calendar.getInstance()).timeInMillis)
            if (key != firstKey) break
            sameDay.add(row)
            consumed++
        }
        val addUi = sameDay.map { it.toEntryUi() }
        var addExpense = 0.0
        var addIncome = 0.0
        addUi.forEach { ui ->
            if (ui.signedAmount < 0) addExpense += -ui.signedAmount else addIncome += ui.signedAmount
        }
        result[result.lastIndex] = last.copy(
            // 用 addAll 构造新的 ImmutableList：`last.entries + addUi` 会退化成普通 List，
            // 类型一变 DayGroupUi 就不再是稳定的，@Immutable 的收益会被抹掉。
            entries = (last.entries + addUi).toImmutableList(),
            expense = last.expense + addExpense,
            income = last.income + addIncome,
        )
    }

    // 第二段：剩余行按日期新开组（只处理这几行，不碰前面已算好的组）
    if (consumed < newRows.size) {
        result.addAll(groupEntries(newRows.subList(consumed, newRows.size)))
    }
    return result
}

private fun buildGroup(key: String, list: List<LedgerEntry>): DayGroupUi {
    val ui = list.map { it.toEntryUi() }
    var expense = 0.0
    var income = 0.0
    // 单次遍历替代原来的两次 filter + 两次 sumOf
    ui.forEach { item ->
        if (item.signedAmount < 0) expense += -item.signedAmount else income += item.signedAmount
    }
    val cal = list.firstOrNull()?.let { entryCalendar(it) } ?: Calendar.getInstance()
    return DayGroupUi(
        dayKey = key,
        dateLabel = "$key ${LedgerRepository.weekday(cal)}",
        entries = ui.toImmutableList(),
        expense = expense,
        income = income,
    )
}

fun LedgerEntry.toEntryUi(): EntryUi {
    val signed = if (type == "income") amount else -kotlin.math.abs(amount)
    val cal = entryCalendar(this) ?: Calendar.getInstance()
    val timeText = clockOf(cal.timeInMillis)
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

fun categoryEmoji(category: String): String = when (category) {
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

fun categoryColor(category: String): Color = when (category) {
    "餐饮" -> LedgerTheme.chart3
    "购物" -> LedgerTheme.chart4
    "转账" -> LedgerTheme.accent
    "交通" -> LedgerTheme.chart5
    "红包" -> LedgerTheme.danger
    "旅行" -> LedgerTheme.chart5
    "医疗" -> LedgerTheme.danger
    "教育" -> LedgerTheme.chart4
    "娱乐" -> LedgerTheme.chart3
    else -> LedgerTheme.chart5
}

fun sourceColor(source: String): Color = when {
    source.contains("短信") -> LedgerTheme.chart5
    source.contains("微信") -> LedgerTheme.chart2
    source.contains("美团") -> LedgerTheme.chart3
    source.contains("银行") -> LedgerTheme.chart4
    source.contains("截屏") -> LedgerTheme.accent
    else -> LedgerTheme.chart2
}
