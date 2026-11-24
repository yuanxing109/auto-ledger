package com.eta.autoledger.data

import android.content.Context
import com.eta.autoledger.util.AppDateFormats
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern

object CsvImporter {

    private val dateRegex = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)")
    private val amountRegex = Pattern.compile("-?\\d+(?:\\.\\d{1,2})?")

    /**
     * 单笔记账的合理上限。
     *
     * 超过 100 万基本只有一种可能：解析错位把「交易订单号 / 商家订单号 / 流水号」
     * 当成了金额。这类长数字串动辄 20 位以上，直接 toDouble() 会溢出成天文数字
     * （甚至 Double.MAX_VALUE / Infinity），必须在入库前拦掉。
     */
    private const val MAX_REASONABLE_AMOUNT = 1_000_000.0

    /** 金额整数部分最多几位：9 位足够覆盖到亿级，再长必然是单号。 */
    private const val MAX_AMOUNT_INTEGER_DIGITS = 9

    /**
     * 把账单文件字节解码成文本。
     *
     * 支付宝导出的 CSV 既有 UTF-8 也有 GBK / GB18030，历史版本还有纯 GBK。
     * 如果一律按 UTF-8 硬读，GBK 文件里的中文会全部变成替换字符，
     * 于是「交易时间 / 交易分类 / 金额」这些表头关键词一个都匹配不上，
     * 解析器会掉进逐行正则的兜底分支 —— 那正是「订单号当金额、金额溢出、
     * 不计收支被当成支出」这一串灾难的直接起点。
     *
     * 判定顺序：UTF-8 BOM → 严格 UTF-8 → GB18030（兼容 GBK / GB2312）。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        strictDecode(bytes, Charset.forName("GB18030"))?.let { return it }
        return String(bytes, Charsets.UTF_8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** 只解析不入库：导入预览要先看清单，再由用户决定写哪些。 */
    fun parse(raw: String): List<LedgerEntry> {
        val lines = raw.lineSequence()
            .map { it.trim().trim('\uFEFF') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()
        val (validFrom, validTo) = parseFileRange(raw)

        // 支付宝账单优先：它的表头前面有 20 多行导出说明，且列名与微信不同，
        // 必须走独立的按列名取值分支，不能和微信共用一套启发式。
        val alipayHeader = findAlipayHeader(lines)
        if (alipayHeader >= 0) {
            return parseAlipay(lines, alipayHeader, validFrom, validTo)
        }

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
                if (line.contains("--------")) return@forEach
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
            // 没有表头时的兜底：只接受「日期 + 一个像金额的数字」的行。
            // 这里同样要拦长串单号，否则乱码文件会把订单号记成天文数字。
            lines.filterNot { it.contains("金额") || it.contains("交易") }.forEach { line ->
                if (line.contains("--------")) return@forEach
                if (line.contains("不计收支") || line.contains("中性")) return@forEach
                val cleaned = dateRegex.matcher(line).replaceAll(" ")
                val amount = parseAmountSafe(cleaned) ?: return@forEach
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

        return result
    }

    // ------------------------------------------------------------------
    // 支付宝账单
    // ------------------------------------------------------------------

    /**
     * 找出支付宝账单真正的表头行。
     *
     * 支付宝导出的 CSV 前面有 20 多行说明（账号、起止时间、汇总统计、分隔线），
     * 表头不在第一行。判定条件是同时出现「交易时间」「交易分类」「金额」三个列名 ——
     * 说明文字里不会有「交易分类」，所以不会误命中。
     *
     * @return 表头行下标；找不到返回 -1。
     */
    private fun findAlipayHeader(lines: List<String>): Int {
        for (i in lines.indices) {
            val cells = parseLine(lines[i], detectDelimiter(lines[i]))
            if (cells.size < 3) continue
            val hasTime = cells.any { it.contains("交易时间") }
            val hasCategory = cells.any { it.contains("交易分类") }
            val hasAmount = cells.any { it.contains("金额") }
            if (hasTime && hasCategory && hasAmount) return i
        }
        return -1
    }

    /** 按列名取下标，绝不写死位置 —— 支付宝不同年份导出的列顺序会变。 */
    private fun List<String>.indexOfHeader(vararg keywords: String): Int =
        indexOfFirst { cell -> keywords.any { keyword -> cell.contains(keyword) } }

    /**
     * 支付宝分支：表头之后逐行取值。
     *
     * 与微信分支的差别：
     * - 分类直接用支付宝的「交易分类」列，再映射到本 App 的分类名；
     * - 备注固定拼成「商品说明 - 交易对方」，商品说明为空则用交易分类；
     * - 「不计收支」（花呗还款、转账、提现）与失败/关闭状态一律跳过。
     */
    private fun parseAlipay(
        lines: List<String>,
        headerIndex: Int,
        validFrom: String?,
        validTo: String?,
    ): List<LedgerEntry> {
        val delimiter = detectDelimiter(lines[headerIndex])
        val header = parseLine(lines[headerIndex], delimiter)

        val timeIdx = header.indexOfHeader("交易时间")
        val categoryIdx = header.indexOfHeader("交易分类")
        val counterpartIdx = header.indexOfHeader("交易对方")
        val productIdx = header.indexOfHeader("商品说明", "商品")
        val directionIdx = header.indexOfHeader("收/支", "收支")
        val amountIdx = header.indexOfHeader("金额")
        val statusIdx = header.indexOfHeader("交易状态")
        val orderIdx = header.indexOfHeader("交易订单号", "交易单号")

        // 没有金额或时间列，说明这不是能解析的账单明细，交给调用方按「空结果」处理
        if (amountIdx < 0 || timeIdx < 0) return emptyList()

        val result = mutableListOf<LedgerEntry>()
        for (line in lines.drop(headerIndex + 1)) {
            // 支付宝在明细列表的首尾都放 ---------------- 分隔行
            if (line.contains("--------")) continue
            val cells = parseLine(line, delimiter)
            if (cells.size <= amountIdx) continue

            val direction = cells.getOrNull(directionIdx).orEmpty().trim()
            // 花呗还款 / 转账 / 提现 / 信用卡还款 都属于「不计收支」，不是真实消费
            if (direction.contains("不计收支") || direction.contains("中性")) continue
            if (!direction.contains("支出") && !direction.contains("收入")) continue

            val status = cells.getOrNull(statusIdx).orEmpty().trim()
            if (status.contains("还款失败") || status.contains("交易关闭") || status.contains("已关闭")) continue

            val amount = parseAmountSafe(cells.getOrNull(amountIdx).orEmpty()) ?: continue

            val rawTime = cells.getOrNull(timeIdx).orEmpty().trim()
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

            val product = cells.getOrNull(productIdx).orEmpty().trim()
            val counterpart = cells.getOrNull(counterpartIdx).orEmpty().trim()
            val categoryRaw = cells.getOrNull(categoryIdx).orEmpty().trim()

            val category = alipayCategory(categoryRaw)

            // 备注 = 商品说明 - 交易对方；商品说明为空时退回交易分类
            val head = product.ifBlank { categoryRaw }.ifBlank { category }
            val note = if (counterpart.isBlank()) head else "$head - $counterpart"

            // 支付宝账单没有「收/支」以外的方向线索，这里只认这一列
            val isIncome = direction.contains("收入")

            result.add(
                LedgerEntry(
                    id = 0L,
                    amount = amount,
                    type = if (isIncome) "income" else "expense",
                    category = category,
                    time = time,
                    note = note.take(120),
                    source = "支付宝导入",
                    // 订单号是支付宝账单里唯一稳定的去重键
                    externalId = if (order.isNotBlank() && order != "/") "ali:$order" else null,
                ),
            )
        }
        return result
    }

    /** 支付宝「交易分类」→ 本 App 的分类名（与 CategoryStore 的默认项对齐）。 */
    private fun alipayCategory(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "其他"
        return when {
            text.contains("餐饮") || text.contains("美食") || text.contains("外卖") -> "餐饮"
            text.contains("交通") || text.contains("出行") || text.contains("打车") -> "交通"
            text.contains("购物") || text.contains("日用") || text.contains("百货") ||
                text.contains("服饰") || text.contains("数码") || text.contains("超市") -> "购物"
            text.contains("红包") -> "红包"
            text.contains("转账") -> "转账"
            text.contains("住房") || text.contains("物业") || text.contains("房租") -> "住房"
            text.contains("医疗") || text.contains("健康") || text.contains("药") -> "医疗"
            text.contains("娱乐") || text.contains("休闲") || text.contains("文化") -> "娱乐"
            text.contains("教育") || text.contains("培训") || text.contains("学习") -> "教育"
            text.contains("通讯") || text.contains("话费") || text.contains("充值") -> "通讯"
            text.contains("旅行") || text.contains("酒店") || text.contains("旅游") -> "旅行"
            text.contains("投资") || text.contains("理财") || text.contains("基金") -> "投资"
            text.contains("美容") || text.contains("美发") || text.contains("美甲") -> "美容"
            text.contains("宠物") -> "宠物"
            text.contains("亲子") || text.contains("母婴") -> "亲子"
            text.contains("还款") || text.contains("信用") -> "还债"
            text.contains("商业服务") || text.contains("服务") -> "消费"
            else -> "其他"
        }
    }

    /**
     * 支付宝金额清洗：只保留数字、小数点和正负号，并拦掉长串单号。
     *
     * 之所以不复用 [parseAmount]：那个版本用 `matcher.find()` 取第一个数字，
     * 在「交易订单号」被错位读进金额列时会原样返回整串 20 位数字。
     */
    private fun parseAmountSafe(raw: String): Double? {
        if (raw.isBlank()) return null
        val cleaned = buildString {
            for (ch in raw) {
                when {
                    ch.isDigit() || ch == '.' -> append(ch)
                    // 千分位、货币符号、全角空格、BOM 一律丢弃
                    ch == ',' || ch == ' ' || ch == '¥' || ch == '￥' ||
                        ch == '\u00A0' || ch == '\uFEFF' || ch == '+' -> Unit
                    ch == '-' && isEmpty() -> append(ch)
                }
            }
        }
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        // 多个小数点说明这压根不是金额
        if (cleaned.count { it == '.' } > 1) return null

        val integerPart = cleaned.substringBefore('.').trimStart('-')
        if (integerPart.isEmpty()) return null
        // 20 位订单号 / 流水号必然超过 9 位整数，直接判为非金额
        if (integerPart.length > MAX_AMOUNT_INTEGER_DIGITS) return null

        val value = cleaned.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        val abs = kotlin.math.abs(value)
        if (abs <= 0.0 || abs > MAX_REASONABLE_AMOUNT) return null
        return abs
    }

    /** 无预览的直连导入，保留给备份恢复等不需要交互的路径。 */
    suspend fun import(context: Context, raw: String): Int =
        LedgerRepository.addImported(context, parse(raw))

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
            return AppDateFormats.format(base, AppDateFormats.dateTimeSeconds)
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
}
