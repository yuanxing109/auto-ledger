package com.eta.autoledger.data

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
            .edit().putString(key(type), arr.toString()).apply()
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