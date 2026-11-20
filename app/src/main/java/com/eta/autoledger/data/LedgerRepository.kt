package com.eta.autoledger.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import com.eta.autoledger.widget.LedgerWidgetProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object LedgerRepository {

    fun dao(context: Context): LedgerDao = LedgerDatabase.get(context).ledgerDao()

    fun observeAll(context: Context): Flow<List<LedgerEntry>> = dao(context).observeAll()

    fun observePaging(context: Context, limit: Int, offset: Int): Flow<List<LedgerEntry>> =
        dao(context).observePaging(limit, offset)

    fun observeCount(context: Context): Flow<Int> = dao(context).observeCount()

    fun observeSummary(context: Context): Flow<Summary> {
        val now = Calendar.getInstance()
        val todayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val tomorrow = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val monthStart = (todayStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }

        val d = dao(context)
        return combine(
            d.observeExpense(todayStart.timeInMillis, tomorrow.timeInMillis),
            d.observeExpense(monthStart.timeInMillis, monthEnd.timeInMillis),
            d.observeIncome(monthStart.timeInMillis, monthEnd.timeInMillis),
        ) { today, month, income -> Summary(today, month, income) }
    }

    // 兼容同步调用：数据量小或已在后台线程使用。
    fun load(context: Context): List<LedgerEntry> = runBlocking { dao(context).getAllOnce() }

    fun add(context: Context, entry: LedgerEntry) = addAll(context, listOf(entry))

    fun addAll(context: Context, entries: List<LedgerEntry>) {
        if (entries.isEmpty()) return
        val normalized = entries.map { it.normalized() }
        runBlocking { dao(context).insertAll(normalized) }
        LedgerWidgetProvider.requestUpdate(context)
    }

    /** 导入去重：优先交易单号，其次时间+金额+收支指纹；返回实际新增数。 */
    fun addImported(context: Context, entries: List<LedgerEntry>): Int {
        if (entries.isEmpty()) return 0
        val dao = dao(context)
        val existing = runBlocking { dao.getAllOnce() }
        val keys = mutableSetOf<String>()
        existing.forEach {
            it.externalId?.takeIf { id -> id.isNotBlank() }?.let(keys::add)
            keys.add(it.fingerprint())
        }
        val normalized = entries.map { it.normalized() }
        val toInsert = normalized
            .filter { entry -> keys.add(entry.importKey()) }
            .distinctBy { it.importKey() }
        if (toInsert.isNotEmpty()) {
            runBlocking { dao.insertAll(toInsert) }
            LedgerWidgetProvider.requestUpdate(context)
        }
        return toInsert.size
    }

    private fun LedgerEntry.normalized(): LedgerEntry =
        if (timeMillis > 0) this else copy(
            timeMillis = parseTime(time)?.timeInMillis ?: System.currentTimeMillis(),
        )

    private fun LedgerEntry.fingerprint(): String =
        "$time|${"%.2f".format(amount)}|$type"

    private fun LedgerEntry.importKey(): String =
        externalId?.takeIf { it.isNotBlank() } ?: fingerprint()

    /** 修复旧版本导入导致的 timestamp=0 数据。 */
    fun repairTimestamps(context: Context) {
        runBlocking {
            val dao = dao(context)
            dao.getMissingTimestamp().forEach { entry ->
                val millis = parseTime(entry.time)?.timeInMillis ?: System.currentTimeMillis()
                dao.setTimestamp(entry.id, millis)
            }
        }
    }

    fun update(context: Context, entry: LedgerEntry) {
        runBlocking { dao(context).update(entry) }
        LedgerWidgetProvider.requestUpdate(context)
    }

    fun delete(context: Context, id: Long) {
        runBlocking { dao(context).deleteById(id) }
        LedgerWidgetProvider.requestUpdate(context)
    }

    fun clear(context: Context) {
        runBlocking { dao(context).clear() }
        LedgerWidgetProvider.requestUpdate(context)
    }

    fun parseTime(raw: String): Calendar? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/M/d H:mm",
            "yyyy/M/d",
            "MM月dd日 HH:mm",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).parse(raw)
            }.getOrNull()
            if (parsed != null) return Calendar.getInstance().apply { time = parsed }
        }
        return null
    }

    fun formatTime(cal: Calendar = Calendar.getInstance()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(cal.time)

    fun dayLabel(cal: Calendar): String =
        SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(cal.time) + " " + weekday(cal)

    fun weekday(cal: Calendar): String = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "星期一"
        Calendar.TUESDAY -> "星期二"
        Calendar.WEDNESDAY -> "星期三"
        Calendar.THURSDAY -> "星期四"
        Calendar.FRIDAY -> "星期五"
        Calendar.SATURDAY -> "星期六"
        else -> "星期日"
    }
}