package com.eta.autoledger.data

import android.content.Context
import android.util.Log

/**
 * 持久化去重表 `seen_key` 的读写门面。
 *
 * 为什么内存里已经有两层去重（监听器的 LRU、[ParseDedup] 的 5 分钟窗口）还要一张表：
 * 那两层都在进程内存里，进程被系统杀掉就全没了。而通知重复投递恰恰**经常**发生在
 * 进程重建之后 —— 系统补投积压通知、用户重装后恢复、部分 ROM 在重启时重放最近的通知。
 * 这些场景下内存去重完全失效，同一条扣款会入两笔。
 *
 * 表里存的是**通知/短信原文指纹**（[NotificationRules.dedupeKey]），不是账单指纹：
 * 原文相同就一定同源，而账单指纹（金额+商户+时间）会把「同一分钟同额的两笔真账」误杀。
 *
 * 写入时机是「解析成功后」，而不是「收到时」：解析失败的营销通知不占表空间，
 * 也让 TTL 窗口只覆盖真正记过账的文本。
 */
object SeenStore {

    private const val TAG = "AutoLedger"

    /**
     * 查这条原文是否已经在 [LedgerSchema.SEEN_KEY_TTL_MS] 内记过账。
     *
     * 查表本身极快（主键查询，单行），但它是 IO，必须在协程里调。
     * 任何异常都当作「没记过」放行 —— 宁可多记一笔让用户删，也不能因为一次数据库抖动漏记。
     */
    suspend fun contains(context: Context, key: String): Boolean {
        if (key.isBlank()) return false
        return runCatching {
            LedgerDatabase.get(context).seenKeyDao().contains(key)
        }.getOrElse {
            Log.w(TAG, "去重表查询失败，按未记录处理", it)
            false
        }
    }

    /**
     * 记下这条原文。REPLACE 语义：同一条原文重复投递只是刷新时间，不会插出第二行。
     */
    suspend fun remember(context: Context, key: String, now: Long = System.currentTimeMillis()) {
        if (key.isBlank()) return
        runCatching {
            LedgerDatabase.get(context).seenKeyDao().insert(SeenKeyEntity(key = key, at = now))
        }.onFailure {
            Log.w(TAG, "去重表写入失败", it)
        }
    }
}
