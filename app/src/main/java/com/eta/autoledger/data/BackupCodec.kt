package com.eta.autoledger.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份文件的编解码。
 *
 * 用 JSON 而不是直接拷 SQLite 文件：换机恢复时 Room 版本可能不同，
 * 直接换 db 文件会被 schema 校验拒绝；JSON 走 `addImported` 的既有迁移与去重逻辑，
 * 跨版本、跨渠道都能落地。
 */
object BackupCodec {

    const val VERSION = 3

    fun encode(entries: List<LedgerEntry>, exportedAt: Long = System.currentTimeMillis()): ByteArray {
        val bills = JSONArray()
        entries.forEach { e ->
            bills.put(
                JSONObject().apply {
                    put("amount", e.amount)
                    put("type", e.type)
                    put("category", e.category)
                    put("time", e.time)
                    put("timeMillis", e.timeMillis)
                    put("note", e.note)
                    put("source", e.source)
                    if (!e.externalId.isNullOrBlank()) put("externalId", e.externalId)
                    // 周期模板必须随备份走，否则换机后周期记账全丢
                    if (e.isRecurring) {
                        put("isRecurring", true)
                        put("recurringRule", e.recurringRule)
                    }
                    if (e.subCategory != null) put("subCategory", e.subCategory)
                    if (e.isTransfer && !e.transferPairId.isNullOrBlank()) {
                        put("isTransfer", true)
                        put("transferPairId", e.transferPairId)
                    }
                },
            )
        }
        val root = JSONObject().apply {
            put("app", "auto-ledger")
            put("version", VERSION)
            put("exportedAt", exportedAt)
            put("count", entries.size)
            put("bills", bills)
        }
        return root.toString().toByteArray()
    }

    /** 解析失败抛 [org.json.JSONException] / [IllegalArgumentException]，由调用方转成提示。 */
    fun decode(raw: ByteArray): List<LedgerEntry> {
        val text = raw.toString(Charsets.UTF_8).trim()
        // 兼容：根是对象 {"bills":[...]}，或者是纯数组 [...]
        val array = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val root = JSONObject(text)
            val version = root.optInt("version", 0)
            require(version in 1..VERSION) { "备份文件版本不支持：$version" }
            root.optJSONArray("bills") ?: throw IllegalArgumentException("备份文件里没有 bills 字段")
        }
        val out = ArrayList<LedgerEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val amount = o.optDouble("amount", 0.0)
            if (amount <= 0.0) continue
            val type = o.optString("type", "expense").ifBlank { "expense" }
            out += LedgerEntry(
                id = 0L,
                amount = amount,
                type = type,
                category = o.optString("category", "其他").ifBlank { "其他" },
                time = o.optString("time", ""),
                timeMillis = o.optLong("timeMillis", 0L),
                note = o.optString("note", ""),
                source = o.optString("source", "云恢复").ifBlank { "云恢复" },
                externalId = o.optString("externalId", "").ifBlank { null },
                isRecurring = o.optBoolean("isRecurring", false),
                recurringRule = o.optString("recurringRule", "").ifBlank { null },
                subCategory = o.optString("subCategory", "").ifBlank { null },
                isTransfer = o.optBoolean("isTransfer", false),
                transferPairId = o.optString("transferPairId", "").ifBlank { null },
            )
        }
        return out
    }
}
