package com.eta.autoledger.data

import androidx.core.content.edit
import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray

data class CategoryStyle(val name: String, val emoji: String, val color: Color)

object CategoryStore {
    val expense = listOf(
        "其他", "餐饮", "购物", "转账", "交通", "消费", "人情社交", "娱乐", "教育",
        "通讯", "美容", "医疗", "红包", "旅行", "宠物", "住房", "代付", "投资",
        "借出", "还债", "亲子",
    )
    val income = listOf("转账", "红包", "退款", "其他", "薪资", "理财", "借入", "收债")
    val other = listOf("提现", "还款", "充值", "代付")

    /**
     * AI 可输出的一级分类枚举。
     *
     * 这是 Prompt 的稳定契约。用户可用分类仍由 [names] 决定，AI 返回值还要再与
     * 对应收支方向的分类列表求交集，不能仅凭模型输出就直接落库。
     */
    val aiPrimaryCategories = listOf(
        "餐饮", "转账", "购物", "交通", "消费", "人情社交", "通讯", "娱乐", "教育",
        "美容", "旅行", "医疗", "红包", "宠物", "住房", "代付", "其他",
    )

    /** 一级分类到默认二级分类的唯一映射。推断与 AI Prompt 都只能从这里取子类。 */
    val subCategoryTree: Map<String, List<String>> = linkedMapOf(
        "其他" to emptyList(),
        "餐饮" to listOf("早中晚餐", "外卖", "水果零食", "奶茶咖啡"),
        "购物" to listOf("日用品", "超市便利", "服饰鞋包", "数码电器", "美妆个护"),
        "转账" to listOf("转账", "还款", "借出", "收款"),
        "交通" to listOf("公交地铁", "打车租车", "火车飞机", "加油停车"),
        "消费" to listOf("日常消费"),
        "人情社交" to listOf("礼金红包", "聚会请客", "人情往来"),
        "娱乐" to listOf("视频会员", "电子游戏", "文娱演出"),
        "教育" to listOf("培训考试", "书报杂志", "学费"),
        "通讯" to listOf("话费流量", "宽带"),
        "美容" to listOf("美发美甲", "护肤美容", "健身瑜伽"),
        "医疗" to listOf("药店买药", "门诊住院"),
        "红包" to listOf("收红包", "发红包"),
        "旅行" to listOf("酒店住宿", "景点门票", "旅行交通", "火车飞机"),
        "宠物" to listOf("宠物食品", "宠物医疗"),
        "住房" to listOf("房租房贷", "物业水电"),
        "代付" to listOf("代付款项"),
        "投资" to listOf("基金理财"),
        "借出" to listOf("借出款项"),
        "还债" to listOf("偿还债务"),
        "亲子" to listOf("亲子消费"),
        "退款" to listOf("退款"),
        "理财" to listOf("基金理财"),
    )

    fun subCategories(category: String): List<String> = subCategoryTree[category].orEmpty()

    fun validSubCategory(category: String, candidate: String?): String? {
        val value = candidate?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && it in subCategories(category) }
    }

    fun subCategoryTreeFor(categories: Collection<String>): Map<String, List<String>> =
        categories.distinct().associateWith(::subCategories).filterValues { it.isNotEmpty() }

    fun defaults(type: Int): List<String> = when (type) {
        1 -> income
        2 -> other
        else -> expense
    }

    fun key(type: Int): String = when (type) {
        1 -> "cat_income"
        2 -> "cat_other"
        else -> "cat_expense"
    }

    fun names(context: Context, type: Int): List<String> {
        val raw = context.getSharedPreferences("auto_ledger", Context.MODE_PRIVATE)
            .getString(key(type), "") ?: ""
        if (raw.isBlank()) return defaults(type)
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.ifEmpty { defaults(type) }
        }.getOrElse { defaults(type) }
    }

    fun save(context: Context, type: Int, names: List<String>) {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        context.getSharedPreferences("auto_ledger", Context.MODE_PRIVATE)
            .edit { putString(key(type), arr.toString()) }
    }

    fun style(name: String): CategoryStyle = CategoryStyle(name, emoji(name), color(name))

    fun emoji(name: String): String = when (name) {
        "餐饮" -> "🍜"
        "购物" -> "🛍️"
        "转账" -> "💸"
        "交通" -> "🚌"
        "消费" -> "💳"
        "人情社交" -> "👥"
        "娱乐" -> "🎮"
        "教育" -> "📚"
        "通讯" -> "📱"
        "美容" -> "💄"
        "医疗" -> "🏥"
        "红包" -> "🧧"
        "旅行" -> "✈️"
        "宠物" -> "🐾"
        "住房" -> "🏠"
        "代付" -> "🤝"
        "投资" -> "📈"
        "借出" -> "📤"
        "还债" -> "💰"
        "亲子" -> "👨‍👩‍👧"
        "退款" -> "↩️"
        "薪资" -> "💼"
        "理财" -> "📈"
        "借入" -> "📥"
        "收债" -> "📤"
        "提现" -> "💵"
        "还款" -> "💳"
        "充值" -> "⚡"
        else -> "⚪"
    }

    // 马卡龙/莫兰迪低饱和明亮色系：柔和但有辨识度
    private val macaron = listOf(
        Color(0xFF6E9CF5),
        Color(0xFFF2A96B),
        Color(0xFF7EC8A9),
        Color(0xFFC3A6E6),
        Color(0xFFF49BB0),
        Color(0xFFF0C567),
        Color(0xFF7CC6E8),
        Color(0xFF9CCB8A),
    )

    fun color(name: String): Color = when (name) {
        "餐饮" -> Color(0xFFF2A96B)
        "购物" -> Color(0xFFC3A6E6)
        "转账" -> Color(0xFF6E9CF5)
        "交通" -> Color(0xFF7CC6E8)
        "红包" -> Color(0xFFF49BB0)
        "旅行" -> Color(0xFF8FBFEF)
        "医疗" -> Color(0xFFEF8A8A)
        "教育" -> Color(0xFFA99AEA)
        "娱乐" -> Color(0xFFF0C567)
        "薪资" -> Color(0xFF7EC8A9)
        "投资", "理财" -> Color(0xFFC3A6E6)
        "退款" -> Color(0xFF8FD3C4)
        "提现", "充值" -> Color(0xFFF0C567)
        "还款" -> Color(0xFF9DB8F2)
        else -> {
            val idx = ((name.hashCode() % macaron.size) + macaron.size) % macaron.size
            macaron[idx]
        }
    }
}
