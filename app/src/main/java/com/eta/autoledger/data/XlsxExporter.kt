package com.eta.autoledger.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极简 XLSX 生成器：单个 worksheet + inlineStr，不依赖 POI / 第三方库。
 * 列固定为：时间 / 类型 / 分类 / 金额 / 备注 / 来源。
 */
object XlsxExporter {

    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private const val CONTENT_TYPES = DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK = DECL +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"账单\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
        "</workbook>"

    private const val WORKBOOK_RELS = DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "</Relationships>"

    private val HEADER = listOf("时间", "类型", "分类", "金额", "备注", "来源")

    fun write(entries: List<LedgerEntry>, file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(build(entries))
    }

    fun build(entries: List<LedgerEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            putEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            putEntry(zip, "_rels/.rels", ROOT_RELS)
            putEntry(zip, "xl/workbook.xml", WORKBOOK)
            putEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            putEntry(zip, "xl/worksheets/sheet1.xml", sheetXml(entries))
        }
        return out.toByteArray()
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sheetXml(entries: List<LedgerEntry>): String = buildString {
        append(DECL)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols>")
        append("<col min=\"1\" max=\"1\" width=\"20\" customWidth=\"1\"/>")
        append("<col min=\"2\" max=\"2\" width=\"10\" customWidth=\"1\"/>")
        append("<col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/>")
        append("<col min=\"4\" max=\"4\" width=\"12\" customWidth=\"1\"/>")
        append("<col min=\"5\" max=\"5\" width=\"36\" customWidth=\"1\"/>")
        append("<col min=\"6\" max=\"6\" width=\"16\" customWidth=\"1\"/>")
        append("</cols><sheetData>")
        append("<row r=\"1\">")
        HEADER.forEachIndexed { index, title -> append(textCell(colName(index + 1), 1, title)) }
        append("</row>")
        entries.forEachIndexed { index, entry ->
            val row = index + 2
            append("<row r=\"$row\">")
            append(textCell("A", row, entry.time))
            append(textCell("B", row, typeLabel(entry.type)))
            append(textCell("C", row, entry.category))
            append(numberCell("D", row, entry.amount))
            append(textCell("E", row, entry.note))
            append(textCell("F", row, entry.source))
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun textCell(column: String, row: Int, text: String): String =
        "<c r=\"$column$row\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(text)}</t></is></c>"

    private fun numberCell(column: String, row: Int, value: Double): String =
        "<c r=\"$column$row\"><v>${String.format(Locale.US, "%.2f", value)}</v></c>"

    private fun typeLabel(type: String): String = when (type) {
        "income" -> "收入"
        "expense" -> "支出"
        "other" -> "其他"
        else -> type
    }

    private fun colName(index: Int): String {
        var rest = index
        val sb = StringBuilder()
        while (rest > 0) {
            val rem = (rest - 1) % 26
            sb.append(('A' + rem))
            rest = (rest - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        raw.forEach { ch ->
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= ' ') sb.append(ch)
            }
        }
        return sb.toString()
    }
}