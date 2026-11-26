package com.eta.autoledger.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class CategoryStyle(val name: String, val emoji: String, val color: Color)

object CategoryStore {
    private const val PREFS_NAME = "auto_ledger"
    private const val KEY_SUB_CATEGORY_TREE = "sub_category_tree_v2"
    private const val MAX_SUB_CATEGORY_LENGTH = 20

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

    /** 一级分类到默认二级分类的唯一映射。用于首次启动和持久化数据损坏时恢复。 */
    private val defaultSubCategoryTree: Map<String, List<String>> = linkedMapOf(
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

    private val defaultSubCategoryTrees: Map<Int, Map<String, List<String>>> = mapOf(
        0 to expense.associateWith { defaultSubCategoryTree[it].orEmpty() },
        1 to income.associateWith { defaultSubCategoryTree[it].orEmpty() },
        2 to other.associateWith { defaultSubCategoryTree[it].orEmpty() },
    )

    private val prefsLock = Any()

    @Volatile
    private var activeSubCategoryTrees: Map<Int, Map<String, List<String>>> = defaultSubCategoryTrees

    @Volatile
    private var subCategoriesLoaded = false

    /** 推断与 AI Prompt 使用的跨组二级分类并集。 */
    val subCategoryTree: Map<String, List<String>>
        get() = primaryCategories.associateWith(::subCategories)

    /** 所有被锁定的一级分类，按支出、收入、其他三组去重。 */
    val primaryCategories: List<String>
        get() = (expense + income + other).distinct()

    fun ensureSubCategoriesLoaded(context: Context) {
        if (subCategoriesLoaded) return
        synchronized(prefsLock) {
            if (subCategoriesLoaded) return
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SUB_CATEGORY_TREE, null)
            activeSubCategoryTrees = decodeSubCategoryTree(raw)
            subCategoriesLoaded = true
        }
    }

    fun subCategories(context: Context, type: Int, category: String): List<String> {
        ensureSubCategoriesLoaded(context)
        return subCategories(type, category)
    }

    fun subCategories(type: Int, category: String): List<String> =
        activeSubCategoryTrees[type]?.get(category).orEmpty()

    /** 内置子分类只用于区分“系统默认”与“用户新增”，不参与推断规则。 */
    fun defaultSubCategories(category: String): List<String> =
        defaultSubCategoryTree[category].orEmpty()

    /** 兼容 AI Prompt 等无组入口：同名一级分类的子类取各组并集。 */
    fun subCategories(category: String): List<String> = (0..2)
        .flatMap { type -> subCategories(type, category) }
        .distinct()

    /** 仅返回当前组中用户新增、无法由内置默认项恢复的子分类。 */
    fun customSubCategories(context: Context, type: Int, category: String): List<String> {
        ensureSubCategoriesLoaded(context)
        return customSubCategories(type, category)
    }

    internal fun customSubCategories(type: Int, category: String): List<String> {
        val builtIn = defaultSubCategoryTree[category].orEmpty().toSet()
        return subCategories(type, category).filterNot(builtIn::contains)
    }

    /** 只返回当前组内用户新增的二级分类归属表。 */
    fun customSubCategoryOwners(
        context: Context,
        type: Int,
        categories: Collection<String>,
    ): Map<String, Set<String>> {
        val owners = LinkedHashMap<String, MutableSet<String>>()
        categories.forEach { category ->
            customSubCategories(context, type, category).forEach { name ->
                owners.getOrPut(name) { linkedSetOf() }.add(category)
            }
        }
        return owners
    }


    fun addSubCategory(context: Context, type: Int, category: String, name: String): Boolean = synchronized(prefsLock) {
        ensureSubCategoriesLoaded(context)
        if (category !in defaults(type)) return@synchronized false
        val value = normalizeSubCategoryName(name) ?: return@synchronized false
        if (value.length > MAX_SUB_CATEGORY_LENGTH) return@synchronized false
        val current = subCategories(type, category)
        if (value in current) return@synchronized false
        saveTree(context, replaceSubCategories(type, category, current + value))
    }

    fun renameSubCategory(
        context: Context,
        type: Int,
        category: String,
        oldName: String,
        newName: String,
    ): Boolean = synchronized(prefsLock) {
        ensureSubCategoriesLoaded(context)
        if (category !in defaults(type)) return@synchronized false
        val value = normalizeSubCategoryName(newName) ?: return@synchronized false
        if (value.length > MAX_SUB_CATEGORY_LENGTH) return@synchronized false
        val current = subCategories(type, category)
        if (oldName !in current || value in current) return@synchronized false
        saveTree(context, replaceSubCategories(type, category, current.map { if (it == oldName) value else it }))
    }

    fun deleteSubCategory(context: Context, type: Int, category: String, name: String): Boolean = synchronized(prefsLock) {
        ensureSubCategoriesLoaded(context)
        if (category !in defaults(type)) return@synchronized false
        val current = subCategories(type, category)
        if (name !in current) return@synchronized false
        saveTree(context, replaceSubCategories(type, category, current.filterNot { it == name }))
    }

    fun subCategoryValidationError(context: Context, type: Int, category: String, raw: String): String? {
        ensureSubCategoriesLoaded(context)
        val value = normalizeSubCategoryName(raw) ?: return "请输入分类名称"
        if (value.length > MAX_SUB_CATEGORY_LENGTH) return "分类名称最多 ${MAX_SUB_CATEGORY_LENGTH} 个字"
        if (value in subCategories(type, category)) return "该子分类已存在"
        return null
    }

    internal fun decodeSubCategoryTree(raw: String?): Map<Int, Map<String, List<String>>> {
        val decoded = if (raw.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val obj = JSONObject(raw)
                buildMap {
                    listOf(0 to "expense", 1 to "income", 2 to "other").forEach { (type, key) ->
                        val group = obj.optJSONObject(key) ?: return@forEach
                        put(type, buildMap {
                            group.keys().forEach { category ->
                                val values = ArrayList<String>()
                                val array = group.optJSONArray(category)
                                if (array != null) {
                                    for (i in 0 until array.length()) {
                                        normalizeSubCategoryName(array.optString(i, ""))?.let(values::add)
                                    }
                                }
                                put(category, values.distinct())
                            }
                        })
                    }
                }
            }.getOrNull()
        }
        return reconcileSubCategoryTrees(decoded)
    }

    private fun saveTree(context: Context, tree: Map<Int, Map<String, List<String>>>): Boolean {
        val normalized = reconcileSubCategoryTrees(tree)
        val previous = activeSubCategoryTrees
        activeSubCategoryTrees = normalized
        val obj = JSONObject()
        listOf(0 to "expense", 1 to "income", 2 to "other").forEach { (type, key) ->
            val group = JSONObject()
            normalized[type].orEmpty().forEach { (category, values) ->
                group.put(category, JSONArray(values))
            }
            obj.put(key, group)
        }
        val committed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SUB_CATEGORY_TREE, obj.toString())
            .commit()
        if (!committed) {
            activeSubCategoryTrees = previous
            return false
        }
        subCategoriesLoaded = true
        return true
    }

    private fun reconcileSubCategoryTrees(
        candidate: Map<Int, Map<String, List<String>>>?,
    ): Map<Int, Map<String, List<String>>> {
        val result = LinkedHashMap<Int, Map<String, List<String>>>()
        listOf(0, 1, 2).forEach { type ->
            val group = LinkedHashMap<String, List<String>>()
            defaults(type).forEach { category ->
                val values = candidate?.get(type)?.get(category)
                    ?: defaultSubCategoryTree[category].orEmpty()
                group[category] = values
                    .mapNotNull(::normalizeSubCategoryName)
                    .filter { it.length <= MAX_SUB_CATEGORY_LENGTH }
                    .distinct()
            }
            result[type] = group
        }
        return result
    }

    private fun replaceSubCategories(
        type: Int,
        category: String,
        values: List<String>,
    ): Map<Int, Map<String, List<String>>> {
        val group = LinkedHashMap(activeSubCategoryTrees[type].orEmpty())
        group[category] = values
        return activeSubCategoryTrees + (type to group)
    }

    internal fun normalizeSubCategoryName(raw: String): String? {
        val value = raw.trim().replace(Regex("\\s+"), " ")
        return value.takeIf { it.isNotEmpty() }
    }

    /** 仅供纯 JVM 测试注入分类树；生产写入口必须经过 Context 与 commit 校验。 */
    internal fun rememberForTest(type: Int, category: String, names: List<String>) {
        synchronized(prefsLock) {
            val group = LinkedHashMap(activeSubCategoryTrees[type].orEmpty())
            group[category] = names
                .mapNotNull(::normalizeSubCategoryName)
                .filter { it.length <= MAX_SUB_CATEGORY_LENGTH }
                .distinct()
            activeSubCategoryTrees = activeSubCategoryTrees + (type to group)
            subCategoriesLoaded = true
        }
    }

    internal fun resetForTest() {
        synchronized(prefsLock) {
            activeSubCategoryTrees = defaultSubCategoryTrees
            subCategoriesLoaded = false
        }
    }

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

    fun names(context: Context, type: Int): List<String> {
        ensureSubCategoriesLoaded(context)
        // 一级分类是推断契约的一部分，旧版本保存的自定义主分类不再读取。
        return defaults(type)
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
