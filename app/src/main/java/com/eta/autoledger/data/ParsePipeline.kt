package com.eta.autoledger.data

import android.util.Log
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解析总入口：**动态规则优先，内置规则兜底**。
 *
 * 为什么是这个顺序而不是反过来：内置规则是编译期常量，覆盖的是「微信/支付宝/常见银行短信」
 * 这类高频模板；动态规则是用户在设置页按自己银行的措辞写的，命中率与准确率都更高。
 * 让用户规则先跑，才能在用户明确知道某家银行的措辞时压过内置的粗判。
 *
 * 为什么兜底不能去掉：`rule` 表是 v4 才建的，老版本升上来的库可能因为迁移异常、用户误删、
 * 或规则 JSON 写坏而一条都不可用。这时如果只走动态规则，自动记账会**整体静默失效** ——
 * 用户看到的是「以前能记，现在一条都不记了」，且没有任何报错。保留内置兜底是最低成本的保险。
 *
 * 这个 object 只做编排，不含任何正则与词表：
 * - 规则怎么编译、怎么匹配 → [RuleEngine]
 * - 正则安不安全 → [RuleSafety]
 * - 内置规则长什么样 → [NotificationRules]
 */
object ParsePipeline {

    private const val TAG = "AutoLedger"

    /** 动态规则产出的账单用这个前缀，与内置规则的 `ntf` / `sms` 区分开，便于排查。 */
    private const val PREFIX_RULE = "rul"

    /**
     * 解析一条通知。
     *
     * @param dao 用来加载规则；传 null 表示只走内置规则（测试与降级场景）
     */
    suspend fun parse(
        raw: NotifyRaw,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
        ignoreKeywords: Set<String> = emptySet(),
        forceKeywords: Set<String> = emptySet(),
        dao: RuleDao? = null,
    ): NotifyBill? {
        val channel = NotificationRules.channelOf(raw.pkg) ?: return null
        val body = NotificationRules.normalize(NotificationRules.bodyForParsing(raw))
        if (body.isNotBlank()) {
            val hit = matchDynamic(RuleType.NOTICE, raw.pkg, body, ignoreKeywords, forceKeywords, dao)
            if (hit != null) {
                return toBill(hit, channel, raw, body, expenseCats, incomeCats, otherCats)
            }
        }
        return NotificationRules.parse(
            raw = raw,
            expenseCats = expenseCats,
            incomeCats = incomeCats,
            otherCats = otherCats,
            ignoreKeywords = ignoreKeywords,
            forceKeywords = forceKeywords,
        )
    }

    /**
     * 解析一条短信。
     *
     * 发送方闸门（[NotificationRules.isPlausibleSender]）在**动态规则之前**就要过：
     * 内置的银行短信规则 `app` 为 null，代表「任意来源」，不拦的话 106 营销短信会直接
     * 命中金额正则。这个闸门只看发送方与正文金融词，与规则无关，所以对两条路都适用。
     */
    suspend fun parseSms(
        sender: String,
        body: String,
        postTime: Long,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
        ignoreKeywords: Set<String> = emptySet(),
        forceKeywords: Set<String> = emptySet(),
        dao: RuleDao? = null,
    ): NotifyBill? {
        val text = NotificationRules.normalize(body).trim()
        if (text.isEmpty()) return null
        if (!NotificationRules.isPlausibleSender(sender, text)) return null

        val hit = matchDynamic(RuleType.SMS, sender, text, ignoreKeywords, forceKeywords, dao)
        if (hit != null) {
            val raw = NotifyRaw(
                pkg = sender,
                title = sender,
                text = text,
                bigText = "",
                postTime = postTime,
            )
            return toBill(hit, NotifyChannel.SMS, raw, text, expenseCats, incomeCats, otherCats)
        }
        return NotificationRules.parseSms(
            sender = sender,
            body = body,
            postTime = postTime,
            expenseCats = expenseCats,
            incomeCats = incomeCats,
            otherCats = otherCats,
            ignoreKeywords = ignoreKeywords,
            forceKeywords = forceKeywords,
        )
    }

    /**
     * 动态规则匹配。任何异常都吞掉并返回 null —— 规则引擎出错不该让内置兜底也跑不到。
     */
    private suspend fun matchDynamic(
        type: String,
        app: String?,
        body: String,
        ignoreKeywords: Set<String>,
        forceKeywords: Set<String>,
        dao: RuleDao?,
    ): RuleHit? {
        if (dao == null) return null
        // 用户词库的「强制记账」优先级最高：它代表「这条一定是账，别管规则说什么」。
        // 反过来「忽略词」不能直接短路动态规则 —— 那是内置内核的语义，规则作者有自己的 block 表。
        val forced = forceKeywords.any { body.contains(it) }
        if (!forced && ignoreKeywords.any { body.contains(it) }) return null
        return runCatching {
            RuleEngine.ensureLoaded(dao)
            RuleEngine.match(type, app, body)
        }.getOrElse {
            Log.w(TAG, "动态规则匹配失败，回退内置规则", it)
            null
        }
    }

    /** 把规则命中结果翻译成可落库的 [NotifyBill]。分类要按当前分类表校验一遍。 */
    private fun toBill(
        hit: RuleHit,
        channel: NotifyChannel,
        raw: NotifyRaw,
        body: String,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
    ): NotifyBill? {
        if (hit.amount <= 0.0 || hit.amount > 1_000_000.0) return null
        val cats = when (hit.type) {
            "income" -> incomeCats
            "other" -> otherCats
            else -> expenseCats
        }
        if (cats.isEmpty()) return null
        // 规则里写的分类可能已经被用户在设置页删掉了，这时回退到「其他」或首个分类，
        // 而不是把这条账单丢掉 —— 金额与方向是对的，分类错一点远好过漏记。
        val category = hit.category?.takeIf { cats.contains(it) }
            ?: if (cats.contains(FALLBACK)) FALLBACK else cats.first()
        val millis = raw.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        return NotifyBill(
            amount = hit.amount,
            type = hit.type,
            category = category,
            note = noteOf(body),
            time = formatTime(millis),
            timeMillis = millis,
            source = NotificationRules.sourceOf(channel),
            externalId = externalId(hit, raw, channel),
            channel = channel,
            confidence = hit.confidence,
            autoRecord = hit.autoRecord,
        )
    }

    /**
     * 规则产出的去重号。
     *
     * 与内置规则用不同的前缀，并且把 `ruleId` 拼进种子：同一条通知如果先后被两条不同规则
     * 命中（用户刚改过规则），会得到两个 externalId，唯一索引挡不住 —— 这是刻意的，
     * 改规则后重放同一批通知本来就该按新规则重新判一次。同一规则对同一输入始终产出同一个值。
     */
    private fun externalId(hit: RuleHit, raw: NotifyRaw, channel: NotifyChannel): String {
        val seed = "${hit.ruleId}|${NotificationRules.dedupeKey(raw)}|${hit.amount}|${hit.type}"
        val digest = MessageDigest.getInstance("MD5")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PREFIX_RULE + (if (channel == NotifyChannel.SMS) "s" else "n") + digest.take(20)
    }

    /** 备注：与内置规则同样的策略 —— 超过 60 字截断，避免整段营销文塞进备注。 */
    private fun noteOf(body: String): String =
        if (body.length <= 60) body else body.take(60) + "…"

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    private const val FALLBACK = "其他"
}
