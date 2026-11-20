package com.eta.autoledger.data

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 通知来源渠道，决定用哪个开关过滤。 */
enum class NotifyChannel(val label: String) {
    ALIPAY("支付宝"),
    WECHAT("微信"),
    BANK("银行"),
    SMS("短信"),
    OTHER("其他"),
}

/** 一条待解析的通知原文。 */
data class NotifyRaw(
    val pkg: String,
    val title: String,
    val text: String,
    val bigText: String,
    val postTime: Long,
)

/** 解析结果，可直接落库。 */
data class NotifyBill(
    val amount: Double,
    val type: String,
    val category: String,
    val note: String,
    val time: String,
    val timeMillis: Long,
    val source: String,
    val externalId: String,
    val channel: NotifyChannel,
    val confidence: Float,
) {
    fun toEntry(id: Long): LedgerEntry = LedgerEntry(
        id = id,
        amount = amount,
        type = type,
        category = category,
        time = time,
        timeMillis = timeMillis,
        note = note,
        source = source,
        externalId = externalId,
    )
}

/**
 * 通知文本规则库：包名归类 -> 排除营销词 -> 提金额 -> 判收支 -> 猜分类 -> 生成去重号。
 *
 * 只做纯函数解析，不碰数据库，方便单测。分类名需要和 [CategoryStore] 里的定义对齐，
 * 由调用方把可用的分类列表传进来，匹配不到就回退到「其他」。
 */
object NotificationRules {

    private val ALIPAY_PKGS = setOf("com.eg.android.AlipayGphone")
    private val WECHAT_PKGS = setOf("com.tencent.mm")

    /** 银行 / 云闪付类 App。 */
    private val BANK_PKGS = setOf(
        "com.unionpay",
        "com.icbc",
        "com.ccb.longjiLife",
        "cmb.pb",
        "com.android.bankabc",
        "com.chinamworld.bocmbci",
        "com.bankcomm.Bankcomm",
        "com.yitong.mbank.psbc",
        "com.ecitic.bank.mobile",
        "cn.com.cmbc.newmbank",
        "com.cebbank.mobile.cemb",
        "com.pingan.paces.ccms",
    )

    /** 短信 App。 */
    private val SMS_PKGS = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.miui.smsextra",
    )

    /** 其他会发支付结果通知的 App：电商、外卖、出行、短视频。 */
    private val OTHER_PKGS = setOf(
        "com.sankuai.meituan",
        "com.sdu.didi.psnger",
        "com.ss.android.ugc.aweme",
        "com.smile.gifmaker",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo",
        "com.taobao.taobao",
        "com.taobao.idlefish",
        "ctrip.android.view",
        "com.MobileTicket",
    )

    /** 营销、提醒、验证码等噪声，命中直接丢弃。 */
    private val BLOCK_WORDS = listOf(
        "优惠券", "代金券", "红包封面", "领取红包", "活动", "满减", "积分", "签到", "会员日",
        "可用额度", "剩余额度", "账单已出", "账单日", "还款日", "即将到期", "即将过期",
        "最后提醒", "验证码", "登录", "系统更新", "广告", "推广", "权益", "抽奖",
        "邀请", "余额宝", "收益到账", "理财收益", "基金收益", "已折叠", "长按可",
        "瓜分", "红包雨", "助力", "开奖", "中奖", "免单", "抽签",
    )

    private val REFUND_WORDS = listOf("退款", "退回", "退货退款", "已退")
    private val REPAY_WORDS = listOf("信用卡还款", "还款成功", "已还款", "自动还款")
    private val WITHDRAW_WORDS = listOf("提现", "提现成功")
    private val RECHARGE_WORDS = listOf("充值", "充值成功")

    private val INCOME_WORDS = listOf(
        "收款", "到账", "收入", "入账", "转入", "已存入", "收到", "工资", "薪资",
        "薪酬", "返现", "补贴", "报销", "分红", "利息",
    )
    /** 命中即倾向收入，用于压制「微信支付收款」里「支付」的误判。 */
    private val STRONG_INCOME_WORDS = listOf(
        "收款成功", "收款到账", "收款", "到账", "已入账", "入账", "收入", "已存入",
    )
    private val EXPENSE_WORDS = listOf(
        "支付成功", "付款成功", "已付款", "付款", "支付", "消费", "支出", "扣款",
        "已扣", "交易成功", "转账给", "转给", "转账", "缴费", "缴纳", "代扣",
        "购买", "订单支付", "续费", "取现", "还款",
    )

    private val AMOUNT_YUAN = Regex(
        """(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?:元|圆)""",
    )
    private val AMOUNT_SIGN = Regex(
        """[¥￥]\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
    )
    private val AMOUNT_BARE = Regex("""^\s*(\d+(?:\.\d{1,2})?)\s*$""")

    /** 动词后 12 个字内的金额，避开「余额 1,234.56 元」「可用额度 20,000.00 元」这类尾随数字。 */
    private val AMOUNT_NEAR_VERB = Regex(
        """(?:消费|支付|付款|支出|扣款|扣费|转账|转出|转入|收入|存入|入账|取现|还款|退款|缴费|缴纳|购买|充值|提现|收款|收到|交易)[^\d¥￥]{0,12}(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
    )

    private val EXPENSE_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf(
            "美团外卖", "饿了么", "麦当劳", "肯德基", "星巴克", "瑞幸", "汉堡", "咖啡",
            "奶茶", "烧烤", "火锅", "麻辣烫", "拉面", "米线", "快餐", "早餐", "午餐",
            "晚餐", "餐厅", "饭店", "食堂", "外卖", "小吃", "食品", "面馆",
        ),
        "交通" to listOf(
            "滴滴", "高德打车", "打车", "出租车", "地铁", "公交", "加油", "中石油",
            "中石化", "壳牌", "停车", "高速", "ETC", "12306", "火车票", "机票",
            "共享单车", "哈啰",
        ),
        "购物" to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁", "超市", "便利店", "商场",
            "盒马", "沃尔玛", "永辉", "优衣库", "服饰", "百货", "小店",
        ),
        "娱乐" to listOf(
            "电影院", "影城", "游戏", "Steam", "腾讯视频", "爱奇艺", "优酷", "哔哩哔哩",
            "KTV", "演唱会", "门票", "会员", "网吧",
        ),
        "通讯" to listOf("话费", "流量", "中国移动", "中国联通", "中国电信", "宽带"),
        "美容" to listOf("理发", "美发", "美容", "美甲", "健身", "瑜伽"),
        "医疗" to listOf("医院", "药房", "药店", "诊所", "挂号", "体检", "医保", "口腔"),
        "住房" to listOf("房租", "物业", "水费", "电费", "燃气", "取暖", "房贷", "租金"),
        "旅行" to listOf("酒店", "民宿", "携程", "飞猪", "去哪儿", "景区", "旅游", "度假"),
        "红包" to listOf("红包"),
        "转账" to listOf("转账", "转给", "转至"),
        "消费" to listOf("消费"),
    )

    private val INCOME_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "退款" to listOf("退款", "退货", "退回"),
        "红包" to listOf("红包"),
        "薪资" to listOf("工资", "薪资", "薪酬", "代发"),
        "理财" to listOf("利息", "收益", "分红", "理财"),
        "转账" to listOf("转账", "转入"),
        "收债" to listOf("收款"),
        "借入" to listOf("借入", "借款到账"),
    )

    private val OTHER_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "还款" to listOf("还款"),
        "提现" to listOf("提现"),
        "充值" to listOf("充值"),
        "代付" to listOf("代付"),
    )

    fun channelOf(pkg: String): NotifyChannel? = when {
        pkg in ALIPAY_PKGS -> NotifyChannel.ALIPAY
        pkg in WECHAT_PKGS -> NotifyChannel.WECHAT
        pkg in SMS_PKGS -> NotifyChannel.SMS
        pkg in BANK_PKGS -> NotifyChannel.BANK
        pkg in OTHER_PKGS -> NotifyChannel.OTHER
        else -> null
    }

    fun sourceOf(channel: NotifyChannel): String = when (channel) {
        NotifyChannel.ALIPAY -> "支付宝通知"
        NotifyChannel.WECHAT -> "微信通知"
        NotifyChannel.BANK -> "银行通知"
        NotifyChannel.SMS -> "短信记账"
        NotifyChannel.OTHER -> "通知记账"
    }

    /** 同一条通知可能重复回调，先按原文指纹抑制。 */
    fun dedupeKey(raw: NotifyRaw): String =
        "${raw.pkg}|${raw.title}|${raw.text}|${raw.bigText}|${raw.postTime}"

    fun parse(
        raw: NotifyRaw,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
    ): NotifyBill? {
        val channel = channelOf(raw.pkg) ?: return null
        return parseAs(channel, raw, expenseCats, incomeCats, otherCats)
    }

    /**
     * 解析一条短信：[sender] 是发送方短号 / 服务号，[body] 是短信正文。
     *
     * 银行消费短信通常没有固定模板（「您尾号1234的卡11月20日12:30消费人民币100.00元」），
     * 走和通知同一条内核，只是噪声词换成短信专用表 —— 信用卡消费短信常带「可用额度」，
     * 通知表会把真实消费当营销提醒误杀。
     */
    fun parseSms(
        sender: String,
        body: String,
        postTime: Long,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
    ): NotifyBill? {
        val text = body.trim()
        if (text.isEmpty()) return null
        if (!plausibleSender(sender, text)) return null
        val raw = NotifyRaw(
            pkg = sender,
            title = sender,
            text = text,
            bigText = "",
            postTime = postTime,
        )
        return parseAs(NotifyChannel.SMS, raw, expenseCats, incomeCats, otherCats)
    }

    /**
     * 解析一条通知。解析不出金额或方向时返回 null，交给调用方跳过。
     */
    private fun parseAs(
        channel: NotifyChannel,
        raw: NotifyRaw,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
    ): NotifyBill? {
        val body = bodyOf(raw)
        if (body.isBlank()) return null
        if (noiseWordsOf(channel).any { body.contains(it) }) return null
        val amount = extractAmount(body) ?: return null
        if (amount <= 0.0 || amount > 1_000_000.0) return null
        val direction = directionOf(body) ?: return null
        val cats = when (direction.type) {
            "income" -> incomeCats
            "other" -> otherCats
            else -> expenseCats
        }
        if (cats.isEmpty()) return null
        val fallback = if (cats.contains(FALLBACK_CATEGORY)) FALLBACK_CATEGORY else cats.first()
        val category = pickCategory(body, direction, cats) ?: fallback
        val millis = raw.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        return NotifyBill(
            amount = amount,
            type = direction.type,
            category = category,
            note = noteOf(body),
            time = formatTime(millis),
            timeMillis = millis,
            source = sourceOf(channel),
            externalId = dedupeId(
                raw,
                amount,
                direction.type,
                if (channel == NotifyChannel.SMS) "sms" else "ntf",
            ),
            channel = channel,
            confidence = direction.confidence,
        )
    }

    private const val FALLBACK_CATEGORY = "其他"

    /**
     * 短信专属噪声词。比通知宽松的地方：不拦「可用额度 / 剩余额度」，
     * 因为真银行消费短信几乎都带这两个词；比通知严格的地方：拦验证码、营销、分期推销。
     */
    private val SMS_BLOCK_WORDS = listOf(
        "验证码", "动态码", "校验码", "随机码", "密码", "登录", "激活",
        "额度提升", "提额", "贷款", "借款", "申请", "办理", "分期", "免息",
        "利率", "最低还款", "账单已出", "账单日", "还款日", "即将到期", "即将过期",
        "逾期", "催缴", "点击", "链接", "退订", "回复T", "回复t", "回复R",
        "营销", "广告", "推广", "中奖", "抽奖", "积分", "话费", "流量", "套餐",
        "宽带", "理财", "基金", "保险", "推荐", "招聘", "报名", "问卷", "调查",
        "会员日", "优惠", "满减", "折扣", "红包封面", "活动", "权益", "新用户",
        "注册", "首绑", "首刷礼", "积分兑换", "商城", "请勿泄露", "谨防诈骗",
    )

    /** 号码形态像银行 / 服务平台短号，或正文本身就带金融关键词。 */
    private val FINANCE_SENDER = Regex("""^(?:\+?86)?(?:95\d{3,5}|\d{5,6}|1\d{10})$""")

    private val FINANCE_HINTS = listOf(
        "银行", "信用卡", "储蓄卡", "借记卡", "尾号", "账户", "账号", "人民币",
        "余额", "消费", "转账", "入账", "扣款", "交易", "取现", "还款",
    )

    /**
     * 106 / 125 这类通道号里混着大量营销与诈骗短信，只有在正文出现金融关键词时才放行；
     * 手机号与 95 开头的服务号直接放行，交给正文规则判断。
     */
    private fun plausibleSender(sender: String, body: String): Boolean {
        val s = sender.trim().replace(" ", "").replace("-", "")
        if (s.isEmpty()) return true
        val financeBody = FINANCE_HINTS.any { body.contains(it) }
        if (s.startsWith("106") || s.startsWith("125") || s.startsWith("10")) {
            return financeBody
        }
        return FINANCE_SENDER.matches(s) || financeBody
    }

    private fun noiseWordsOf(channel: NotifyChannel): List<String> =
        if (channel == NotifyChannel.SMS) SMS_BLOCK_WORDS else BLOCK_WORDS

    private data class Direction(val type: String, val confidence: Float)

    private fun bodyOf(raw: NotifyRaw): String {
        val title = raw.title.trim()
        val body = raw.bigText.trim().ifBlank { raw.text.trim() }
        val merged = if (body.isNotBlank() && title.isNotBlank() && !body.contains(title)) {
            "$title $body"
        } else {
            body.ifBlank { title }
        }
        return merged.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun extractAmount(text: String): Double? {
        // 交易金额通常紧跟动词（消费 / 支出 / 转账…），「余额 xxx 元」在后面
        AMOUNT_NEAR_VERB.find(text)?.let { return toAmount(it.groupValues[1]) }
        // 没有动词时取全文最靠前的金额：交易金额在句首，余额 / 可用额度在后
        val candidates = ArrayList<Pair<Int, String>>(4)
        AMOUNT_YUAN.findAll(text).forEach { candidates += it.range.first to it.groupValues[1] }
        AMOUNT_SIGN.findAll(text).forEach { candidates += it.range.first to it.groupValues[1] }
        if (text.length <= 12) {
            AMOUNT_BARE.findAll(text).forEach { candidates += it.range.first to it.groupValues[1] }
        }
        return candidates.minByOrNull { it.first }?.let { toAmount(it.second) }
    }

    private fun toAmount(raw: String): Double? =
        raw.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun directionOf(body: String): Direction? {
        if (REFUND_WORDS.any { body.contains(it) }) {
            return Direction("income", 0.9f)
        }
        if (REPAY_WORDS.any { body.contains(it) }) return Direction("other", 0.8f)
        if (WITHDRAW_WORDS.any { body.contains(it) }) return Direction("other", 0.7f)
        if (RECHARGE_WORDS.any { body.contains(it) }) return Direction("other", 0.7f)

        // 「微信支付收款」「收款到账」这类是收入，即使文本里也出现「支付」二字
        val strongIncome = STRONG_INCOME_WORDS.any { body.contains(it) }
        val paid = body.contains("支付成功") || body.contains("付款成功") || body.contains("已付款")
        if (strongIncome && !paid) return Direction("income", 0.75f)

        val incomeHits = INCOME_WORDS.count { body.contains(it) }
        val expenseHits = EXPENSE_WORDS.count { body.contains(it) }
        return when {
            incomeHits > expenseHits -> Direction("income", 0.7f)
            expenseHits > incomeHits -> Direction("expense", 0.8f)
            expenseHits > 0 && incomeHits > 0 -> {
                // 收支词同时出现时，「成功」+ 支付/付款 更可信是支出
                if (body.contains("成功") && (body.contains("支付") || body.contains("付款"))) {
                    Direction("expense", 0.6f)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun pickCategory(body: String, direction: Direction, cats: List<String>): String? {
        val table = when (direction.type) {
            "income" -> INCOME_KEYWORDS
            "other" -> OTHER_KEYWORDS
            else -> EXPENSE_KEYWORDS
        }
        val hit = table.firstOrNull { (name, words) ->
            cats.contains(name) && words.any { body.contains(it) }
        }
        if (hit != null) return hit.first
        return if (cats.contains(FALLBACK_CATEGORY)) FALLBACK_CATEGORY else null
    }

    private fun noteOf(body: String): String {
        val cleaned = body.trim()
        return if (cleaned.length <= 60) cleaned else cleaned.take(60) + "…"
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    private fun dedupeId(raw: NotifyRaw, amount: Double, type: String, prefix: String): String {
        val seed = "${dedupeKey(raw)}|$amount|$type"
        return prefix + md5(seed).take(20)
    }

    private fun md5(seed: String): String = try {
        MessageDigest.getInstance("MD5")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    } catch (t: Throwable) {
        seed.hashCode().toString()
    }
}