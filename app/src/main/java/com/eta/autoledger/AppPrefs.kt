package com.eta.autoledger

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("auto_ledger", Context.MODE_PRIVATE)

    private var smsState by mutableStateOf(prefs.getBoolean("sms_enabled", true))
    var smsEnabled: Boolean
        get() = smsState
        set(value) { smsState = value; prefs.edit().putBoolean("sms_enabled", value).apply() }

    private var alipayState by mutableStateOf(prefs.getBoolean("alipay_enabled", true))
    var alipayEnabled: Boolean
        get() = alipayState
        set(value) { alipayState = value; prefs.edit().putBoolean("alipay_enabled", value).apply() }

    private var wechatState by mutableStateOf(prefs.getBoolean("wechat_enabled", true))
    var wechatEnabled: Boolean
        get() = wechatState
        set(value) { wechatState = value; prefs.edit().putBoolean("wechat_enabled", value).apply() }

    private var otherState by mutableStateOf(prefs.getBoolean("other_enabled", true))
    var otherEnabled: Boolean
        get() = otherState
        set(value) { otherState = value; prefs.edit().putBoolean("other_enabled", value).apply() }

    private var screenshotState by mutableStateOf(prefs.getBoolean("screenshot_enabled", false))
    var screenshotEnabled: Boolean
        get() = screenshotState
        set(value) { screenshotState = value; prefs.edit().putBoolean("screenshot_enabled", value).apply() }

    private var notifyAtState by mutableStateOf(prefs.getLong("last_notify_at", 0L))
    /** 通知通道最近一次成功入库的时间，0 表示从未采集到。 */
    var lastNotifyAt: Long
        get() = notifyAtState
        set(value) { notifyAtState = value; prefs.edit().putLong("last_notify_at", value).apply() }

    private var smsAtState by mutableStateOf(prefs.getLong("last_sms_at", 0L))
    /** 短信通道最近一次成功入库的时间，0 表示从未采集到。 */
    var lastSmsAt: Long
        get() = smsAtState
        set(value) { smsAtState = value; prefs.edit().putLong("last_sms_at", value).apply() }

    private var dailyReminderState by mutableStateOf(prefs.getBoolean("daily_reminder", false))
    var dailyReminder: Boolean
        get() = dailyReminderState
        set(value) { dailyReminderState = value; prefs.edit().putBoolean("daily_reminder", value).apply() }

    private var weeklyState by mutableStateOf(prefs.getBoolean("weekly_report", false))
    var weeklyReport: Boolean
        get() = weeklyState
        set(value) { weeklyState = value; prefs.edit().putBoolean("weekly_report", value).apply() }

    private var monthlyState by mutableStateOf(prefs.getBoolean("monthly_report", false))
    var monthlyReport: Boolean
        get() = monthlyState
        set(value) { monthlyState = value; prefs.edit().putBoolean("monthly_report", value).apply() }

    private var reminderTimeState by mutableStateOf(prefs.getString("reminder_time", "20:00") ?: "20:00")
    var reminderTime: String
        get() = reminderTimeState
        set(value) { reminderTimeState = value; prefs.edit().putString("reminder_time", value).apply() }

    private var islandDailyState by mutableStateOf(prefs.getBoolean("island_daily", true))
    var islandDaily: Boolean
        get() = islandDailyState
        set(value) { islandDailyState = value; prefs.edit().putBoolean("island_daily", value).apply() }

    private var calendarDailyState by mutableStateOf(prefs.getBoolean("calendar_daily", false))
    var calendarDaily: Boolean
        get() = calendarDailyState
        set(value) { calendarDailyState = value; prefs.edit().putBoolean("calendar_daily", value).apply() }

    private var assistantDailyState by mutableStateOf(prefs.getBoolean("assistant_daily", false))
    var assistantDaily: Boolean
        get() = assistantDailyState
        set(value) { assistantDailyState = value; prefs.edit().putBoolean("assistant_daily", value).apply() }

    private var dailyMutedState by mutableStateOf(prefs.getBoolean("daily_muted", false))
    var dailyMuted: Boolean
        get() = dailyMutedState
        set(value) { dailyMutedState = value; prefs.edit().putBoolean("daily_muted", value).apply() }

    private var islandSingleState by mutableStateOf(prefs.getBoolean("island_single", true))
    var islandSingle: Boolean
        get() = islandSingleState
        set(value) { islandSingleState = value; prefs.edit().putBoolean("island_single", value).apply() }

    private var persistentSingleState by mutableStateOf(prefs.getBoolean("persistent_single", false))
    var persistentSingle: Boolean
        get() = persistentSingleState
        set(value) { persistentSingleState = value; prefs.edit().putBoolean("persistent_single", value).apply() }

    private var singleMutedState by mutableStateOf(prefs.getBoolean("single_muted", false))
    var singleMuted: Boolean
        get() = singleMutedState
        set(value) { singleMutedState = value; prefs.edit().putBoolean("single_muted", value).apply() }

    private var budgetAmountState by mutableStateOf(prefs.getString("budget_amount", "0.00") ?: "0.00")
    var budgetAmount: String
        get() = budgetAmountState
        set(value) { budgetAmountState = value; prefs.edit().putString("budget_amount", value).apply() }

    private var budgetRepeatState by mutableStateOf(prefs.getString("budget_repeat", "每月") ?: "每月")
    var budgetRepeat: String
        get() = budgetRepeatState
        set(value) { budgetRepeatState = value; prefs.edit().putString("budget_repeat", value).apply() }

    private var budgetEndState by mutableStateOf(prefs.getString("budget_end", "永不结束") ?: "永不结束")
    var budgetEnd: String
        get() = budgetEndState
        set(value) { budgetEndState = value; prefs.edit().putString("budget_end", value).apply() }

    private var budgetReminderState by mutableStateOf(prefs.getInt("budget_reminder", 80))
    var budgetReminder: Int
        get() = budgetReminderState
        set(value) { budgetReminderState = value; prefs.edit().putInt("budget_reminder", value).apply() }

    private var homeBudgetState by mutableStateOf(prefs.getBoolean("home_budget_card", false))
    var homeBudgetCard: Boolean
        get() = homeBudgetState
        set(value) { homeBudgetState = value; prefs.edit().putBoolean("home_budget_card", value).apply() }

    private var aiBaseUrlState by mutableStateOf(prefs.getString("ai_base_url", "") ?: "")
    var aiBaseUrl: String
        get() = aiBaseUrlState
        set(value) { aiBaseUrlState = value; prefs.edit().putString("ai_base_url", value).apply() }

    private var aiKeyState by mutableStateOf(prefs.getString("ai_key", "") ?: "")
    var aiKey: String
        get() = aiKeyState
        set(value) { aiKeyState = value; prefs.edit().putString("ai_key", value).apply() }

    private var aiModelState by mutableStateOf(prefs.getString("ai_model", "") ?: "")
    var aiModel: String
        get() = aiModelState
        set(value) { aiModelState = value; prefs.edit().putString("ai_model", value).apply() }

    private var aiPromptState by mutableStateOf(prefs.getString("ai_prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT)
    var aiPrompt: String
        get() = aiPromptState
        set(value) { aiPromptState = value; prefs.edit().putString("ai_prompt", value).apply() }

    companion object {
        const val DEFAULT_PROMPT =
            "你是记账助手。请从图片或文本中提取收支信息，只输出严格 JSON：" +
                "{\"amount\":0.00,\"type\":\"expense|income|other\",\"category\":\"餐饮\"," +
                "\"time\":\"yyyy-MM-dd HH:mm\",\"note\":\"\",\"confidence\":0.0}。不要输出多余文字。"
    }
}