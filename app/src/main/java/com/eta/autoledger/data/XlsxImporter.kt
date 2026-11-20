package com.eta.autoledger.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipFile

/** 轻量 XLSX 读取：解析 sharedStrings 与第一个 worksheet，再交给 CsvImporter。 */
object XlsxImporter {

    fun import(context: Context, uri: Uri): Int {
        val temp = File.createTempFile("ledger_import", ".xlsx", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return 0
            input.use { ins -> temp.outputStream().use { outs -> ins.copyTo(outs) } }

            val rows = ZipFile(temp).use { zip ->
                val shared = readSharedStrings(zip)
                val sheet = zip.getEntry("xl/worksheets/sheet1.xml")
                    ?: zip.entries().asSequence().firstOrNull {
                        it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml")
                    }
                    ?: return 0
                zip.getInputStream(sheet).use { parseRows(it.bufferedReader().readText(), shared) }
            }
            if (rows.isEmpty()) return 0
            val csv = rows.joinToString("\n") { row ->
                row.joinToString(",") { escape(it) }
            }
            return CsvImporter.import(context, csv)
        } catch (_: Throwable) {
            return 0
        } finally {
            temp.delete()
        }
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val xml = zip.getInputStream(entry).use { it.bufferedReader().readText() }
        val strings = mutableListOf<String>()
        Regex("<si[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { si ->
            val text = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(si.groupValues[1])
                .joinToString("") { unescape(stripTags(it.groupValues[1])) }
            strings.add(text)
        }
        return strings
    }

    private fun parseRows(xml: String, shared: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { rowMatch ->
            val cells = sortedMapOf<Int, String>()
            Regex("<c\\s+([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(rowMatch.groupValues[1]).forEach { cell ->
                val attrs = cell.groupValues[1]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val type = Regex("t=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                val inner = cell.groupValues[2]
                val raw = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                    .find(inner)?.groupValues?.get(1)
                    ?: Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .find(inner)?.groupValues?.get(1)
                    ?: ""
                val value = when (type) {
                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> unescape(stripTags(raw))
                    else -> unescape(stripTags(raw))
                }
                cells[columnIndex(ref)] = value
            }
            val max = cells.keys.maxOrNull() ?: -1
            result.add(List(max + 1) { cells[it] ?: "" })
        }
        return result
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        ref.forEach { ch -> n = n * 26 + (ch - 'A' + 1) }
        return (n - 1).coerceAtLeast(0)
    }

    private fun stripTags(value: String): String = value.replace(Regex("<[^>]+>"), "")

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    private fun escape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}