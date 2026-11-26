package com.eta.autoledger.screenshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import com.eta.autoledger.data.CategoryInference
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.MatchSource
import com.eta.autoledger.network.AiClient
import com.eta.autoledger.network.AiPrompt
import com.eta.autoledger.network.AiResponseFormatException
import com.eta.autoledger.util.AiDateTimeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class ParsedEntry(
    val amount: Double,
    val type: String,
    val category: String,
    val time: String,
    val note: String,
    val subCategory: String? = null,
)

object VisionHelper {

    /**
     * 图片 MIME 必须由调用方传入：相册导入走 JPEG，截屏服务走 PNG，
     * 早先写死 "image/png" 会让 JPEG 的 base64 被当成 PNG 解析而报 400。
     */
    fun callVisionModel(
        prefs: AppPrefs,
        base64: String,
        mime: String = MIME_JPEG,
        expenseCategories: List<String> = CategoryStore.expense,
        incomeCategories: List<String> = CategoryStore.income,
    ): List<ParsedEntry> {
        // 相册与截屏两条调用路径可能各自已经拼过 data URI 前缀，
        // 再拼一次就变成 data:image/jpeg;base64,data:image/jpeg;base64,/9j/…
        // 服务端要么 400，要么回一段不是 JSON 的错误页 —— 统一在这里剥干净。
        val pure = stripDataUriPrefix(base64)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", strictPrompt(prefs.aiPrompt, expenseCategories, incomeCategories))
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:" + mime + ";base64," + pure)
                })
            })
        }
        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })
        val contentText = AiClient.chat(
            baseUrl = prefs.aiBaseUrl,
            key = prefs.aiKey,
            model = prefs.aiModel,
            messages = messages,
            maxTokens = 1_200,
        )
        return parseResponse(contentText, expenseCategories, incomeCategories)
    }

    internal fun parseResponse(
        contentText: String,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
    ): List<ParsedEntry> {
        val payload = AiClient.parseJson(contentText)
            ?: AiClient.invalidResponse(contentText)
        val transactions = when (payload) {
            is com.eta.autoledger.network.AiJsonPayload.ObjectValue -> {
                val obj = payload.value
                when {
                    obj.has("transactions") -> obj.optJSONArray("transactions")
                        ?: AiClient.invalidResponse(contentText)
                    obj.length() == 0 -> JSONArray()
                    else -> JSONArray().put(obj)
                }
            }
            is com.eta.autoledger.network.AiJsonPayload.ArrayValue -> payload.value
        }
        if (transactions.length() == 0) return emptyList()
        val parsed = buildList {
            for (index in 0 until transactions.length()) {
                val obj = transactions.optJSONObject(index) ?: continue
                parseTransaction(obj, expenseCategories, incomeCategories)?.let(::add)
            }
        }
        if (parsed.isEmpty()) AiClient.invalidResponse(contentText)
        return parsed
    }

    private fun parseTransaction(
        obj: JSONObject,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
    ): ParsedEntry? = runCatching {
        val amount = obj.optDouble("amount", 0.0)
        if (!amount.isFinite() || amount <= 0.0) {
            return@runCatching null
        }
        val rawType = cleanField(obj, "type").lowercase(Locale.ROOT)
        val type = if (rawType == "income") "income" else "expense"
        val available = if (type == "income") incomeCategories else expenseCategories
        val contractCategories = available.filter { it in CategoryStore.aiPrimaryCategories }
        val modelCategory = cleanField(obj, "category")
            .takeIf { it in contractCategories }
            ?: contractCategories.firstOrNull { it == "其他" }
            ?: contractCategories.firstOrNull()
            ?: "其他"
        val note = cleanField(obj, "note").take(120)
        if (note.isBlank()) return@runCatching null

        // AI 只负责候选，最终分类由本地规则二次裁决；规则兜底时保留模型给出的有效分类。
        val inferred = CategoryInference.infer(note, contractCategories)
        val category = if (inferred.source != MatchSource.FALLBACK) {
            inferred.category
        } else {
            modelCategory
        }
        val modelSubCategory = if (modelCategory == category) {
            CategoryStore.validSubCategory(category, cleanField(obj, "subCategory"))
        } else {
            null
        }
        val subCategory = if (inferred.source != MatchSource.FALLBACK && inferred.category == category) {
            CategoryStore.validSubCategory(category, inferred.subCategory) ?: modelSubCategory
        } else {
            modelSubCategory
        }
        ParsedEntry(
            amount = amount,
            type = type,
            category = category,
            subCategory = subCategory,
            time = saneTime(cleanField(obj, "time")),
            note = note,
        )
    }.getOrNull()

    /** 将已校正的识图结果转换成待批量写入的 Entity。 */
    internal fun buildLedgerEntries(
        entries: List<ParsedEntry>,
        externalIdPrefix: String? = null,
    ): List<LedgerEntry> = entries.mapIndexed { index, entry ->
        LedgerEntry(
            id = 0L,
            amount = entry.amount,
            type = entry.type,
            category = entry.category,
            subCategory = entry.subCategory,
            time = entry.time,
            note = entry.note,
            source = "截屏记账",
            externalId = externalIdPrefix?.let { "$it:$index" },
        )
    }

    internal fun encodeEntries(entries: List<ParsedEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("amount", entry.amount)
                put("type", entry.type)
                put("category", entry.category)
                put("subCategory", entry.subCategory.orEmpty())
                put("time", entry.time)
                put("note", entry.note)
            })
        }
    }.toString()

    internal fun decodeEntries(raw: String): List<ParsedEntry> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val amount = obj.optDouble("amount", 0.0)
                if (!amount.isFinite() || amount <= 0.0) continue
                val type = cleanField(obj, "type").let {
                    if (it == "income") "income" else "expense"
                }
                val note = cleanField(obj, "note").take(120)
                if (note.isBlank()) continue
                add(
                    ParsedEntry(
                        amount = amount,
                        type = type,
                        category = cleanField(obj, "category").ifBlank { "其他" },
                        subCategory = cleanField(obj, "subCategory").ifBlank { null },
                        time = cleanField(obj, "time").ifBlank { saneTime("") },
                        note = note,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 取字符串字段并把「占位空值」抹成空串。
     *
     * 关键坑：JSON 里写成 null 时 `optString` 返回的是**字面量 "null"**，不是空串，
     * 于是分类会变成「null」、备注会变成「null」。模型返回 {"note": null} 很常见。
     */
    private fun cleanField(obj: JSONObject, key: String): String {
        val raw = obj.optString(key, "").trim()
        if (raw.isEmpty()) return ""
        return when (raw.lowercase(Locale.ROOT)) {
            "null", "nil", "undefined", "n/a", "none" -> ""
            else -> raw
        }
    }

    /** 剥掉 data URI 前缀（含换行与空格），只留纯 base64 正文。 */
    internal fun stripDataUriPrefix(raw: String): String {
        val trimmed = raw.trim()
        // 用 lastIndexOf：调用方可能已经拼过一次前缀，再拼一次就是
        // "data:image/png;base64,data:image/png;base64,<正文>"，
        // 取第一个会把内层前缀留在正文里。
        val marker = trimmed.lastIndexOf("base64,")
        val body = if (marker >= 0) trimmed.substring(marker + "base64,".length) else trimmed
        return body.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
    }

    /** 用户 Prompt 只作为辅助场景说明，字段契约始终由统一骨架控制。 */
    internal fun strictPrompt(
        base: String,
        expenseCategories: List<String> = CategoryStore.expense,
        incomeCategories: List<String> = CategoryStore.income,
    ): String = AiPrompt.forVision(
        businessHint = base,
        primaryCategories = CategoryStore.aiPrimaryCategories,
        subCategoryTree = CategoryStore.subCategoryTreeFor(CategoryStore.aiPrimaryCategories),
        expenseCategories = expenseCategories,
        incomeCategories = incomeCategories,
    )

    /**
     * 模型偶尔会编出离谱日期（当前 11 月却返回「2026-02-12 07:22」），
     * 与当前时间相差超过 3 天就丢弃，改用现在。
     */
    private fun saneTime(raw: String): String {
        val now = System.currentTimeMillis()
        val resolved = AiDateTimeResolver.resolve(raw, "", now)
        return if (abs(resolved.timeMillis - now) <= THREE_DAYS_MS) {
            resolved.display
        } else {
            AiDateTimeResolver.resolve("", "", now).display
        }
    }

    /** 模型原文只进入 Logcat，通知与页面只呈现稳定、可理解的错误文案。 */
    fun userFacingError(error: Throwable): String {
        val hasFormatError = generateSequence(error) { it.cause }
            .any { it is AiResponseFormatException }
        if (hasFormatError) return AiClient.FORMAT_ERROR_MESSAGE
        val message = error.message.orEmpty()
        return if (message.contains("配置 AI")) {
            "识别失败：$message"
        } else {
            "识别失败，请检查 AI 配置或网络"
        }
    }

    suspend fun readImageBase64(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        // 1024 已足够看清账单金额与商户名，base64 体积比 1280 小约 36%。
        val maxDim = 1024
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("解码图片失败")
        val width = decoded.width
        val height = decoded.height
        val scale = if (max(width, height) > maxDim) maxDim.toFloat() / max(width, height) else 1f
        val scaled = decoded.scale(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != decoded) decoded.recycle()
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        scaled.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 确保「识图记账」渠道存在；体检页跳转渠道设置前调用，避免系统页因渠道缺失崩溃。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "识图记账", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun postResult(context: Context, entries: List<ParsedEntry>, error: String?) {
        val channel = NotificationChannel(CHANNEL, "识图记账", NotificationManager.IMPORTANCE_HIGH)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle("识图记账")

        if (error != null) {
            builder.setContentText(error)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
        } else if (entries.isEmpty()) {
            builder.setContentTitle("未识别到交易")
                .setContentText("图片中没有识别到明确发生的账单")
                .setStyle(NotificationCompat.BigTextStyle().bigText("图片中没有识别到明确发生的账单"))
        } else if (entries.size == 1) {
            val entry = entries.single()
            val signed = signedAmount(entry)
            val categoryLabel = categoryLabel(entry.category, entry.subCategory)
            builder.setContentTitle(notificationTitle(entries))
                .setContentText(listOf(categoryLabel, entry.time, entry.note).filter { it.isNotBlank() }.joinToString(" · "))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "分类：$categoryLabel\n金额：$signed\n时间：${entry.time}\n备注：${entry.note}",
                    ),
                )
            addConfirmActions(context, builder, entries)
        } else {
            builder.setContentTitle(notificationTitle(entries))
                .setContentText(entries.joinToString(" · ") { signedAmount(it) })
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        entries.joinToString("\n") { entry ->
                            "${categoryLabel(entry.category, entry.subCategory)} · ${entry.note} · ${signedAmount(entry)}"
                        },
                    ),
                )
            addConfirmActions(context, builder, entries)
        }
        runCatching { manager.notify(ScreenshotCaptureService.RESULT_ID, builder.build()) }
    }

    /** 兼容单条调用点；新链路统一传列表。 */
    fun postResult(context: Context, entry: ParsedEntry?, error: String?) =
        postResult(context, listOfNotNull(entry), error)

    private fun addConfirmActions(
        context: Context,
        builder: NotificationCompat.Builder,
        entries: List<ParsedEntry>,
    ) {
        val token = UUID.randomUUID().toString()
        val confirm = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, ConfirmEntryReceiver::class.java).apply {
                action = ConfirmEntryReceiver.ACTION_CONFIRM
                putExtra(ConfirmEntryReceiver.EXTRA_ENTRIES_JSON, encodeEntries(entries))
                putExtra(ConfirmEntryReceiver.EXTRA_CONFIRM_TOKEN, token)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            4,
            Intent(context, ConfirmEntryReceiver::class.java).apply {
                action = ConfirmEntryReceiver.ACTION_CANCEL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, "确认入账", confirm)
        builder.addAction(0, "忽略", cancel)
    }

    /** 相册导入：readImageBase64 统一压成 JPEG。 */
    const val MIME_JPEG = "image/jpeg"

    /** 截屏服务：Bitmap.CompressFormat.PNG。 */
    const val MIME_PNG = "image/png"

    private const val CHANNEL = "screenshot_result"

    private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

    private fun max(a: Int, b: Int) = if (a > b) a else b

    private fun categoryLabel(category: String, subCategory: String?): String {
        val child = subCategory?.trim().orEmpty()
        return if (child.isEmpty()) category else "$category · $child"
    }

    private fun typeLabel(type: String): String = if (type == "income") "收入" else "支出"

    private fun signedAmount(entry: ParsedEntry): String =
        (if (entry.type == "income") "+" else "-") + "¥%.2f".format(Locale.ROOT, entry.amount)

    internal fun notificationTitle(entries: List<ParsedEntry>): String = when {
        entries.isEmpty() -> "未识别到交易"
        entries.size == 1 -> {
            val entry = entries.single()
            "识别到${typeLabel(entry.type)} ${signedAmount(entry)}"
        }
        else -> "识别到 ${entries.size} 笔账单，共 ¥%.2f，点击确认入账"
            .format(Locale.ROOT, entries.sumOf { it.amount })
    }
}
