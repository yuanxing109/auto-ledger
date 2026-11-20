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

    fun callVisionModel(prefs: AppPrefs, base64: String): ParsedEntry {
        val url = endpoint(prefs.aiBaseUrl, "/chat/completions")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (prefs.aiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${prefs.aiKey}")
            }
        }
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prefs.aiPrompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/png;base64,$base64")
                })
            })
        }
        val messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })
        val payload = JSONObject().apply {
            put("model", prefs.aiModel)
            put("temperature", 0.1)
            put("max_tokens", 400)
            put("messages", messages)
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(160)}")

        val root = runCatching { JSONObject(body) }.getOrElse {
            error("响应不是 JSON（HTTP $code）：${body.take(120)}")
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val contentText = sequenceOf(
            message?.optString("content"),
            message?.optString("reasoning_content"),
            choice?.optString("text"),
            root.optString("content"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        if (contentText.isBlank()) {
            error("模型返回为空（HTTP $code），请确认该模型支持图片输入")
        }
        val cleaned = contentText.replace("```json", "").replace("```", "").trim()
        val jsonText = Regex("\\{[\\s\\S]*\\}").find(cleaned)?.value
            ?: error("模型没有返回 JSON：${cleaned.take(120)}")
        val obj = JSONObject(jsonText)
        return ParsedEntry(
            amount = obj.optDouble("amount", 0.0),
            type = obj.optString("type", "expense"),
            category = obj.optString("category", "其他"),
            time = saneTime(obj.optString("time", "")),
            note = obj.optString("note", ""),
        )
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
        val maxDim = 1280
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
        runCatching { manager.notify(9003, builder.build()) }
    }

    private const val CHANNEL = "screenshot_result"

    private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

    private fun endpoint(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (!base.endsWith("/v1")) base += "/v1"
        return base + path
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}