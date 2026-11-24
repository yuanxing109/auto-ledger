package com.eta.autoledger.network

import android.util.Log
import com.eta.autoledger.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI 兼容接口的统一 HTTP 客户端，负责鉴权、超时、请求和 JSON 清理。 */
object AiClient {

    const val FORMAT_ERROR_MESSAGE = "解析失败：AI 返回格式不标准"

    fun textCompletion(
        prefs: AppPrefs,
        prompt: String,
        maxTokens: Int = 8,
    ): String = chat(
        baseUrl = prefs.aiBaseUrl,
        key = prefs.aiKey,
        model = prefs.aiModel,
        messages = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            },
        ),
        maxTokens = maxTokens,
    )

    fun chat(
        baseUrl: String,
        key: String,
        model: String,
        messages: JSONArray,
        maxTokens: Int,
        temperature: Double = 0.1,
    ): String {
        val url = endpoint(baseUrl, "/chat/completions")
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("messages", messages)
        }
        return request("POST", url, key, payload.toString())
            .let(::extractMessageContent)
    }

    fun fetchModels(baseUrl: String, key: String): List<String> {
        val body = request("GET", endpoint(baseUrl, "/models"), key, null)
        val data = JSONObject(body).optJSONArray("data") ?: error("响应中没有 data")
        return buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }
    }

    fun testConnection(baseUrl: String, key: String, model: String) {
        val messages = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            },
        )
        chat(baseUrl, key, model, messages, maxTokens = 1)
    }

    /** 从模型输出里提取最大的 JSON 对象，容错 Markdown 代码块与解释文字。 */
    fun parseJsonObject(raw: String): JSONObject? {
        val text = raw
            .replace("```json", " ")
            .replace("```JSON", " ")
            .replace("```", " ")
            .replace("`", " ")
            .trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null

        JSON_OBJECT_PATTERN.find(text)?.value?.let { candidate ->
            runCatching { JSONObject(candidate) }.getOrNull()?.let { return it }
        }

        return largestBalancedJsonObject(text)
    }

    /** 记录模型原始响应并抛出统一格式异常，避免把思考过程带到 UI。 */
    fun invalidResponse(rawText: String): Nothing {
        Log.e(AI_ERROR_TAG, rawText)
        throw AiResponseFormatException(rawText)
    }

    private fun largestBalancedJsonObject(text: String): JSONObject? {
        var validObject: JSONObject? = null
        var validLength = 0
        var searchFrom = 0
        while (true) {
            val start = text.indexOf('{', searchFrom)
            if (start < 0) break
            val end = balancedObjectEnd(text, start)
            if (end == null) {
                searchFrom = start + 1
                continue
            }
            val candidate = text.substring(start, end + 1)
            val parsed = runCatching { JSONObject(candidate) }.getOrNull()
            if (parsed != null && candidate.length > validLength) {
                validObject = parsed
                validLength = candidate.length
            }
            searchFrom = start + 1
        }
        return validObject
    }

    private fun balancedObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun request(method: String, url: String, key: String, payload: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        val code: Int
        val body: String
        try {
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            code = connection.responseCode
            body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } finally {
            connection.disconnect()
        }
        if (code !in 200..299) error("HTTP $code ${body.take(160)}")
        return body
    }

    internal fun extractMessageContent(body: String): String {
        val root = runCatching { JSONObject(body) }
            .getOrElse { error("响应不是 JSON：${body.take(120)}") }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = sequenceOf(
            stringOrNull(message, "content"),
            stringOrNull(choice, "text"),
            stringOrNull(root, "content"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        if (content.isBlank()) {
            stringOrNull(message, "reasoning_content")?.let { Log.e(AI_ERROR_TAG, it) }
            error("模型返回为空，请确认该模型支持当前输入")
        }
        return content
    }

    private fun stringOrNull(obj: JSONObject?, key: String): String? {
        if (obj == null) return null
        val value = obj.opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        val text = value.toString().trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        return text
    }

    private fun endpoint(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (!base.endsWith("/v1")) base += "/v1"
        return base + path
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val AI_ERROR_TAG = "AI_ERROR"
    private val JSON_OBJECT_PATTERN = Regex("""\{[\s\S]*\}""")
}

class AiResponseFormatException(
    val rawText: String,
) : IllegalArgumentException(AiClient.FORMAT_ERROR_MESSAGE)
