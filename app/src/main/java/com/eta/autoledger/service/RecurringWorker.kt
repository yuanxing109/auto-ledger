package com.eta.autoledger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eta.autoledger.R
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.RecurringRule
import com.eta.autoledger.data.RecurringExternalIds
import com.eta.autoledger.util.AppDateFormats
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 周期账单自动入账。
 *
 * 每天检查一次所有 isRecurring 模板：命中今天的规则、且今天还没生成过，
 * 就插一条真实账单并通知用户。
 *
 * 设计取舍：
 * - 用「区间内是否已生成」判定幂等，而不是给模板加 lastRunDate 字段：
 *   后者要再改一次表结构，且用户手动删掉实账后无法重生成。
 * - 生成失败不重试（Result.failure），只 return success 并跳过——
 *   周期账单晚一天入账可以接受，卡在重试队列里反复写库更糟。
 * - 时间窗用 [00:00, 次日00:00)，与首页/报表的日边界一致。
 */
class RecurringWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "recurring_bills"
        const val CHANNEL = "auto_ledger_recurring"
        private const val NOTIFY_ID_BASE = 9300
        /** 自动生成的实账 source，便于用户区分「这是周期账单自动记的」。 */
        const val SOURCE = "recurring"
        /** 模板行的 source，仅用于展示时区分；模板本身不参与统计。 */
        const val SOURCE_TEMPLATE = "周期模板"

        /** 每天凌晨跑一次；系统会在此后的维护窗口内择机执行。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntilNextRun(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** App 启动时立刻补跑一次：用户可能几天没开 App，定时任务被系统压后过。 */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<RecurringWorker>().build(),
            )
        }

        /** 距离下一个 00:05 还有多久（避开零点整的系统高峰）。 */
        private fun delayUntilNextRun(): Long {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val dao = LedgerRepository.dao(context)

        // 今天 00:00 ~ 明天 00:00
        val dayStart = (Calendar.getInstance() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayEnd = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val today = dayStart

        val templates = runCatching { dao.recurringTemplates() }.getOrElse { return Result.success() }
        if (templates.isEmpty()) return Result.success()

        val generated = mutableListOf<LedgerEntry>()
        for (template in templates) {
            val rule = RecurringRule.parse(template.recurringRule) ?: continue
            if (!rule.matches(today)) continue

            // 幂等：external_id 唯一索引保证「每个模板每天最多一笔」。
            // insertAll 用 IGNORE，重复时 rowId = -1，直接跳过——无需先查再插。
            val entry = template.copy(
                id = 0L,
                timeMillis = today.timeInMillis,
                time = AppDateFormats.format(today, AppDateFormats.dateTime),
                source = SOURCE,
                externalId = RecurringExternalIds.generate(template.id, today),
                isRecurring = false,
                recurringRule = null,
            )
            val rowId = runCatching { dao.insertAll(listOf(entry)) }.getOrNull()?.firstOrNull() ?: -1L
            if (rowId > 0) generated += entry
        }

        if (generated.isNotEmpty()) notifyGenerated(context, generated)
        return Result.success()
    }

    private fun notifyGenerated(context: Context, entries: List<LedgerEntry>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val summary = if (entries.size == 1) {
            val e = entries.first()
            val sign = if (e.type == "income") "+" else "-"
            "已为您自动记录周期账单：${e.note} $sign${"%,.2f".format(e.amount)}元"
        } else {
            "已为您自动记录 ${entries.size} 笔周期账单"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("周期记账")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFY_ID_BASE + entries.size, notification)
            }
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "周期记账", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "周期账单自动入账后的提醒"
            },
        )
    }
}
