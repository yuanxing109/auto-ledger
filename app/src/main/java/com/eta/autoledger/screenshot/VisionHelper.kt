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
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ParsedEntry(
    val amount: Double,
    val type: String,
    val category: String,
    val time: String,
    val note: String,
)

object VisionHelper {

    /**
     * 图片 MIME 必须由调用方传入：相册导入走 JPEG，截屏服务走 PNG，
     * 早先写死 "image/png" 会让 JPEG 的 base64 被当成 PNG 解析而报 400。
     */
    fun callVisionModel(prefs: AppPrefs, base64: String, mime: String = MIME_JPEG): ParsedEntry {
        // 相册与截屏两条调用路径可能各自已经拼过 data URI 前缀，
        // 再拼一次就变成 data:image/jpeg;base64,data:image/jpeg;base64,/9j/…
        // 服务端要么 400，要么回一段不是 JSON 的错误页 —— 统一在这里剥干净。
        val pure = stripDataUriPrefix(base64)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", strictPrompt(prefs.aiPrompt))
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
        val contentText = postChat(prefs, messages, 400)
        val obj = parseJsonObject(contentText)
            ?: error("模型没有返回 JSON：" + contentText.take(120))
        val amount = obj.optDouble("amount", 0.0)
        if (!amount.isFinite() || amount <= 0.0) {
            error("模型返回的金额无效：" + contentText.take(120))
        }
        val rawType = cleanField(obj, "type").lowercase(Locale.ROOT)
        val type = if (rawType == "income" || rawType == "other") rawType else "expense"
        val category = cleanField(obj, "category").ifBlank { "其他" }.take(12)
        return ParsedEntry(
            amount = amount,
            type = type,
            category = category,
            time = saneTime(cleanField(obj, "time")),
            note = cleanField(obj, "note").take(120),
        )
    }

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

    /**
     * 在用户提示词后追加硬约束。用户自定义提示词通常只写「只输出 JSON」，
     * 模型仍会包一层 ```json 或补一段「好的，我来分析…」。
     */
    internal fun strictPrompt(base: String): String {
        val suffix = "只输出一个 JSON 对象，不要 Markdown 代码块标记，不要任何解释文字。" +
            "字段固定为 amount(数字)、type(expense/income/other)、category、time(yyyy-MM-dd HH:mm)、note。"
        return if (base.contains(suffix)) base else base.trim() + "\n" + suffix
    }

    /** 纯文本补全：导入去重的 YES/NO 判定复用同一端点与鉴权，避免第二套 HTTP 代码。 */
    fun callTextModel(prefs: AppPrefs, prompt: String, maxTokens: Int = 8): String {
        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })
        return postChat(prefs, messages, maxTokens)
    }

    private fun postChat(prefs: AppPrefs, messages: JSONArray, maxTokens: Int): String {
        val url = endpoint(prefs.aiBaseUrl, "/chat/completions")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (prefs.aiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer " + prefs.aiKey)
            }
        }
        val payload = JSONObject().apply {
            put("model", prefs.aiModel)
            put("temperature", 0.1)
            put("max_tokens", maxTokens)
            put("messages", messages)
        }
        val code: Int
        val body: String
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            code = conn.responseCode
            body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } finally {
            conn.disconnect()
        }
        if (code !in 200..299) error("HTTP " + code + " " + body.take(160))
        val root = runCatching { JSONObject(body) }.getOrElse {
            error("响应不是 JSON（HTTP " + code + "）：" + body.take(120))
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        // 必须用 opt + JSONObject.NULL 判空：字段是 JSON null 时，
        // optString 会返回字面量 "null"，于是下游把 "null" 当成模型正文去解析 JSON，
        // 报出来就是「模型没有返回 JSON：null」——这正是识图失败最常见的一种。
        val contentText = sequenceOf(
            stringOrNull(message, "content"),
            stringOrNull(message, "reasoning_content"),
            stringOrNull(choice, "text"),
            stringOrNull(root, "content"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        if (contentText.isBlank()) {
            error("模型返回为空（HTTP " + code + "），请确认该模型支持当前输入")
        }
        return contentText
    }

    /** 取字符串字段；字段缺失或为 JSON null 时返回 null，而不是字面量 "null"。 */
    private fun stringOrNull(obj: JSONObject?, key: String): String? {
        if (obj == null) return null
        val value = obj.opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        return if (text.equals("null", ignoreCase = true)) null else text
    }

    /**
     * 从模型输出里取第一个完整的 JSON 对象：按括号深度扫描，并跳过字符串里的括号。
     * 比贪婪正则 "\\{[\\s\\S]*\\}" 稳——模型后面多解释几句、正文里带花括号也不会截错。
     */
    internal fun parseJsonObject(raw: String): JSONObject? {
        // 三种常见跑偏：整段被 ```json 包住、外面套一层数组、直接回字面量 null。
        val text = raw
            .replace("\u0060\u0060\u0060json", " ")
            .replace("\u0060\u0060\u0060JSON", " ")
            .replace("\u0060\u0060\u0060", " ")
            .replace("\u0060", " ")
            .trim()
        if (text.isEmpty()) return null
        if (text.equals("null", ignoreCase = true)) return null
        if (text.startsWith("[")) {
            val arrEnd = text.lastIndexOf(']')
            if (arrEnd > 0) {
                runCatching { JSONArray(text.substring(0, arrEnd + 1)) }.getOrNull()
                    ?.takeIf { it.length() > 0 }
                    ?.optJSONObject(0)
                    ?.let { return it }
            }
        }
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /**
     * 模型偶尔会编出离谱日期（当前 11 月却返回「2026-02-12 07:22」），
     * 与当前时间相差超过 3 天就丢弃，改用现在。
     */
    private fun saneTime(raw: String): String {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val parsed = runCatching { fmt.parse(raw.trim())?.time }.getOrNull()
        return if (parsed != null && abs(parsed - now) <= THREE_DAYS_MS) {
            fmt.format(Date(parsed))
        } else {
            fmt.format(Date(now))
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
        val scaled = Bitmap.createScaledBitmap(
            decoded,
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

    fun postResult(context: Context, entry: ParsedEntry?, error: String?) {
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

        if (entry == null) {
            builder.setContentText(error ?: "处理失败")
                .setStyle(NotificationCompat.BigTextStyle().bigText(error ?: "处理失败"))
        } else {
            val sign = if (entry.type == "income") "+" else "-"
            builder.setContentTitle("识别到${if (entry.type == "income") "收入" else "支出"} $sign¥ %,.2f".format(entry.amount))
                .setContentText(listOf(entry.category, entry.time, entry.note).filter { it.isNotBlank() }.joinToString(" · "))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "分类：${entry.category}\n金额：$sign¥ %,.2f\n时间：${entry.time}\n备注：${entry.note}".format(entry.amount),
                    ),
                )
            val confirm = PendingIntent.getBroadcast(
                context,
                3,
                Intent(context, ConfirmEntryReceiver::class.java).apply {
                    action = ConfirmEntryReceiver.ACTION_CONFIRM
                    putExtra("amount", entry.amount)
                    putExtra("type", entry.type)
                    putExtra("category", entry.category)
                    putExtra("time", entry.time)
                    putExtra("note", entry.note)
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
        runCatching { manager.notify(ScreenshotCaptureService.RESULT_ID, builder.build()) }
    }

    /** 相册导入：readImageBase64 统一压成 JPEG。 */
    const val MIME_JPEG = "image/jpeg"

    /** 截屏服务：Bitmap.CompressFormat.PNG。 */
    const val MIME_PNG = "image/png"

    private const val CHANNEL = "screenshot_result"

    private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

    private fun endpoint(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (!base.endsWith("/v1")) base += "/v1"
        return base + path
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}