package com.eta.autoledger.settings

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.window.LocalSnackbar
import android.app.DatePickerDialog
import android.content.Intent
import com.eta.autoledger.util.AppDateFormats
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.data.CsvImporter
import com.eta.autoledger.data.XlsxExporter
import com.eta.autoledger.data.LedgerRepository

internal enum class ExportFormat(
    val label: String,
    val hint: String,
    val extension: String,
    val mime: String,
) {
    Csv("CSV", "体积小，表格软件直接打开", "csv", "text/csv"),
    Xlsx("XLSX", "带列宽，Excel / WPS 原生格式", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/**
 * 导出范围预设。
 *
 * 「本月 / 本年」按自然月/自然年算，不跟随预算起始日——
 * 导出是为了拿到原始数据去别处分析，用通用口径比用个人预算周期更好理解。
 */
internal enum class ExportRange(val label: String) {
    ALL("全部"),
    THIS_MONTH("本月"),
    THIS_YEAR("本年"),
    CUSTOM("自定义"),
    ;

    /** 返回该范围的 [开始日期, 结束日期]，格式 yyyy-MM-dd。CUSTOM 保留调用方当前值。 */
    fun bounds(now: Calendar = Calendar.getInstance()): Pair<String, String> {
        return when (this) {
            ALL -> "1970-01-01" to AppDateFormats.format(now, AppDateFormats.date)
            THIS_MONTH -> {
                val s = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
                AppDateFormats.format(s, AppDateFormats.date) to AppDateFormats.format(now, AppDateFormats.date)
            }
            THIS_YEAR -> {
                val s = (now.clone() as Calendar).apply {
                    set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1)
                }
                AppDateFormats.format(s, AppDateFormats.date) to AppDateFormats.format(now, AppDateFormats.date)
            }
            CUSTOM -> "" to ""
        }
    }
}

@Composable
internal fun ExportPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val monthStart = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        AppDateFormats.format(cal, AppDateFormats.date)
    }
    val today = remember { AppDateFormats.format(System.currentTimeMillis(), AppDateFormats.date) }
    var start by remember { mutableStateOf(monthStart) }
    var end by remember { mutableStateOf(today) }
    // 导出范围：先选范围自动填日期，用户仍可再手动微调
    var range by remember { mutableStateOf(ExportRange.THIS_MONTH) }
    var format by remember { mutableStateOf(ExportFormat.Csv) }
    var working by remember { mutableStateOf(false) }

    fun pickDate(onSet: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(context, { _, y, m, d ->
            onSet("%04d-%02d-%02d".format(y, m + 1, d))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    SettingsPageColumn {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("导出范围", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExportRange.entries.forEach { item ->
                        val selected = range == item
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) AccentBlue else LedgerTheme.inset)
                                .clickable {
                                    range = item
                                    // 选范围即回填日期，避免用户还要自己算月初月末
                                    val (s0, e0) = item.bounds()
                                    start = s0
                                    end = e0
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = item.label,
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) LedgerTheme.onAccent else LedgerTheme.text,
                                ),
                            )
                        }
                    }
                }
                if (range != ExportRange.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "已按所选范围自动填好起止日期，仍可手动微调。",
                        style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
                    )
                }
            }
        }

        SettingsCard {
            ArrowPreference(title = "开始时间", summary = start.ifEmpty { "请选择" }, onClick = { pickDate { start = it } })
            RowDivider()
            ArrowPreference(title = "结束时间", summary = end.ifEmpty { "请选择" }, onClick = { pickDate { end = it } })
        }
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("导出格式", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExportFormat.entries.forEach { item ->
                        val selected = format == item
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) LedgerTheme.tint(LedgerTheme.accent) else LedgerTheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    if (selected) AccentBlue else LedgerTheme.border,
                                    RoundedCornerShape(16.dp),
                                )
                                .clickable { format = item }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            Text(
                                item.label,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) AccentBlue else TextPrimary,
                                ),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(item.hint, style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary))
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (working) LedgerTheme.tint(LedgerTheme.accent) else AccentBlue)
                .clickable(enabled = !working) {
                    if (start.isEmpty() || end.isEmpty()) {
                        scope.launch { snackbar.show("请先选择开始时间和结束时间") }
                        return@clickable
                    }
                    val targetFormat = format
                    working = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching<Pair<Int, File>?> {
                                val from = LedgerRepository.parseTime("$start 00:00")?.timeInMillis ?: 0L
                                val to = LedgerRepository.parseTime("$end 23:59")?.timeInMillis ?: Long.MAX_VALUE
                                val entries = LedgerRepository.load(context)
                                    .filter { entry ->
                                        val stamp = if (entry.timeMillis > 0) {
                                            entry.timeMillis
                                        } else {
                                            LedgerRepository.parseTime(entry.time)?.timeInMillis ?: 0L
                                        }
                                        stamp in from..to
                                    }
                                    .sortedByDescending { it.timeMillis }
                                if (entries.isEmpty()) {
                                    null
                                } else {
                                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                    val fileStamp = AppDateFormats.format(
                                        System.currentTimeMillis(),
                                        AppDateFormats.fileStamp,
                                    )
                                    val file = File(dir, "账单_$fileStamp.${targetFormat.extension}")
                                    if (targetFormat == ExportFormat.Csv) {
                                        file.writeText("\uFEFF" + CsvImporter.export(entries), Charsets.UTF_8)
                                    } else {
                                        XlsxExporter.write(entries, file)
                                    }
                                    entries.size to file
                                }
                            }
                        }
                        working = false
                        outcome.onSuccess { payload ->
                            if (payload == null) {
                                scope.launch { snackbar.show("该时间段没有账单") }
                            } else {
                                val (count, file) = payload
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = targetFormat.mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, "账单一共 $count 笔（${targetFormat.label}）")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "导出账单"))
                                scope.launch { snackbar.show("已生成 $count 笔账单 ${targetFormat.label}") }
                            }
                        }.onFailure { error ->
                            scope.launch { snackbar.show("导出失败：${error.message}") }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (working) "正在导出…" else "导出",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    // working 态底是 tint(accent) 的浅色块，onAccent 的白字压不住，改用主色文字
                    color = if (working) LedgerTheme.accent else LedgerTheme.onAccent,
                ),
            )
        }
    }
}
