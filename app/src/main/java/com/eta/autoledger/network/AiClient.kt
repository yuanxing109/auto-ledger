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

    /** 模型输出可能是对象或数组；统一交由此入口做 Markdown/废话剥离。 */
    internal fun parseJson(raw: String): AiJsonPayload? {
        if (raw.isBlank() || raw.trim().equals("null", ignoreCase = true)) return null
        val candidates = jsonCandidates(raw)
        for (candidate in candidates) {
            runCatching { JSONObject(candidate) }.getOrNull()?.let {
                return AiJsonPayload.ObjectValue(it)
            }
            runCatching { JSONArray(candidate) }.getOrNull()?.let {
                return AiJsonPayload.ArrayValue(it)
            }
        }
        largestBalancedJsonObject(raw)?.let { return AiJsonPayload.ObjectValue(it) }
        largestBalancedJsonArray(raw)?.let { return AiJsonPayload.ArrayValue(it) }
        return null
    }

    /** 文本记账兼容旧契约：对象原样返回；单元素数组取第一个对象。 */
    fun parseJsonObject(raw: String): JSONObject? = when (val payload = parseJson(raw)) {
        is AiJsonPayload.ObjectValue -> payload.value
        is AiJsonPayload.ArrayValue -> payload.value.optJSONObject(0)
        null -> null
    }

    internal fun parseJsonArray(raw: String): JSONArray? = when (val payload = parseJson(raw)) {
        is AiJsonPayload.ObjectValue -> JSONArray().put(payload.value)
        is AiJsonPayload.ArrayValue -> payload.value
        null -> null
    }

    /** 记录模型原始响应并抛出统一格式异常，避免把思考过程带到 UI。 */
    fun invalidResponse(rawText: String): Nothing {
        Log.e(AI_ERROR_TAG, rawText)
        throw AiResponseFormatException(rawText)
    }

    private fun jsonCandidates(raw: String): List<String> {
        val candidates = linkedSetOf<String>()
        // 思考型模型可能先给示例代码块、再给最终结果；倒序读取，优先最终块。
        FENCED_JSON_PATTERN.findAll(raw).toList().asReversed().forEach { match ->
            val body = match.groupValues[1].trim()
            if (body.isNotEmpty()) candidates += body
            primaryJsonCandidate(body)?.let(candidates::add)
        }
        val withoutFence = FENCE_PATTERN.replace(raw, " ").trim()
        if (withoutFence.isNotEmpty()) candidates += withoutFence
        primaryJsonCandidate(withoutFence)?.let(candidates::add)
        return candidates.toList()
    }

    private fun primaryJsonCandidate(text: String): String? {
        val objectStart = text.indexOf('{')
        val arrayStart = text.indexOf('[')
        return when {
            objectStart < 0 && arrayStart < 0 -> null
            arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart) -> arrayCandidate(text)
            else -> objectCandidate(text)
        }
    }

    private fun objectCandidate(text: String): String? = JSON_OBJECT_PATTERN.find(text)?.value

    private fun arrayCandidate(text: String): String? = JSON_ARRAY_PATTERN.find(text)?.value

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

    private fun largestBalancedJsonArray(text: String): JSONArray? {
        var validArray: JSONArray? = null
        var validLength = 0
        var searchFrom = 0
        while (true) {
            val start = text.indexOf('[', searchFrom)
            if (start < 0) break
            val end = balancedJsonEnd(text, start, '[', ']')
            if (end == null) {
                searchFrom = start + 1
                continue
            }
            val candidate = text.substring(start, end + 1)
            val parsed = runCatching { JSONArray(candidate) }.getOrNull()
            if (parsed != null && candidate.length > validLength) {
                validArray = parsed
                validLength = candidate.length
            }
            searchFrom = start + 1
        }
        return validArray
    }

    private fun balancedObjectEnd(text: String, start: Int): Int? =
        balancedJsonEnd(text, start, '{', '}')

    private fun balancedJsonEnd(
        text: String,
        start: Int,
        open: Char,
        close: Char,
    ): Int? {
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
                open -> depth++
                close -> {
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
    private val JSON_ARRAY_PATTERN = Regex("""\[[\s\S]*\]""")
    private val FENCED_JSON_PATTERN =
        Regex("""```(?:[a-zA-Z0-9_-]+)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
    private val FENCE_PATTERN = Regex("""```(?:[a-zA-Z0-9_-]+)?|```""", RegexOption.IGNORE_CASE)
}

internal sealed interface AiJsonPayload {
    data class ObjectValue(val value: JSONObject) : AiJsonPayload
    data class ArrayValue(val value: JSONArray) : AiJsonPayload
}

class AiResponseFormatException(
    val rawText: String,
) : IllegalArgumentException(AiClient.FORMAT_ERROR_MESSAGE)
