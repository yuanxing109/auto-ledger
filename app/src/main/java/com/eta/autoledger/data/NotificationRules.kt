package com.eta.autoledger.data

import java.text.Normalizer
import com.eta.autoledger.util.AppDateFormats

/** 通知来源渠道，决定用哪个开关过滤。 */
enum class NotifyChannel(val label: String) {
    ALIPAY("支付宝"),
    WECHAT("微信"),
    BANK("银行"),
    SMS("短信"),
    OTHER("其他"),
}

/**
 * 一条待解析的通知原文。
 *
 * [subText] 是第三级回落：少数 App（尤其部分银行的定制通知）把金额放在 `EXTRA_SUB_TEXT` 里，
 * 主文本只剩一句「交易提醒」。字段留空串而不是可空，是为了让三级回落写起来平铺直叙。
 */
data class NotifyRaw(
    val pkg: String,
    val title: String,
    val text: String,
    val bigText: String,
    val postTime: Long,
    val subText: String = "",
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
    /** 正文中识别到的银行卡/账户后四位，供跨源强指纹使用。 */
    val accountTail: String? = null,
    /** 动态规则或手工录入产生的子分类。 */
    val subCategory: String? = null,
    /**
     * 这条是否应当自动入库。
     *
     * 内置规则永远为 true；动态规则可以把它设成 false，表示「认得出来但只提示、不记账」——
     * 典型场景是用户想先观察某家银行的新模板一段时间，确认不会误判之后再打开。
     * 放在 [NotifyBill] 而不是让调用方再查一次规则，是因为解析结果才是唯一可信的上下文。
     */
    val autoRecord: Boolean = true,
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
        subCategory = subCategory,
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

    /**
     * 预告类（钱还没实际发生）信号：只有当「将来时标记紧邻扣费/付款动词」时才判定为预告，
     * 不能只看全文是否出现"将于"——否则「消费100元，将于明日发送账单」这类真交易会被误杀。
     * 命中且无完成标记则丢弃，等真实扣费通知到了再记，避免「提醒记一笔 + 实扣再记一笔」。
     */
    private val FUTURE_CHARGE_PATTERNS = listOf(
        // 将(自动)扣费/付款/续费…
        Regex("""将[^。；！？\n]{0,12}(?:扣费|扣款|付款|支付|消费|续费|划扣|收取)"""),
        // 明天/明日/即将/预计…（可跨越逗号）扣费/付款…（不含易歧义的"消费"）
        Regex("""(?:明天|明日|次日|即将|预计|稍后)[^。；！？\n]{0,24}(?:扣费|扣款|付款|支付|续费|划扣)"""),
        // 倒装：扣费将于明日…（动词后紧跟将来时，不允许跨过金额数字，避免误伤"消费100元，将于…"）
        Regex("""(?:扣费|扣款|付款|支付|续费)[^。；！？\d\n]{0,4}(?:将于|即将|明日|明天)"""),
        // 明确未发生状态
        Regex("""(?:待扣|待支付|待付款|待缴|未扣款|未支付|尚未支付|未扣费)"""),
    )
    /** 完成标记：有它说明交易已实际完成，预告检查放行。 */
    private val COMPLETED_WORDS = listOf(
        "已扣", "扣费成功", "扣款成功", "扣费完成", "扣款完成", "已扣款",
        "交易成功", "支付成功", "已支付", "已消费", "付款成功", "已付款",
    )

    /**
     * 【营销 / 待消费 / 团购券】强拦截信号。
     *
     * 真实误判样本：
     *   「你有订单未消费 你购买的【新会员】5小时包时(15.8…还未消费，可前往电竞蜂网咖…」
     * 这条通知里「消费」「购买」都是被否定的状态描述（"未消费"），钱根本没花出去；
     * 但：
     *   1. directionOf 看到「消费」「购买」→ 判成 expense；
     *   2. extractAmount 的 AMOUNT_NEAR_VERB 把「购买」后 12 字内的「5小时」的 5 抓成金额，
     *   于是生成一条 5.00 元的假支出并自动记账。
     *
     * 规则：命中任一「未消费类」信号，**且**全文没有任何「已扣款」证据时，直接 return null。
     * 后半句是必需的——否则会把「已支付 99 元，订单未消费可退款」这类真实扣款通知也误杀。
     */
    private val NOT_CONSUMED_PATTERNS = listOf(
        "未消费", "待消费", "待使用", "尚未使用", "未使用", "尚未核销", "待核销", "未核销",
        "订单未消费", "还未消费", "尚未消费",
    )
    /** 明确的「钱已实际扣走」证据：有它才允许在含「未消费」类词时继续记账。 */
    private val PAID_PROOF_WORDS = listOf(
        "已支付", "支付成功", "付款成功", "已付款", "已扣款", "扣款成功", "扣费成功",
        "已扣费", "已消费", "交易成功", "支出",
    )

    /** 仅表示订单已创建，金额和方向并不代表资金已经实际划转。 */
    private val PENDING_ORDER_WORDS = listOf(
        "下单成功", "待支付", "待付款", "订单已生成", "订单生成成功", "等待支付",
    )

    /** 足以把 pending 转换成正式账单的完成证据。 */
    private val SETTLED_PAYMENT_WORDS = listOf(
        "已支付", "支付成功", "支付完成", "付款成功", "已付款","已扣款",
        "扣款成功", "扣款完成", "扣费成功", "交易成功",
    )

    /** 【】内的商户 / 标题名。正常 2~16 字；超过 24 字基本是营销长文，不能当商户名。 */
    private val MERCHANT_BRACKET = Regex("""【([^】]{1,64})】""")

    private val REFUND_WORDS = listOf("退款", "退回", "退货退款", "已退")
    private val REPAY_WORDS = listOf("信用卡还款", "还款成功", "已还款", "自动还款")
    private val WITHDRAW_WORDS = listOf("提现", "提现成功")
    private val RECHARGE_WORDS = listOf("充值", "充值成功")

    /**
     * 「钱进到您账户」的强指向短语。
     *
     * 为什么要单列一张表、且判定顺序排在所有词表之前：
     * 「XX公司向您支付了…项目服务费19.40元」里同时出现「支付」「服务费」「代扣」三个支出词，
     * 按词数投票必然判成支出 —— 但「向您支付」的施动者是对方、受益者是您，钱是进来的。
     * 这类短语的指向性远强于单个动词，命中即定方向，不再参与投票。
     */
    private val INWARD_WORDS = listOf(
        "向您支付", "向您转账", "向您汇款", "给您打款", "给您转账",
        "汇入您", "您收到", "已汇入", "已转入您",
    )

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
        "购买", "订单支付", "续费", "取现", "还款", "下单", "待支付", "待付款", "订单生成",
    )

    private val AMOUNT_YUAN = Regex(
        MoneyPatterns.WITH_YUAN,
    )
    private val AMOUNT_SIGN = Regex(
        MoneyPatterns.WITH_SYMBOL,
    )
    private val AMOUNT_BARE = Regex("""^\s*(\d+(?:\.\d{1,2})?)\s*$""")

    /** 动词后 12 个字内的金额，避开「余额 1,234.56 元」「可用额度 20,000.00 元」这类尾随数字。 */
    private val AMOUNT_NEAR_VERB = Regex(
        MoneyPatterns.NEAR_VERB,
    )

    /**
     * 文本归一化：NFKC 兼容分解。
     *
     * 为什么必须有这一步：银行短信里的金额常写成全角 `１，０００．００元`，
     * 而 [AMOUNT_YUAN] 认的是半角 `\d{1,3}(?:,\d{3})+` —— 全角数字和全角逗号
     * 一个都匹配不上，整条真实扣款短信被当成「没有金额」丢掉。
     *
     * NFKC 一次性解决四类差异：全角数字 → 半角、全角逗号 `，` → `,`、
     * 全角句点 `．` → `.`、全角 `￥` → `¥`；顺带把 `①` 这类兼容字符摊平。
     * 对中文正文与关键词匹配无副作用（汉字不在 NFKC 的映射范围内）。
     *
     * **必须在算指纹之前调用**：指纹是对文本做哈希的，若一条走归一化、一条不走，
     * 同一笔钱会得到两个 externalId，去重直接失效。所以统一在「取到文本的第一现场」
     * （[com.eta.autoledger.service.SmsReceiver] 拿到 body、通知监听拼 [NotifyRaw]）
     * 就归一化，后续所有环节看到的都是同一份文本。
     */
    fun normalize(text: String): String =
        if (text.isEmpty()) text
        else runCatching { Normalizer.normalize(text, Normalizer.Form.NFKC) }.getOrDefault(text)

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

    /**
     * 同一条通知可能重复回调，先按原文指纹抑制。
     *
     * 时间取 `postTime / 1000`（秒级）：同一毫秒内重复投递只是理想情况，
     * 实际观察到的重复回调会差几毫秒到几百毫秒，毫秒级指纹会双双失配 → 同一条通知入两笔。
     * 秒级 + [ParseDedup] 的 2 秒促发窗口，两层兜住跨秒边界的重复。
     */
    fun dedupeKey(raw: NotifyRaw): String {
        // 各字段先归一化再拼：同一笔钱若一次以全角、一次以半角文本到达，
        // 不归一化就会算出两个 externalId，数据库唯一索引与内存去重全部失效。
        val title = normalize(raw.title)
        val text = normalize(raw.text)
        val bigText = normalize(raw.bigText)
        val subText = normalize(raw.subText)
        return "${raw.pkg}|$title|$text|$bigText|$subText|${raw.postTime / 1000L}"
    }

    /**
     * 把一条通知/短信的各个文本字段拼成用于解析的正文。
     *
     * 只开这一个公开口子给 [ParsePipeline]：动态规则与内置规则必须**吃同一份正文**，
     * 否则同一条通知在两条路上会拼出不同的字符串（字段顺序、空白压缩、标题合并规则不同），
     * 出现「动态规则抠不到金额但内置能抠到」这类极难排查的差异。
     */
    fun bodyForParsing(raw: NotifyRaw): String = bodyOf(raw)

    /**
     * 发送方是否值得解析。动态规则里 `app` 为 null 的通用短信规则会匹配任意来源，
     * 不先过这道闸，106 营销短信会直接命中金额正则。
     */
    fun isPlausibleSender(sender: String, body: String): Boolean = plausibleSender(sender, body)

    /** 只有下单/待支付信号、且没有实际完成证据时，才进入 pending。 */
    fun isPendingOrder(text: String): Boolean {
        val body = normalize(text)
        return PENDING_ORDER_WORDS.any(body::contains) && !hasPaymentProof(body)
    }

    /** 出现已支付、支付成功、扣款成功等信号，代表订单已完成。 */
    fun hasPaymentProof(text: String): Boolean {
        val body = normalize(text)
        return SETTLED_PAYMENT_WORDS.any(body::contains)
    }

    fun parse(
        raw: NotifyRaw,
        expenseCats: List<String>,
        incomeCats: List<String>,
        otherCats: List<String>,
        ignoreKeywords: Set<String> = emptySet(),
        forceKeywords: Set<String> = emptySet(),
        allowPending: Boolean = false,
    ): NotifyBill? {
        val channel = channelOf(raw.pkg) ?: return null
        return parseAs(
            channel, raw, expenseCats, incomeCats, otherCats,
            ignoreKeywords = ignoreKeywords,
            forceKeywords = forceKeywords,
            allowPending = allowPending,
        )
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
        ignoreKeywords: Set<String> = emptySet(),
        forceKeywords: Set<String> = emptySet(),
        allowPending: Boolean = false,
    ): NotifyBill? {
        // 防御性再归一化一次：本函数是公开 API，任何调用方漏了归一化都不该导致漏记。
        // NFKC 是幂等的，重复调用没有副作用。
        val text = normalize(body).trim()
        if (text.isEmpty()) return null
        if (!plausibleSender(sender, text)) return null
        val raw = NotifyRaw(
            pkg = sender,
            title = sender,
            text = text,
            bigText = "",
            postTime = postTime,
        )
        return parseAs(
            NotifyChannel.SMS, raw, expenseCats, incomeCats, otherCats,
            ignoreKeywords = ignoreKeywords,
            forceKeywords = forceKeywords,
            allowPending = allowPending,
        )
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
        ignoreKeywords: Set<String> = emptySet(),
        forceKeywords: Set<String> = emptySet(),
        allowPending: Boolean = false,
    ): NotifyBill? {
        // 归一化后再做一切判定与指纹：全角数字/逗号在这里被摊平成半角，
        // 否则 AMOUNT_YUAN 直接失配（详见 [normalize] 的注释）。
        val body = normalize(bodyOf(raw))
        if (body.isBlank()) return null
        if (noiseWordsOf(channel).any { body.contains(it) }) return null
        // ── 用户词库优先判定 ────────────────────────────────────
        // 顺序刻意如此：认账词在拦截词之前。信用支付短信常同时出现「下单」与「已支付」，
        // 只有「已支付」才代表钱真的出去了，拦截词此时必须让位，否则真实扣款被吞。
        val forced = forceKeywords.any { body.contains(it) }
        if (!forced && ignoreKeywords.any { body.contains(it) }) return null
        // 营销 / 待消费 / 团购券强拦截：见 NOT_CONSUMED_PATTERNS 的注释。
        // 「未消费」类词 + 无任何「已扣款」证据 → 直接丢弃，绝不生成账单。
        if (!forced && NOT_CONSUMED_PATTERNS.any { body.contains(it) } &&
            PAID_PROOF_WORDS.none { body.contains(it) } &&
            forceKeywords.none { body.contains(it) }
        ) return null
        // 预告类提醒（"明天将自动付款18.8"）：钱还没实际发生，等真实扣费通知到了再记。
        // 用「将来时紧邻扣费动词」的正则判定，避免「将于明日发送账单」这类真交易被误杀。
        if (!allowPending && !forced &&
            COMPLETED_WORDS.none(body::contains) &&
            FUTURE_CHARGE_PATTERNS.any { it.containsMatchIn(body) }
        ) return null
        val amount = extractAmount(body) ?: return null
        if (amount <= 0.0 || amount > 1_000_000.0) return null
        val direction = directionOf(body) ?: return null
        val cats = when (direction.type) {
            "income" -> incomeCats
            "other" -> otherCats
            else -> expenseCats
        }.ifEmpty { listOf(FALLBACK_CATEGORY) }
        val inferred = CategoryInference.infer(body, cats)
        val category = inferred.category.takeIf(cats::contains)
            ?: if (cats.contains(FALLBACK_CATEGORY)) FALLBACK_CATEGORY else cats.first()
        val millis = raw.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        return NotifyBill(
            amount = amount,
            type = direction.type,
            category = category,
            subCategory = inferred.subCategory.takeIf { inferred.category == category },
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
            accountTail = ParseDedup.accountTailFromText(body),
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
        // 「向您支付」本身就是强金融语义，缺了它，106 通道发来的进账通知会被发送方闸门挡掉
        "向您支付", "向您转账", "给您打款", "服务费",
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

    /**
     * 拼出用于解析的正文：`bigText` → `text` → `subText` 三级回落，再并上标题。
     *
     * 三级而不是两级：`EXTRA_BIG_TEXT` 与 `EXTRA_TEXT` 都空、金额只放在 `EXTRA_SUB_TEXT`
     * 的通知是真实存在的（部分银行的定制模板），少这一档就整条漏记。
     */
    private fun bodyOf(raw: NotifyRaw): String {
        val title = raw.title.trim()
        val body = raw.bigText.trim()
            .ifBlank { raw.text.trim() }
            .ifBlank { raw.subText.trim() }
        val merged = if (body.isNotBlank() && title.isNotBlank() && !body.contains(title)) {
            "$title $body"
        } else {
            body.ifBlank { title }
        }
        // 保留换行，供 DOTALL 动态规则跨行匹配；只压平每行内的横线与重复空格。
        return merged.lines()
            .map { line -> line.replace(Regex("[\\t\\r\\u000B\\f ]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
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
        // 进账短语必须排在最前面：这类短信同时带「支付」「服务费」「代扣」，
        // 一旦进入下面的词数投票，支出词必然多过收入词，方向就反了。
        if (INWARD_WORDS.any { body.contains(it) }) return Direction("income", 0.95f)
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

    private fun noteOf(body: String): String {
        var cleaned = body.trim()
        // 【】块若超过 24 字，几乎一定是营销长文而不是商户名，剥掉它避免整段广告塞进备注。
        // 正常的短商户名（如【招商银行】【美团】）不受影响。
        MERCHANT_BRACKET.findAll(cleaned).forEach { m ->
            if (m.groupValues[1].length > 24) cleaned = cleaned.replace(m.value, "")
        }
        cleaned = cleaned.trim()
        return if (cleaned.length <= 60) cleaned else cleaned.take(60) + "…"
    }

    private fun formatTime(millis: Long): String =
        AppDateFormats.format(millis, AppDateFormats.dateTime)

    private fun dedupeId(raw: NotifyRaw, amount: Double, type: String, prefix: String): String {
        val seed = "${dedupeKey(raw)}|${FingerprintCodec.amountFenToken(amount)}|$type"
        return prefix + md5(seed).take(20)
    }

    private fun md5(seed: String): String = try {
        FingerprintCodec.md5Hex(seed)
    } catch (t: Throwable) {
        seed.hashCode().toString()
    }
}
