package com.eta.autoledger.data

/**
 * 解析结果的短时内存去重。
 *
 * 为什么需要它：数据库层已有 `external_id` 唯一索引，但那个 key 由
 * `dedupeKey = pkg|title|text|bigText|postTime` 算出——**含毫秒级 postTime**。
 * 同一条短信被系统重复投递（多段短信分组、接收器被连调两次、进程重启后补发）时
 * postTime 会差几毫秒，`external_id` 与「毫秒+金额+收支」指纹**双双对不上**，
 * 于是同一笔账被写成两行。截图里 15:11 那两条一模一样的 46.94 就是这么来的。
 *
 * 这里改用**分钟级**时间参与指纹，并在内存里记住最近 5 分钟已入库的指纹，
 * 命中即丢弃。只在自动记账入口（短信 / 通知）调用，不碰手动记账、CSV 导入与云恢复。
 *
 * 取舍：同一分钟内、同金额、同商户的两笔真实消费会被合并成一笔。
 * 这在自动记账场景下是可接受的——宁可少记一笔让用户补，也不要凭空多出一笔让用户删。
 */
object ParseDedup {

    /** 指纹有效期。短信/通知的重复投递几乎都发生在秒级，5 分钟已经非常宽松。 */
    private const val WINDOW_MS = 5 * 60_000L

    /** 指纹 -> 首次入库时刻。LinkedHashMap 保持插入顺序，便于从头部清理过期项。 */
    private val recent = LinkedHashMap<String, Long>()

    /**
     * 生成指纹：金额 + 商户/标题 + 发生时间（精确到分钟）。
     *
     * 金额用两位小数的定点格式，避免 46.9 与 46.90 被当成不同金额；
     * 时间取 `timeMillis / 60_000`，即「同一分钟内」视为同一时刻。
     */
    fun fingerprint(amount: Double, merchant: String, timeMillis: Long): String {
        val minute = timeMillis / 60_000L
        val normalizedMerchant = merchant.trim().replace(Regex("\\s+"), " ")
        return "%.2f|%s|%d".format(amount, normalizedMerchant, minute)
    }

    /**
     * 尝试占用指纹。
     *
     * @return true 表示这是 5 分钟内首次出现，可以入库；false 表示重复，应直接丢弃。
     */
    @Synchronized
    fun accept(fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        val last = recent[fingerprint]
        if (last != null && now - last <= WINDOW_MS) return false
        recent[fingerprint] = now
        return true
    }

    /** 清理过期指纹，防止内存里无限堆积。按插入顺序从头删，遇到未过期即停。 */
    private fun prune(now: Long) {
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > WINDOW_MS) iterator.remove() else break
        }
    }

    /** 仅供测试与调试：清空缓存。 */
    @Synchronized
    fun clear() = recent.clear()
}
