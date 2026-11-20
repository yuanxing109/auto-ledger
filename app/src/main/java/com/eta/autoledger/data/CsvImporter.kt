package com.eta.autoledger.data

import android.content.Context
import java.util.regex.Pattern

object CsvImporter {

    private val dateRegex = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?")
    private val amountRegex = Pattern.compile("-?\\d+(?:\\.\\d{1,2})?")

    suspend fun import(context: Context, raw: String): Int {
        val lines = raw.lineSequence()
            .map { it.trim().trim('\uFEFF') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return 0
        val (validFrom, validTo) = parseFileRange(raw)

        val headerIndex = lines.indexOfFirst { line ->
            val cells = parseLine(line, detectDelimiter(line))
            cells.any { it.contains("金额") } &&
                cells.any { it.contains("时间") || it.contains("日期") } &&
                cells.any { it.contains("收/支") || it.contains("收支") || it.contains("交易类型") }
        }

        val result = mutableListOf<LedgerEntry>()
        if (headerIndex >= 0) {
            val delimiter = detectDelimiter(lines[headerIndex])
            val header = parseLine(lines[headerIndex], delimiter)
            val isWechat = header.any { it.contains("交易单号") } || header.any { it.contains("交易类型") }

            val timeIdx = header.indexOfFirst { it.contains("交易时间") || it.contains("时间") || it.contains("日期") }
            val amountIdx = header.indexOfFirst { it.contains("金额") }
            val typeIdx = header.indexOfFirst { it.contains("收/支") || it == "收支" || it.contains("收支") }
            val tradeTypeIdx = header.indexOfFirst { it.contains("交易类型") }
            val counterpartIdx = header.indexOfFirst { it.contains("交易对方") }
            val productIdx = header.indexOfFirst { it.contains("商品") }
            val remarkIdx = header.indexOfFirst { it.contains("备注") }
            val orderIdx = header.indexOfFirst { it.contains("交易单号") }
            val categoryIdx = header.indexOfFirst { it.contains("记账分类") || (it.contains("分类") && !it.contains("子类")) }
            val subCategoryIdx = header.indexOfFirst { it.contains("子类") }

            lines.drop(headerIndex + 1).forEach { line ->
                val cells = parseLine(line, delimiter)
                if (cells.size <= maxOf(amountIdx, 0)) return@forEach
                val rawType = cells.getOrNull(typeIdx).orEmpty().trim()
                if (rawType.contains("中性") || rawType.contains("不计收支")) return@forEach
                val amount = parseAmount(cells.getOrNull(amountIdx).orEmpty()) ?: return@forEach
                if (amount <= 0.0) return@forEach

                val rawTime = cells.getOrNull(timeIdx).orEmpty().ifBlank { LedgerRepository.formatTime() }
                val order = cells.getOrNull(orderIdx).orEmpty().trim()
                var time = normalizeTime(rawTime)
                if (!isPlausibleTime(time)) {
                    val (orderDate, orderClock) = extractOrderDateTime(order, validFrom, validTo)
                    val clock = Regex("\\d{2}:\\d{2}(?::\\d{2})?").find(rawTime)?.value
                        ?: clockFromFraction(rawTime)
                        ?: orderClock
                    time = when {
                        orderDate != null && clock != null -> "$orderDate $clock"
                        orderDate != null -> "$orderDate 00:00:00"
                        else -> LedgerRepository.formatTime()
                    }
                }
                if (result.size < 3) {
                    android.util.Log.d("CsvImport", "rawTime=$rawTime order=$order -> $time")
                }
                val tradeType = cells.getOrNull(tradeTypeIdx).orEmpty().trim()
                val counterpart = cells.getOrNull(counterpartIdx).orEmpty().trim()
                val product = cells.getOrNull(productIdx).orEmpty().trim()
                val remark = cells.getOrNull(remarkIdx).orEmpty().trim()

                val isIncome = rawType.contains("收入") ||
                    (!rawType.contains("支出") && line.contains("收入") && !line.contains("支出"))

                val categoryCell = cells.getOrNull(categoryIdx).orEmpty().trim()
                val subCell = cells.getOrNull(subCategoryIdx).orEmpty().trim()
                val category = categoryCell.ifBlank { subCell }.ifBlank {
                    wechatCategory(tradeType, counterpart, product, remark)
                }

                val note = if (isWechat) {
                    listOf(counterpart, product, remark)
                        .filter { it.isNotBlank() && it != "/" && !it.startsWith("商户单号") }
                        .distinct()
                        .joinToString(" · ")
                        .take(120)
                } else {
                    cells.getOrNull(remarkIdx).orEmpty().take(120)
                }

                val externalId = if (order.isNotBlank() && order != "/") "wx:$order" else null

                result.add(
                    LedgerEntry(
                        id = 0L,
                        amount = amount,
                        type = if (isIncome) "income" else "expense",
                        category = category,
                        time = time,
                        note = note,
                        source = if (isWechat) "微信导入" else "CSV 导入",
                        externalId = externalId,
                    ),
                )
            }
        } else {
            lines.filterNot { it.contains("金额") || it.contains("交易") }.forEach { line ->
                val cleaned = dateRegex.matcher(line).replaceAll(" ")
                val matcher = amountRegex.matcher(cleaned)
                if (!matcher.find()) return@forEach
                val amount = matcher.group().toDoubleOrNull()?.let { kotlin.math.abs(it) } ?: return@forEach
                if (amount <= 0.0) return@forEach
                val isIncome = line.contains("收入") && !line.contains("支出")
                result.add(
                    LedgerEntry(
                        id = 0L,
                        amount = amount,
                        type = if (isIncome) "income" else "expense",
                        category = detectCategory(line),
                        time = LedgerRepository.formatTime(),
                        note = line.take(80),
                        source = "CSV 导入",
                    ),
                )
            }
        }

        return LedgerRepository.addImported(context, result)
    }

    fun export(entries: List<LedgerEntry>): String = buildString {
        appendLine("id,type,category,amount,time,note,source")
        entries.forEach { e ->
            appendLine(
                listOf(
                    e.id,
                    e.type,
                    esca(e.category),
                    "%.2f".format(e.amount),
                    esca(e.time),
                    esca(e.note),
                    esca(e.source),
                ).joinToString(","),
            )
        }
    }

    private fun detectDelimiter(line: String): Char =
        if (line.count { it == '\t' } > line.count { it == ',' }) '\t' else ','

    private fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace("¥", "").replace("￥", "").replace(",", "").trim()
        return amountRegex.matcher(cleaned).let { if (it.find()) it.group().toDoubleOrNull() else null }
    }

    private fun normalizeTime(raw: String): String {
        val value = raw.trim()
        val serial = value.toDoubleOrNull()
        if (serial != null && serial in 20000.0..80000.0) {
            val base = java.util.Calendar.getInstance().apply {
                set(1899, java.util.Calendar.DECEMBER, 30, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            // 必须用 Long：46345 天 * 86400 秒会超过 Int 上限
            base.timeInMillis += (serial * 86_400_000.0).toLong()
            return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(base.time)
        }
        return value
    }

    private fun parseLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        cells.add(current.toString().trim())
        return cells
    }

    private fun esca(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun isPlausibleTime(raw: String): Boolean {
        val cal = LedgerRepository.parseTime(raw) ?: return false
        val year = cal.get(java.util.Calendar.YEAR)
        return year in 2000..2100
    }

    private fun parseFileRange(raw: String): Pair<String?, String?> {
        val from = Regex("起始时间：\\[(\\d{4}-\\d{2}-\\d{2})").find(raw)?.groupValues?.get(1)
        val to = Regex("终止时间：\\[(\\d{4}-\\d{2}-\\d{2})").find(raw)?.groupValues?.get(1)
        return from to to
    }

    private fun extractOrderDateTime(order: String, from: String?, to: String?): Pair<String?, String?> {
        val matches = Regex("(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(\\d{2})(\\d{2})(\\d{2})")
            .findAll(order)
        val candidates = matches.map { m ->
            val date = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
            val clock = "${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
            date to clock
        }.toList()
        val inRange = candidates.firstOrNull { (date, _) ->
            (from == null || date >= from) && (to == null || date <= to)
        }
        if (inRange != null) return inRange
        // 没有文件范围时，接受 2020-2030 的日期，避免再次出现 206x/207x
        return candidates.firstOrNull { (date, _) ->
            date >= "2020-01-01" && date <= "2030-12-31"
        } ?: (null to null)
    }

    private fun clockFromFraction(raw: String): String? {
        val value = raw.toDoubleOrNull() ?: return null
        if (value !in 0.0..1.0) return null
        val total = Math.round(value * 86400).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return "%02d:%02d:%02d".format(h, m, sec)
    }


    private fun wechatCategory(tradeType: String, counterpart: String, product: String, remark: String): String {
        val text = "$tradeType $counterpart $product $remark"
        return when {
            tradeType.contains("商户消费") || tradeType.contains("扫二维码") || tradeType.contains("二维码") -> "消费"
            tradeType.contains("转账") || tradeType.contains("群收款") -> "转账"
            tradeType.contains("红包") -> "红包"
            tradeType.contains("退款") -> "退款"
            else -> detectCategory(text)
        }
    }

    private fun detectCategory(line: String): String = when {
        line.contains("餐饮") || line.contains("饭") || line.contains("餐") || line.contains("外卖") -> "餐饮"
        line.contains("交通") || line.contains("地铁") || line.contains("公交") || line.contains("打车") || line.contains("加油") -> "交通"
        line.contains("购物") || line.contains("超市") || line.contains("淘宝") || line.contains("京东") -> "购物"
        line.contains("红包") -> "红包"
        line.contains("转账") -> "转账"
        line.contains("工资") || line.contains("薪资") -> "薪资"
        line.contains("医疗") || line.contains("药") -> "医疗"
        line.contains("娱乐") || line.contains("电影") -> "娱乐"
        line.contains("消费") -> "消费"
        else -> "其他"
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}