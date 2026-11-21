package com.eta.autoledger.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后重建提醒。
 *
 * AlarmManager 里的闹钟在设备重启或应用被整体替换时会被系统清空，
 * 若不在这些时点重排，每日/每周/每月提醒会永久失效，直到用户再次打开 App。
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> ReminderScheduler.rescheduleAll(context.applicationContext)
        }
    }
}
