package com.eta.autoledger.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.eta.autoledger.MainActivity
import com.eta.autoledger.data.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: EXTRA_KIND_DAILY
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "记账提醒"
        // 汇总要走 SQL 聚合，放到 IO 协程里；goAsync() 让系统等这一小段异步工作。
        val pending = goAsync()
        scope.launch {
            try {
                val text = buildSummary(context, kind)
                post(context, title, text)
                // 一次性精确闹钟不会自行重复：触发后立刻重排下一次，否则只响这一次
                ReminderScheduler.rescheduleForKind(context, kind)
            } finally {
                pending.finish()
            }
        }
    }

    private fun post(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "记账提醒", NotificationManager.IMPORTANCE_HIGH),
        )
        val content = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(content)
            .build()
        runCatching { manager.notify(2000, notification) }
    }

    private suspend fun buildSummary(context: Context, kind: String): String {
        val now = Calendar.getInstance()
        val start = when (kind) {
            EXTRA_KIND_WEEKLY -> (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                val dow = get(Calendar.DAY_OF_WEEK)
                add(Calendar.DAY_OF_YEAR, if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow)
            }
            EXTRA_KIND_MONTHLY -> (now.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            else -> (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        }
        val end = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }

        val totals = LedgerRepository.rangeTotals(context, start.timeInMillis, end.timeInMillis)
        if (totals.expense == 0.0 && totals.income == 0.0) {
            return "还没有账目，打开记一笔记录今天的收支吧。"
        }
        val prefix = when (kind) {
            EXTRA_KIND_WEEKLY -> "本周"
            EXTRA_KIND_MONTHLY -> "本月"
            else -> "今天"
        }
        return "${prefix}共 ${totals.count} 笔：支出 ¥ %,.2f · 收入 ¥ %,.2f".format(totals.expense, totals.income)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND_DAILY = "daily"
        const val EXTRA_KIND_WEEKLY = "weekly"
        const val EXTRA_KIND_MONTHLY = "monthly"
        private const val CHANNEL = "ledger_reminder"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}