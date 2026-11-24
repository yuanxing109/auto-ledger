package com.eta.autoledger.data

/**
 * 两阶段确认期间暂存的待合并账单。
 *
 * [amountCents] 只用于与包名共同构成内存键，避免 `Double` 金额的精度差异。
 * 其余字段与最终 [LedgerEntry] 的落库信息保持一致，后续接入解析链路时可原样合并。
 */
data class PendingBill(
    val packageName: String,
    val amountCents: Long,
    val type: String,
    val category: String,
    val note: String,
    val timeMillis: Long,
    val source: String,
    val channel: NotifyChannel,
    val subCategory: String?,
    val accountTail: String? = null,
)

/**
 * 进程内两阶段确认存储。
 *
 * 键为 `packageName + amountCents`。同一键再次 [put] 时会覆盖旧记录并重置 TTL。
 * 所有公开读写都通过同一把锁串行化，可从通知回调、协程和 Worker 共享使用。
 * 当前只提供内存能力，不负责接入 ParsePipeline 或任何服务。
 */
object PendingMap {

    const val TTL_MS = 5 * 60 * 1000L

    private data class PendingKey(
        val packageName: String,
        val amountCents: Long,
    )

    private data class Entry(
        val bill: PendingBill,
        val createdAtMillis: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<PendingKey, Entry>()

    /** 写入或覆盖一条待确认记录，并从 [now] 重新起算 5 分钟 TTL。 */
    fun put(bill: PendingBill, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            entries[keyOf(bill.packageName, bill.amountCents)] = Entry(bill, now)
        }
    }

    /**
     * 消费一条未过期记录。
     *
     * 无论成功、过期还是不存在，记录都会从内存中移除。过期项静默返回 null。
     */
    fun consume(
        packageName: String,
        amountCents: Long,
        now: Long = System.currentTimeMillis(),
    ): PendingBill? = synchronized(lock) {
        val key = keyOf(packageName, amountCents)
        val entry = entries.remove(key) ?: return@synchronized null
        if (isExpired(entry, now)) null else entry.bill
    }

    /** 删除所有已过期记录，返回实际删除条数。 */
    fun prune(now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneLocked(now)
    }

    /** 当前仍有效的记录数。查询时会顺带清理过期项。 */
    fun size(now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        pruneLocked(now)
        entries.size
    }

    /** 清空全部记录，仅供测试和诊断使用。 */
    fun clearForTest() {
        synchronized(lock) { entries.clear() }
    }

    private fun pruneLocked(now: Long): Int {
        var removed = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (isExpired(entry, now)) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    /** 恰好 5 分钟即过期，剩 1ms 时仍有效。 */
    private fun isExpired(entry: Entry, now: Long): Boolean =
        now - entry.createdAtMillis >= TTL_MS

    private fun keyOf(packageName: String, amountCents: Long) = PendingKey(packageName, amountCents)
}
