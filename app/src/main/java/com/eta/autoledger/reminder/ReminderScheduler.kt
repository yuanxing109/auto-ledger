package com.eta.autoledger.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eta.autoledger.AppPrefs
import java.util.Calendar

object ReminderScheduler {
    private const val REQ_DAILY = 1001
    private const val REQ_WEEKLY = 1002
    private const val REQ_MONTHLY = 1003

    fun scheduleDaily(context: Context, time: String) {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 20
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val trigger = nextRun(hour, minute, Calendar.DAY_OF_YEAR, 1)
        set(context, REQ_DAILY, trigger, ReminderReceiver.EXTRA_KIND_DAILY, "每日记账提醒")
    }

    fun scheduleWeekly(context: Context) {
        val now = Calendar.getInstance()
        val trigger = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 7)
        }
        set(context, REQ_WEEKLY, trigger, ReminderReceiver.EXTRA_KIND_WEEKLY, "记账周报")
    }

    fun scheduleMonthly(context: Context) {
        val now = Calendar.getInstance()
        val trigger = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, 1)
            }
        }
        set(context, REQ_MONTHLY, trigger, ReminderReceiver.EXTRA_KIND_MONTHLY, "记账月报")
    }

    fun cancelDaily(context: Context) = cancel(context, REQ_DAILY)
    fun cancelWeekly(context: Context) = cancel(context, REQ_WEEKLY)
    fun cancelMonthly(context: Context) = cancel(context, REQ_MONTHLY)

    /** 提醒触发后按类型重排下一次（一次性闹钟不会自己重复，否则只响一次）。 */
    fun rescheduleForKind(context: Context, kind: String) {
        val prefs = AppPrefs.get(context)
        when (kind) {
            ReminderReceiver.EXTRA_KIND_WEEKLY -> if (prefs.weeklyReport) scheduleWeekly(context)
            ReminderReceiver.EXTRA_KIND_MONTHLY -> if (prefs.monthlyReport) scheduleMonthly(context)
            else -> if (prefs.dailyReminder) scheduleDaily(context, prefs.reminderTime)
        }
    }

    /** 按当前偏好重建全部提醒（开机、应用更新、冷启动时调用，修复重启后提醒丢失）。 */
    fun rescheduleAll(context: Context) {
        val prefs = AppPrefs.get(context)
        if (prefs.dailyReminder) scheduleDaily(context, prefs.reminderTime) else cancelDaily(context)
        if (prefs.weeklyReport) scheduleWeekly(context) else cancelWeekly(context)
        if (prefs.monthlyReport) scheduleMonthly(context) else cancelMonthly(context)
    }

    private fun nextRun(hour: Int, minute: Int, field: Int, amount: Int): Calendar {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(field, amount)
            }
        }
        return target
    }

    private fun set(context: Context, requestCode: Int, trigger: Calendar, kind: String, title: String) {
        val pending = pendingIntent(context, requestCode, kind, title)
        val alarm = context.getSystemService(AlarmManager::class.java)
        runCatching {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pending)
        }.getOrElse {
            alarm.set(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pending)
        }
    }

    private fun cancel(context: Context, requestCode: Int) {
        val pending = pendingIntent(context, requestCode, "", "")
        context.getSystemService(AlarmManager::class.java).cancel(pending)
    }

    private fun pendingIntent(context: Context, requestCode: Int, kind: String, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_KIND, kind)
                putExtra(ReminderReceiver.EXTRA_TITLE, title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}