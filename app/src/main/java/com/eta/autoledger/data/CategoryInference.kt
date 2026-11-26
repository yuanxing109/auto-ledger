package com.eta.autoledger.data

import java.text.Normalizer
import java.util.Locale

/** 分类命中来源，便于日志、测试与后续规则调优区分。 */
enum class MatchSource {
    MERCHANT,
    KEYWORD,
    FALLBACK,
}

/** 关键词规则的匹配方式。 */
enum class MatchType {
    EXACT_MERCHANT,
    REGEX_WORD,
    CONTAINS,
}

data class CategoryMatchResult(
    val category: String,
    val subCategory: String?,
    val source: MatchSource,
    val weight: Int,
)

data class KeywordRule(
    val keywords: List<String>,
    val category: String,
    val subCategory: String?,
    val weight: Int,
    val excludeWords: List<String>,
    val matchType: MatchType,
)

/**
 * 分类与子分类推断的唯一内核。
 *
 * 执行顺序固定为：文本归一化 → 精准商户 → 关键词规则 → 唯一最高权重裁决。
 * 子分类只从 [CategoryStore] 的当前分类树取值，调用方还会按用户已启用的一级分类过滤。
 */
object CategoryInference {

    private const val MERCHANT_WEIGHT = 100
    private const val STRONG_KEYWORD_WEIGHT = 80
    private const val WEAK_KEYWORD_WEIGHT = 40
    private const val FALLBACK_WEIGHT = 10
    private const val FALLBACK_CATEGORY = "其他"

    private data class MerchantTarget(
        val category: String,
        val subCategory: String?,
        val excludeWords: Set<String> = emptySet(),
    )

    private data class CompiledKeywordRule(
        val category: String,
        val subCategory: String?,
        val weight: Int,
        val keywords: List<String>,
        val excludeWords: List<String>,
        val matchType: MatchType,
        val regexes: List<Regex>,
    )

    private val whitespace = Regex("\\s+")

    /**
     * 商户名到分类的精确语义映射。
     *
     * 超级平台必须先细化业务线，并按最长商户名优先命中；泛化平台键只作为严格词边界
     * 下的兜底，避免外卖、买药、酒店被同一个商户名吞掉。
     */
    private val merchantMap: Map<String, MerchantTarget> = linkedMapOf(
        normalized("美团外卖") to MerchantTarget("餐饮", child("餐饮", "外卖", "早中晚餐")),
        normalized("饿了么") to MerchantTarget("餐饮", child("餐饮", "外卖")),
        normalized("饿了么外卖") to MerchantTarget("餐饮", child("餐饮", "外卖", "早中晚餐")),
        normalized("星巴克") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("瑞幸") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("喜茶") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("奈雪") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("蜜雪冰城") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("茶百道") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("古茗") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("沪上阿姨") to MerchantTarget("餐饮", child("餐饮", "奶茶咖啡")),
        normalized("麦当劳") to MerchantTarget("餐饮", child("餐饮", "早中晚餐")),
        normalized("肯德基") to MerchantTarget("餐饮", child("餐饮", "早中晚餐")),
        normalized("汉堡王") to MerchantTarget("餐饮", child("餐饮", "早中晚餐")),
        normalized("必胜客") to MerchantTarget("餐饮", child("餐饮", "早中晚餐")),
        normalized("德克士") to MerchantTarget("餐饮", child("餐饮", "早中晚餐")),

        normalized("美团打车") to MerchantTarget("交通", child("交通", "打车租车")),
        normalized("美团单车") to MerchantTarget("交通", child("交通", "打车租车")),
        normalized("滴滴") to MerchantTarget("交通", child("交通", "打车租车")),
        normalized("滴滴出行") to MerchantTarget("交通", child("交通", "打车租车")),
        normalized("高德打车") to MerchantTarget("交通", child("交通", "打车租车")),
        normalized("12306") to MerchantTarget("交通", child("交通", "火车飞机")),
        normalized("中石油") to MerchantTarget("交通", child("交通", "加油停车")),
        normalized("中石化") to MerchantTarget("交通", child("交通", "加油停车")),
        normalized("壳牌") to MerchantTarget("交通", child("交通", "加油停车")),

        normalized("美团买药") to MerchantTarget("医疗", child("医疗", "药店买药")),
        normalized("京东买药") to MerchantTarget("医疗", child("医疗", "药店买药")),
        normalized("饿了么买药") to MerchantTarget("医疗", child("医疗", "药店买药")),
        normalized("大药房") to MerchantTarget("医疗", child("医疗", "药店买药")),

        normalized("美团酒店") to MerchantTarget("旅行", child("旅行", "酒店住宿")),
        normalized("美团民宿") to MerchantTarget("旅行", child("旅行", "酒店住宿")),
        normalized("携程") to MerchantTarget("旅行", child("旅行", "酒店住宿")),
        normalized("飞猪") to MerchantTarget("旅行", child("旅行", "酒店住宿")),
        normalized("京东机票") to MerchantTarget("旅行", child("旅行", "火车飞机", "旅行交通")),

        normalized("美团优选") to MerchantTarget("购物", child("购物", "超市便利")),
        normalized("京东超市") to MerchantTarget("购物", child("购物", "超市便利")),
        normalized("京东支付") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("京东商城") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("京东订单") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("淘宝") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("天猫") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("京东") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("拼多多") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("唯品会") to MerchantTarget("购物", child("购物", "服饰鞋包")),
        normalized("苏宁") to MerchantTarget("购物", child("购物", "数码电器")),
        normalized("小红书") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("得物") to MerchantTarget("购物", child("购物", "服饰鞋包")),
        normalized("抖音") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("网易严选") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("沃尔玛") to MerchantTarget("购物", child("购物", "超市便利")),
        normalized("永辉") to MerchantTarget("购物", child("购物", "超市便利")),
        normalized("无印良品") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("优衣库") to MerchantTarget("购物", child("购物", "服饰鞋包")),
        normalized("屈臣氏") to MerchantTarget("购物", child("购物", "美妆个护")),
        normalized("名创优品") to MerchantTarget("购物", child("购物", "日用品")),
        normalized("盒马鲜生") to MerchantTarget(
            "购物",
            child("购物", "超市便利"),
            excludeWords = setOf("草莓", "果切", "水果", "零食"),
        ),

        normalized("腾讯视频") to MerchantTarget("娱乐", child("娱乐", "视频会员")),
        normalized("网易云") to MerchantTarget("娱乐", child("娱乐", "视频会员")),
        normalized("爱奇艺") to MerchantTarget("娱乐", child("娱乐", "视频会员")),
        normalized("优酷") to MerchantTarget("娱乐", child("娱乐", "视频会员")),
        normalized("哔哩哔哩") to MerchantTarget("娱乐", child("娱乐", "视频会员")),
        normalized("steam") to MerchantTarget("娱乐", child("娱乐", "电子游戏")),
        normalized("中国移动") to MerchantTarget("通讯", child("通讯", "话费流量")),
        normalized("中国联通") to MerchantTarget("通讯", child("通讯", "话费流量")),
        normalized("中国电信") to MerchantTarget("通讯", child("通讯", "话费流量")),

        normalized("微信转账") to MerchantTarget(
            "转账",
            null,
            excludeWords = setOf("随礼", "礼金", "份子钱", "收红包"),
        ),
    )

    /** 短品牌名与数字商户必须按独立词匹配，避免“北京东…”“卡尾号12306…”误命中。 */
    private val boundaryMerchantRegexes: Map<String, Regex> = listOf(
        "京东", "淘宝", "天猫", "拼多多", "苏宁", "得物", "滴滴", "12306",
    ).map(::normalized).associateWith(::wordRegex)

    /** 关键词词库只描述业务语义，子分类名称一律由 [child] 从 CategoryStore 校验。 */
    private val keywordRules: List<KeywordRule> = listOf(
        KeywordRule(
            keywords = listOf(
                "早餐", "午餐", "晚餐", "餐厅", "饭店", "食堂", "快餐", "快餐店",
                "吃饭", "小吃", "火锅", "烧烤", "麻辣烫", "拉面", "米线", "汉堡",
                "烘焙", "蛋糕", "面馆", "美食", "兰州拉面", "黄焖鸡", "沙县", "老乡鸡",
            ),
            category = "餐饮",
            subCategory = child("餐饮", "早中晚餐"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("外卖"),
            category = "餐饮",
            subCategory = child("餐饮", "外卖"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("水果", "草莓", "果切", "零食"),
            category = "餐饮",
            subCategory = child("餐饮", "水果零食"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("咖啡", "奶茶"),
            category = "餐饮",
            subCategory = child("餐饮", "奶茶咖啡"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("日用品", "卫生纸", "纸巾", "洗发水", "洗衣液"),
            category = "购物",
            subCategory = child("购物", "日用品"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("超市", "便利店"),
            category = "购物",
            subCategory = child("购物", "超市便利"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("打车", "出租车", "网约车"),
            category = "交通",
            subCategory = child("交通", "打车租车"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("火车票", "高铁", "动车", "机票", "航班"),
            category = "交通",
            subCategory = child("交通", "火车飞机"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("公交", "地铁"),
            category = "交通",
            subCategory = child("交通", "公交地铁"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("加油", "停车", "高速通行费", "etc"),
            category = "交通",
            subCategory = child("交通", "加油停车"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("共享单车", "哈啰"),
            category = "交通",
            subCategory = child("交通", "打车租车"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("交通费", "交通卡", "交通出行", "交通支出", "交通消费"),
            category = "交通",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = listOf("交通银行", "交通大厦"),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("交通"),
            category = "交通",
            subCategory = null,
            weight = WEAK_KEYWORD_WEIGHT,
            excludeWords = listOf("大厦", "银行", "快餐", "快餐店"),
            matchType = MatchType.REGEX_WORD,
        ),
        KeywordRule(
            keywords = listOf("药店", "药房", "买药", "大药房"),
            category = "医疗",
            subCategory = child("医疗", "药店买药"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("医院", "挂号", "门诊", "住院", "体检"),
            category = "医疗",
            subCategory = child("医疗", "门诊住院"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("酒店", "民宿", "住宿"),
            category = "旅行",
            subCategory = child("旅行", "酒店住宿"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("旅游", "景区", "度假"),
            category = "旅行",
            subCategory = child("旅行", "景点门票"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = listOf("机票", "航班", "火车"),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("电影院", "影城", "ktv", "演唱会", "网吧"),
            category = "娱乐",
            subCategory = child("娱乐", "文娱演出"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("游戏", "游戏充值"),
            category = "娱乐",
            subCategory = child("娱乐", "电子游戏"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("会员充值", "视频会员", "视频会员充值"),
            category = "娱乐",
            subCategory = child("娱乐", "视频会员"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("培训", "学费"),
            category = "教育",
            subCategory = child("教育", "培训考试"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("书店", "教材", "书报", "杂志"),
            category = "教育",
            subCategory = child("教育", "书报杂志"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("随礼", "礼金", "份子钱"),
            category = "人情社交",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("聚会", "请客"),
            category = "人情社交",
            subCategory = child("人情社交", "聚会请客"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("还款"),
            category = "转账",
            subCategory = child("转账", "还款"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("转给", "转至"),
            category = "转账",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("借出"),
            category = "转账",
            subCategory = child("转账", "借出"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("收款"),
            category = "转账",
            subCategory = child("转账", "收款"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("退款", "退货", "退回"),
            category = "退款",
            subCategory = child("退款", "退款"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("工资", "薪资", "薪酬", "代发工资"),
            category = "薪资",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("利息", "收益", "分红", "理财"),
            category = "理财",
            subCategory = child("理财", "基金理财"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("借入", "借款到账"),
            category = "借入",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("收红包", "红包到账"),
            category = "红包",
            subCategory = child("红包", "收红包"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("提现"),
            category = "提现",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("还款"),
            category = "还款",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("充值"),
            category = "充值",
            subCategory = null,
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("代付"),
            category = "代付",
            subCategory = child("代付", "代付款项"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("话费", "流量"),
            category = "通讯",
            subCategory = child("通讯", "话费流量"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("宽带"),
            category = "通讯",
            subCategory = child("通讯", "宽带"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("理发", "美发", "美甲"),
            category = "美容",
            subCategory = child("美容", "美发美甲"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("美容", "护肤"),
            category = "美容",
            subCategory = child("美容", "护肤美容"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("健身", "瑜伽"),
            category = "美容",
            subCategory = child("美容", "健身瑜伽"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("房租", "房贷", "租金"),
            category = "住房",
            subCategory = child("住房", "房租房贷"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("物业费", "水费", "电费", "燃气费"),
            category = "住房",
            subCategory = child("住房", "物业水电"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("猫粮", "狗粮", "宠物食品"),
            category = "宠物",
            subCategory = child("宠物", "宠物食品"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("宠物医院", "宠物医疗"),
            category = "宠物",
            subCategory = child("宠物", "宠物医疗"),
            weight = STRONG_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("转账"),
            category = "转账",
            subCategory = null,
            weight = WEAK_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
        KeywordRule(
            keywords = listOf("消费", "付款", "支付", "扣款"),
            category = "消费",
            subCategory = null,
            weight = WEAK_KEYWORD_WEIGHT,
            excludeWords = emptyList(),
            matchType = MatchType.CONTAINS,
        ),
    )

    private val compiledKeywordRules: List<CompiledKeywordRule> = keywordRules.map { rule ->
        val keywords = rule.keywords.map(::normalized)
        CompiledKeywordRule(
            category = rule.category,
            subCategory = rule.subCategory,
            weight = rule.weight,
            keywords = keywords,
            excludeWords = rule.excludeWords.map(::normalized),
            matchType = rule.matchType,
            regexes = if (rule.matchType == MatchType.REGEX_WORD) {
                keywords.map(::wordRegex)
            } else {
                emptyList()
            },
        )
    }

    private val platformPrefix = Regex(
        """^\s*(?:财付通\s*[-－—–]?\s*)?(?:微信支付|支付宝)\s*[-－—–:]?\s*""",
    )

    fun infer(text: String): CategoryMatchResult =
        infer(text, CategoryStore.expense + CategoryStore.income + CategoryStore.other)

    /** 动态层只感知调用方提供的分类树，不改变内置商户、关键词和权重。 */
    fun infer(
        text: String,
        allowedCategories: Collection<String>,
        customSubCategoryOwners: Map<String, Set<String>>,
    ): CategoryMatchResult {
        val base = infer(text, allowedCategories)
        val body = normalizeForMatching(text)
        val category = base.category
        if (body.isEmpty() || customSubCategoryOwners.isEmpty() ||
            category !in allowedCategories || category == FALLBACK_CATEGORY
        ) return base

        val normalizedCustom = customSubCategoryOwners.entries
            .filter { (_, owner) -> owner.contains(category) }
            .mapNotNull { (name, _) -> normalizedOrNull(name) }
            .distinct()
            .sortedByDescending(String::length)
        val customHits = normalizedCustom
            .filter(body::contains)
            .filter { token -> customSubCategoryOwners[token]?.contains(category) == true }
        if (customHits.isEmpty()) return base
        val longest = customHits.first().length
        val best = customHits.filter { it.length == longest }
        if (best.size != 1) return base

        // 子类词只负责细化分类，不能单独把未知文本提升成某个主分类。
        // 先移除子类词，剩余正文必须还能由内置规则命中同一主分类。
        val allCustomTokens = customSubCategoryOwners.keys.mapNotNull(::normalizedOrNull)
            .sortedByDescending(String::length)
        val categoryAnchor = normalizeForMatching(
            allCustomTokens.fold(body) { current, token -> current.replace(token, " ") },
        )
        val anchorHit = infer(categoryAnchor, allowedCategories)
        if (categoryAnchor.isNotEmpty() && anchorHit.source == MatchSource.FALLBACK) return base
        if (anchorHit.category != category) return base
        return refinedResult(category, best.single(), base)
    }

    private fun refinedResult(
        category: String,
        subCategory: String,
        base: CategoryMatchResult,
    ): CategoryMatchResult = CategoryMatchResult(
        category = category,
        subCategory = subCategory,
        source = base.source,
        weight = base.weight,
    )

    /**
     * 按用户当前启用的一级分类推断。
     *
     * 某个高权重分类被用户禁用时跳过该命中，而不是返回无效分类；全部落空时优先回退
     * “其他”，连“其他”也不可用才使用调用方列表的第一项。
     */
    fun infer(text: String, allowedCategories: Collection<String>): CategoryMatchResult {
        val allowed = allowedCategories.map(String::trim).filter(String::isNotEmpty).toSet()
        val body = normalizeForMatching(text)
        if (body.isEmpty()) return fallback(allowed)

        val merchantMatches = merchantMap.entries
            .asSequence()
            .filter { (keyword, target) ->
                target.category in allowed &&
                    merchantMatches(keyword, body) &&
                    target.excludeWords.none { body.contains(normalized(it)) }
            }
            .sortedByDescending { it.key.length }
            .toList()
        if (merchantMatches.isNotEmpty()) {
            val longest = merchantMatches.first().key.length
            val candidates = merchantMatches
                .filter { it.key.length == longest }
                .map { (_, target) -> result(target.category, target.subCategory, MatchSource.MERCHANT, MERCHANT_WEIGHT) }
                .distinct()
            if (candidates.size == 1) return candidates.single()
            return fallback(allowed)
        }

        val hits = compiledKeywordRules
            .asSequence()
            .filter { it.category in allowed }
            .filter { ruleMatches(it, body) }
            .map { result(it.category, it.subCategory, MatchSource.KEYWORD, it.weight) }
            .distinct()
            .toList()
        if (hits.isEmpty()) return fallback(allowed)

        val maxWeight = hits.maxOf { it.weight }
        val best = hits.filter { it.weight == maxWeight }
        return if (best.size == 1) best.single() else fallback(allowed)
    }

    private fun ruleMatches(rule: CompiledKeywordRule, body: String): Boolean {
        if (rule.excludeWords.any(body::contains)) return false
        return when (rule.matchType) {
            MatchType.REGEX_WORD -> rule.regexes.any { it.containsMatchIn(body) }
            MatchType.EXACT_MERCHANT, MatchType.CONTAINS -> rule.keywords.any(body::contains)
        }
    }

    private fun merchantMatches(keyword: String, body: String): Boolean =
        boundaryMerchantRegexes[keyword]?.containsMatchIn(body) ?: body.contains(keyword)

    private fun wordRegex(keyword: String): Regex = Regex(
        "(?<![\\p{L}\\p{N}])" + Regex.escape(keyword) + "(?![\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE),
    )

    private fun result(
        category: String,
        subCategory: String?,
        source: MatchSource,
        weight: Int,
    ): CategoryMatchResult = CategoryMatchResult(
        category = category,
        subCategory = CategoryStore.validSubCategory(category, subCategory),
        source = source,
        weight = weight,
    )

    private fun fallback(allowed: Set<String>): CategoryMatchResult {
        val category = when {
            FALLBACK_CATEGORY in allowed -> FALLBACK_CATEGORY
            allowed.isNotEmpty() -> allowed.first()
            else -> FALLBACK_CATEGORY
        }
        return CategoryMatchResult(category, null, MatchSource.FALLBACK, FALLBACK_WEIGHT)
    }

    private fun normalizeForMatching(text: String): String {
        var value = normalized(text).trim()
        while (true) {
            val stripped = platformPrefix.replaceFirst(value, "").trim()
            if (stripped == value) break
            value = stripped
        }
        return value
    }

    private fun normalized(text: String): String = runCatching {
        Normalizer.normalize(text, Normalizer.Form.NFKC)
    }.getOrDefault(text)
        .lowercase(Locale.ROOT)
        .replace('－', '-')
        .replace('–', '-')
        .replace('—', '-')
        .replace(whitespace, " ")
        .trim()

    private fun normalizedOrNull(text: String): String? =
        normalized(text).takeIf { it.isNotEmpty() }

    /** 从 CategoryStore 的真实分类树选第一个合法子类，禁止推断内核另建词表。 */
    private fun child(category: String, vararg candidates: String): String? =
        candidates.firstNotNullOfOrNull { CategoryStore.validSubCategory(category, it) }
}
