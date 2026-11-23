package com.eta.autoledger.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置系统规则的播种器。
 *
 * 把「现有 [NotificationRules] 里最基础的那几条配置」翻译成 JSON 存进 [RuleEntity]，
 * 让数据库里有可读、可改、可导出的规则数据。
 *
 * 当前 [ParsePipeline] 会先跑用户规则，再跑这里的系统规则，全部未命中才回退
 * [NotificationRules] 的内置解析。因此关闭某条系统规则只表示「不再使用这份动态配置」，
 * 不会关闭内置兜底。
 *
 * 播种用 `INSERT OR IGNORE`（[RuleDao.insertIgnore]），不是 REPLACE。原因见那里的注释：
 * 用户可能关掉某条内置规则，REPLACE 会在每次升级时把用户的开关抹掉。
 */
object RuleSeed {

    /**
     * 规则 JSON 的版本号。
     *
     * 内核将来读 [RuleEntity.patternJson] 时先看这个字段：认得的版本按已知结构解释，
     * 不认得就跳过该条规则（而不是猜）。这样「新版本写入的新结构」在旧版本 App 上
     * 表现为「这条规则不生效」，而不是解析崩掉。
     */
    const val PATTERN_VERSION = 1

    /**
     * 第一批内置规则。
     *
     * 只挑最稳且可人工验收的四类：微信支付、支付宝、银行短信通用、向您支付进账。
     * 没有把 [NotificationRules] 的整张关键词表搬过来 —— 那 40 个屏蔽词、
     * 十几条正则整体搬进 JSON 会形成两份需要同步维护的真相。这里只放「最小可验证集」，
     * 覆盖四条主要路径，其余复杂模板仍交给内置解析兜底。
     */
    fun systemRules(now: Long = System.currentTimeMillis()): List<RuleEntity> = listOf(
        noticeRule(
            app = "com.tencent.mm",
            name = "微信支付通知",
            amount = listOf(
                """微信支付\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""",
                """[¥￥]\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
            ),
            expenseWords = listOf("支付成功", "付款成功", "已支付", "转账给", "二维码收款", "已付款"),
            incomeWords = listOf("收款到账", "收款成功", "已入账", "退款到账"),
            blockWords = listOf("优惠券", "红包封面", "领取红包", "验证码", "账单已出", "收益到账"),
            categories = mapOf(
                "消费" to listOf("支付成功", "付款成功", "已付款", "扫码", "商户"),
                "转账" to listOf("转账", "转给", "转至"),
                "退款" to listOf("退款", "退回"),
            ),
            now = now,
        ),
        noticeRule(
            app = "com.eg.android.AlipayGphone",
            name = "支付宝通知",
            amount = listOf(
                """(?:付款|支付|消费|收款)金额\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""",
                """[¥￥]\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
            ),
            expenseWords = listOf("支付成功", "付款成功", "已付款", "交易成功", "扫码付款"),
            incomeWords = listOf("收款到账", "收款成功", "退款到账", "已入账"),
            blockWords = listOf("优惠券", "代金券", "验证码", "积分", "账单已出", "余额宝收益"),
            categories = mapOf(
                "消费" to listOf("支付成功", "付款成功", "收银台", "当面付", "商户", "订单"),
                "转账" to listOf("转账", "转给", "转至"),
                "退款" to listOf("退款", "退货", "退回"),
            ),
            now = now,
        ),
        smsRule(
            app = null,
            name = "银行短信通用",
            amount = listOf(
                """(?:消费|支付|付款|支出|扣款|扣费|转账|转出|转入|收入|存入|入账|取现|还款|退款|缴费|缴纳|购买|充值|提现|收款|收到|交易)[^\d¥￥]{0,12}(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
                """(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?:元|圆)""",
            ),
            expenseWords = listOf("支付成功", "付款成功", "已付款", "消费", "支出", "扣款", "已扣", "交易成功", "缴费", "缴纳", "代扣", "购买", "取现", "还款"),
            incomeWords = listOf("收款", "到账", "收入", "入账", "转入", "已存入", "收到", "工资", "薪资", "薪酬", "返现", "补贴", "报销", "分红", "利息"),
            blockWords = listOf("验证码", "登录", "账单已出", "账单日", "还款日", "即将到期", "即将过期", "广告", "推广", "权益", "抽奖", "邀请"),
            categories = mapOf(
                "消费" to listOf("消费", "扣款", "收银台", "当面付", "扫码", "付款", "已支付", "支付", "商户", "订单"),
                "转账" to listOf("转账", "转出", "转入"),
                "退款" to listOf("退款", "退货", "退回"),
                "还款" to listOf("还款"),
                "提现" to listOf("提现"),
                "充值" to listOf("充值"),
            ),
            now = now,
        ),
        smsRule(
            app = null,
            name = "向您支付进账",
            // 「项目服务费19.40元」这类句式里，金额前面跟的是款项名而不是动词，
            // 通用银行规则那两条正则靠「支付…金额」的距离窗口抠不出来，这里单列一条。
            amount = listOf(
                """服务费\s*[¥￥]?\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
                """(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?:元|圆)""",
            ),
            expenseWords = listOf("支付", "服务费", "代扣", "申报"),
            incomeWords = listOf("向您支付", "向您转账", "向您汇款", "给您打款", "给您转账", "您收到"),
            blockWords = listOf("验证码", "登录", "账单已出", "账单日", "还款日", "即将到期", "广告", "推广"),
            categories = mapOf(
                // 方向已写死为 income，分类只需要在收入分类里挑一个语义最贴近的；
                // 「其他」在所有用户的收入分类里都存在，不会落到「消费」上。
                "其他" to listOf("服务费", "支付", "个人所得税", "项目"),
                "退款" to listOf("退款", "退回"),
                "转账" to listOf("转账", "转入"),
            ),
            direction = "income",
            now = now,
        ),
    )

    /**
     * 播种：把内置规则写进数据库。
     *
     * 幂等 —— 重复调用不会产生重复行，也不会覆盖用户对内置规则的开关。
     * 返回**实际新增**的条数（被 IGNORE 掉的不算），调用方可以据此打日志。
     *
     * 为什么返回 Int 而不是 Unit：首次启动与升级后都会调用，日志里能看到
     * 「播种了 3 条」还是「一条都没加」，一眼分辨是首次播种还是重复调用。
     */
    suspend fun seed(dao: RuleDao, now: Long = System.currentTimeMillis()): Int {
        val rules = systemRules(now)
        val inserted = dao.insertIgnore(rules)
        // IGNORE 冲突时 Room 返回 -1，统计非 -1 的即为真正插入的行
        return inserted.count { it != -1L }
    }

    /**
     * 拼一条通知规则的 JSON。
     *
     * 结构刻意与 [NotificationRules] 的字段一一对应（金额正则 / 收支词表 / 屏蔽词 / 分类表），
     * 将来内核切换时就是「把这些 List 从硬编码搬到 JSON 读出来」，不涉及语义改写。
     * 每个正则都写进 JSON 的 `patterns` 数组，顺序即优先级 —— 数组比单个字符串好扩展，
     * 因为真实短信里金额的位置五花八门，一条正则覆盖不完。
     */
    private fun noticeRule(
        app: String,
        name: String,
        amount: List<String>,
        expenseWords: List<String>,
        incomeWords: List<String>,
        blockWords: List<String>,
        categories: Map<String, List<String>>,
        now: Long,
    ): RuleEntity = RuleEntity(
        id = RuleEntity.makeId(RuleType.NOTICE, app, name),
        app = app,
        type = RuleType.NOTICE,
        name = name,
        creator = RuleEntity.CREATOR_SYSTEM,
        patternJson = buildJson(
            type = RuleType.NOTICE,
            app = app,
            amount = amount,
            expenseWords = expenseWords,
            incomeWords = incomeWords,
            blockWords = blockWords,
            categories = categories,
        ),
        enabled = true,
        autoRecord = true,
        updatedAt = now,
    )

    private fun smsRule(
        app: String?,
        name: String,
        amount: List<String>,
        expenseWords: List<String>,
        incomeWords: List<String>,
        blockWords: List<String>,
        categories: Map<String, List<String>>,
        /**
         * 写死方向（`income` / `expense` / `other`）。
         *
         * 只给「一句话里同时出现收支动词」的句式用：「XX公司向您支付了…服务费19.40元」里
         * 「支付」「服务费」「代扣」三个支出词压过零个收入词，词表投票永远判反。
         * 传 null 就退回词表投票，与老规则行为一致。
         */
        direction: String? = null,
        now: Long,
    ): RuleEntity = RuleEntity(
        id = RuleEntity.makeId(RuleType.SMS, app, name),
        app = app,
        type = RuleType.SMS,
        name = name,
        creator = RuleEntity.CREATOR_SYSTEM,
        patternJson = buildJson(
            type = RuleType.SMS,
            app = app,
            amount = amount,
            expenseWords = expenseWords,
            incomeWords = incomeWords,
            blockWords = blockWords,
            categories = categories,
            direction = direction,
        ),
        enabled = true,
        autoRecord = true,
        updatedAt = now,
    )

    /**
     * 规则 JSON 的公共结构。
     *
     * 字段命名用 snake_case 以外的短名（`amount` / `expense` / `block` / `cats`），
     * 因为这些 JSON 会被写进备份文件、被用户手工编辑，短名更省眼睛。
     */
    private fun buildJson(
        type: String,
        app: String?,
        amount: List<String>,
        expenseWords: List<String>,
        incomeWords: List<String>,
        blockWords: List<String>,
        categories: Map<String, List<String>>,
        direction: String? = null,
    ): String {
        val cats = JSONObject()
        categories.forEach { (cat, words) -> cats.put(cat, JSONArray(words)) }
        return JSONObject().apply {
            put("v", PATTERN_VERSION)
            put("type", type)
            put("app", app ?: JSONObject.NULL)
            put("amount", JSONArray(amount))
            put("expense", JSONArray(expenseWords))
            put("income", JSONArray(incomeWords))
            put("block", JSONArray(blockWords))
            put("cats", cats)
            // 只有写死方向的规则才带这个字段；不写就代表「按词表投票」，老版本 App 忽略它即可
            if (direction != null) put("direction", direction)
        }.toString()
    }
}
