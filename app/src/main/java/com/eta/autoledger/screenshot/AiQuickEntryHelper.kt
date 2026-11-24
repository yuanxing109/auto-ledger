package com.eta.autoledger.screenshot

import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.network.AiClient
import com.eta.autoledger.network.AiPrompt
import com.eta.autoledger.util.AiDateTimeResolver
import java.util.Locale

data class AiQuickEntry(
    val amount: Double,
    val type: String,
    val category: String,
    val note: String,
    val dateOffset: Int,
    val time: String,
    val timeMillis: Long,
)

/**
 * AI 自然语言记账的解析入口。
 *
 * 模型只负责把用户文本映射成严格 JSON，金额、类型、分类和日期仍在这里二次校验。
 * 纯 JVM 测试通过 [resolveWithModelCall] 注入模型输出，不需要访问网络。
 */
object AiQuickEntryHelper {

    const val MIN_DATE_OFFSET = -31
    const val MAX_DATE_OFFSET = 0

    /** 生产入口：调用文本补全能力并解析为可落账结果，格式异常交给 UI 统一降级。 */
    fun parse(
        prefs: AppPrefs,
        text: String,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): AiQuickEntry? {
        if (text.isBlank()) return null
        val prompt = buildPrompt(text, expenseCategories, incomeCategories)
        val response = try {
            AiClient.textCompletion(prefs, prompt, maxTokens = 320)
        } catch (error: Throwable) {
            Log.e("AI_ERROR", "AI 请求失败", error)
            throw error
        }
        return parseResponse(response, text, expenseCategories, incomeCategories, nowMillis)
            ?: AiClient.invalidResponse(response)
    }

    /** 测试入口：生产与测试共用同一 Prompt 和响应解析，只替换模型调用。 */
    internal fun resolveWithModelCall(
        text: String,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
        nowMillis: Long,
        modelCall: (prompt: String) -> String,
    ): AiQuickEntry? {
        if (text.isBlank()) return null
        val prompt = buildPrompt(text, expenseCategories, incomeCategories)
        val response = modelCall(prompt)
        return parseResponse(response, text, expenseCategories, incomeCategories, nowMillis)
            ?: AiClient.invalidResponse(response)
    }

    internal fun buildPrompt(
        text: String,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
    ): String {
        return AiPrompt.forText(text, expenseCategories, incomeCategories)
    }

    internal fun parseResponse(
        raw: String,
        originalText: String,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
        nowMillis: Long,
    ): AiQuickEntry? = runCatching {
        val obj = AiClient.parseJsonObject(raw) ?: return@runCatching null
        val amount = (obj.opt("amount") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return@runCatching null
        val type = field(obj, "type").takeIf { it == "expense" || it == "income" }
            ?: return@runCatching null
        val categories = if (type == "income") incomeCategories else expenseCategories
        val rawCategory = field(obj, "category")
        val category = rawCategory.takeIf { it in categories }
            ?: categories.firstOrNull { it == "其他" }
            ?: categories.firstOrNull()
            ?: "其他"
        val note = field(obj, "note").ifBlank { originalText.trim() }.take(120)
        val resolvedTime = AiDateTimeResolver.resolve(
            rawTime = field(obj, "time"),
            fallbackText = originalText,
            nowMillis = nowMillis,
        )
        val dateOffset = resolvedTime.dateOffset.coerceIn(MIN_DATE_OFFSET, MAX_DATE_OFFSET)
        AiQuickEntry(
            amount = amount,
            type = type,
            category = category,
            note = note,
            dateOffset = dateOffset,
            time = resolvedTime.display,
            timeMillis = resolvedTime.timeMillis,
        )
    }.getOrNull()

    private fun field(obj: org.json.JSONObject, key: String): String {
        val value = obj.optString(key, "").trim()
        if (value.isEmpty()) return ""
        return if (value.lowercase(Locale.ROOT) in NULL_FIELDS) "" else value
    }

    private val NULL_FIELDS = setOf("null", "nil", "undefined", "n/a", "none")
}
