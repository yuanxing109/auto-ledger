package com.eta.autoledger

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppPrefs private constructor(context: Context) {
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

    /** Android 16+ 实况通知（Live Updates）总开关。 */
    private var islandLiveUpdatesState by mutableStateOf(prefs.getBoolean("island_live_updates", true))
    var islandLiveUpdates: Boolean
        get() = islandLiveUpdatesState
        set(value) { islandLiveUpdatesState = value; prefs.edit().putBoolean("island_live_updates", value).apply() }

    /** 小米超级岛/焦点通知业务ID（需在小米开发者平台开通后获得，留空则不上小米岛）。 */
    private var miuiBusinessIdState by mutableStateOf(prefs.getString("miui_business_id", "") ?: "")
    var miuiBusinessId: String
        get() = miuiBusinessIdState
        set(value) { miuiBusinessIdState = value; prefs.edit().putString("miui_business_id", value).apply() }

    private var budgetAmountState by mutableStateOf(prefs.getString("budget_amount", "0.00") ?: "0.00")
    var budgetAmount: String
        get() = budgetAmountState
        set(value) { budgetAmountState = value; prefs.edit().putString("budget_amount", value).apply() }

    /**
     * 预算周期的起始日（1..28）。
     *
     * 发薪日往往不是 1 号：设成 25 表示「本月预算周期 = 25 号 ~ 次月 24 号」。
     * 上限 28 是刻意的——29/30/31 在 2 月不存在，会让周期边界漂移甚至跳过整月。
     */
    private var budgetStartDayState by mutableStateOf(prefs.getInt("budget_start_day", 1))
    var budgetStartDay: Int
        get() = budgetStartDayState
        set(value) {
            val clamped = value.coerceIn(1, 28)
            budgetStartDayState = clamped
            prefs.edit().putInt("budget_start_day", clamped).apply()
        }

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

    /** 主题：system / light / dark。默认跟随系统。 */
    private var themeState by mutableStateOf(prefs.getString("theme_mode", "system") ?: "system")
    var themeMode: String
        get() = themeState
        set(value) { themeState = value; prefs.edit().putString("theme_mode", value).apply() }

    // ---- WebDAV 备份（坚果云 / 群晖 / Nextcloud 通用） ----

    private var davUrlState by mutableStateOf(prefs.getString("dav_url", "") ?: "")
    /** 目录地址，例如 https://dav.jianguoyun.com/dav/auto-ledger。 */
    var davUrl: String
        get() = davUrlState
        set(value) { davUrlState = value; prefs.edit().putString("dav_url", value).apply() }

    private var davUserState by mutableStateOf(prefs.getString("dav_user", "") ?: "")
    var davUser: String
        get() = davUserState
        set(value) { davUserState = value; prefs.edit().putString("dav_user", value).apply() }

    private var davPassState by mutableStateOf(prefs.getString("dav_pass", "") ?: "")
    /** 坚果云要填**应用密码**，不是登录密码。 */
    var davPass: String
        get() = davPassState
        set(value) { davPassState = value; prefs.edit().putString("dav_pass", value).apply() }

    private var davAutoState by mutableStateOf(prefs.getBoolean("dav_auto", false))
    private var davAutoTimeState by mutableStateOf(prefs.getString("dav_auto_time", "03:00") ?: "03:00")
    /** 是否每天定时备份。 */
    var davAuto: Boolean
        get() = davAutoState
        set(value) { davAutoState = value; prefs.edit().putBoolean("dav_auto", value).apply() }

    /** 定时备份的时刻，HH:mm。 */
    var davAutoTime: String
        get() = davAutoTimeState
        set(value) { davAutoTimeState = value; prefs.edit().putString("dav_auto_time", value).apply() }

    private var davLastAtState by mutableStateOf(prefs.getLong("dav_last_at", 0L))
    private var davLastResultState by mutableStateOf(prefs.getString("dav_last_result", "") ?: "")
    var davLastAt: Long
        get() = davLastAtState
        set(value) { davLastAtState = value; prefs.edit().putLong("dav_last_at", value).apply() }

    var davLastResult: String
        get() = davLastResultState
        set(value) { davLastResultState = value; prefs.edit().putString("dav_last_result", value).apply() }

    fun davConfigured(): Boolean = davUrl.isNotBlank() && davUser.isNotBlank() && davPass.isNotBlank()

    // ── 解析拦截词库 ────────────────────────────────────────────
    // 用户自定义 + 默认预设合并后使用；存 SharedPreferences 的 StringSet。
    // 注意：默认值只作为「初始填充」，用户删掉某个词后不会再被自动加回来。

    private var ignoreKeywordsState by mutableStateOf(
        prefs.getStringSet(KEY_IGNORE_KEYWORDS, null)?.toSet() ?: DEFAULT_IGNORE_KEYWORDS,
    )

    /** 命中即丢弃，不生成账单。 */
    var ignoreKeywords: Set<String>
        get() = ignoreKeywordsState
        set(value) {
            ignoreKeywordsState = value
            prefs.edit().putStringSet(KEY_IGNORE_KEYWORDS, value).apply()
        }

    private var forceKeywordsState by mutableStateOf(
        prefs.getStringSet(KEY_FORCE_KEYWORDS, null)?.toSet() ?: DEFAULT_FORCE_KEYWORDS,
    )

    /** 命中则无视 [ignoreKeywords]，强制入账。 */
    var forceKeywords: Set<String>
        get() = forceKeywordsState
        set(value) {
            forceKeywordsState = value
            prefs.edit().putStringSet(KEY_FORCE_KEYWORDS, value).apply()
        }

    companion object {
        @Volatile
        private var instance: AppPrefs? = null

        /**
         * 全局单例。所有页面 / 服务必须共用同一份 [mutableStateOf] 快照，
         * 否则「外观」等设置写的是另一个实例，主题要等重启才生效。
         */
        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context.applicationContext).also { instance = it }
            }

        private const val KEY_IGNORE_KEYWORDS = "parse_ignore_keywords"
        private const val KEY_FORCE_KEYWORDS = "parse_force_keywords"

        /**
         * 默认拦截词。
         *
         * 「下单 / 待付款 / 先用后付 / 确认收货后 / 下月」来自抖音月付、京东白条这类
         * 信用支付的下单短信——钱还没真扣，只是占用了额度，记成支出会让当月支出虚高。
         * 「白条 / 花呗」是渠道名：单独一条「花呗」通知多半是账单提醒而非扣款。
         */
        val DEFAULT_IGNORE_KEYWORDS: Set<String> = linkedSetOf(
            "下单", "待付款", "先用后付", "确认收货后", "下月",
            "白条", "花呗", "未支付", "订单未消费",
        )

        /** 默认强制认账词：出现即说明钱已经动了。 */
        val DEFAULT_FORCE_KEYWORDS: Set<String> = linkedSetOf(
            "已支付", "支付成功", "已扣款", "退款",
        )

        const val DEFAULT_PROMPT =
            "你是记账助手。请从图片或文本中提取收支信息，只输出严格 JSON：" +
                "{\"amount\":0.00,\"type\":\"expense|income|other\",\"category\":\"餐饮\"," +
                "\"time\":\"yyyy-MM-dd HH:mm\",\"note\":\"\",\"confidence\":0.0}。不要输出多余文字。"
    }
}