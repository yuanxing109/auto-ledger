package com.eta.autoledger.data

/**
 * 解析结果的短时内存去重。
 *
 * 为什么需要它：数据库层已有 `external_id` 唯一索引，但那个 key 由
 * `dedupeKey = pkg|title|text|bigText|subText|秒级 postTime` 算出。
 * 同一条短信被系统重复投递（多段短信分组、接收器被连调两次、进程重启后补发）时
 * postTime 会差几毫秒到几百毫秒，`external_id` 对不上，于是同一笔账被写成两行。
 * 截图里 15:11 那两条一模一样的 46.94 就是这么来的。
 *
 * ── 指纹粒度：秒级 + 短促窗口（2026-11 修订）──────────────────
 * 早期版本用**分钟级**时间参与指纹，副作用是「同一分钟、同金额、同商户」的两笔真实消费
 * （便利店连刷两次 9.9）被合并成一笔——去重过度。现在改成两层：
 *
 *  1. 指纹里的时间桶取 `timeMillis / 1000`（秒级）：重复投递常落在同一秒内，天然同桶；
 *  2. 同一「金额+商户」在 [BURST_MS]（2 秒）内再次出现，即使跨了秒边界也判重。
 *
 * 这样「重复投递」被挡住，而相隔几秒以上的两笔真账都能留下。
 * 只在自动记账入口（短信 / 通知）调用，不碰手动记账、CSV 导入与云恢复。
 */
object ParseDedup {

    /** 指纹有效期。短信/通知的重复投递几乎都发生在秒级，5 分钟已经非常宽松。 */
    private const val WINDOW_MS = 5 * 60_000L

    /**
     * 「同一笔钱被投递两次」的短促窗口。
     *
     * 秒级时间桶挡不住跨秒边界的重复（12:00:00.998 与 12:00:01.002 落在两个桶里），
     * 用 2 秒的促发窗口兜住；同时远小于「人手动连刷两笔」的间隔，不会误吞真账。
     */
    private const val BURST_MS = 2_000L

    /** 指纹 -> 首次入库时刻。LinkedHashMap 保持插入顺序，便于从头部清理过期项。 */
    private val recent = LinkedHashMap<String, Long>()

    /** 「金额|商户」-> 最近一次放行时刻，用于跨秒边界的促发判重。 */
    private val bursts = LinkedHashMap<String, Long>()

    /**
     * 生成指纹：金额 + 商户/标题 + 发生时间（精确到秒）。
     *
     * 金额用两位小数的定点格式，避免 46.9 与 46.90 被当成不同金额；
     * 时间取 `timeMillis / 1000`，即「同一秒内」视为同一时刻 —— 比分钟级细，
     * 不会再把同一分钟内的两笔真实消费并成一笔。
     */
    fun fingerprint(amount: Double, merchant: String, timeMillis: Long): String {
        val second = timeMillis / 1_000L
        val normalizedMerchant = merchant.trim().replace(Regex("\\s+"), " ")
        return "%.2f|%s|%d".format(amount, normalizedMerchant, second)
    }

    /** 指纹去掉时间桶后的部分，即「金额|商户」。 */
    private fun coreOf(fingerprint: String): String = fingerprint.substringBeforeLast('|')

    /**
     * 尝试占用指纹。
     *
     * @return true 表示这是首次出现，可以入库；false 表示重复，应直接丢弃。
     */
    @Synchronized
    fun accept(fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        val last = recent[fingerprint]
        if (last != null && now - last <= WINDOW_MS) return false
        // 跨秒边界的重复投递：时间桶不同，但「金额+商户」刚放行过，仍在促发窗口内
        val core = coreOf(fingerprint)
        val burst = bursts[core]
        if (burst != null && now - burst <= BURST_MS) return false
        recent[fingerprint] = now
        bursts[core] = now
        return true
    }

    /** 清理过期指纹，防止内存里无限堆积。按插入顺序从头删，遇到未过期即停。 */
    private fun prune(now: Long) {
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > WINDOW_MS) iterator.remove() else break
        }
        val burstIterator = bursts.entries.iterator()
        while (burstIterator.hasNext()) {
            val entry = burstIterator.next()
            if (now - entry.value > BURST_MS) burstIterator.remove() else break
        }
    }

    /** 仅供测试与调试：清空缓存。 */
    @Synchronized
    fun clear() {
        recent.clear()
        bursts.clear()
    }
}
