package com.eta.autoledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eta.autoledger.MainActivity
import com.eta.autoledger.R
import com.eta.autoledger.data.LedgerRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class LedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val entries = LedgerRepository.load(context)
        val now = Calendar.getInstance()
        val monthExpense = entries
            .filter { it.type != "income" && sameMonth(it.time, now) }
            .sumOf { it.amount }
        val monthIncome = entries
            .filter { it.type == "income" && sameMonth(it.time, now) }
            .sumOf { it.amount }
        val todayExpense = entries
            .filter { it.type != "income" && sameDay(it.time, now) }
            .sumOf { it.amount }

        val views = RemoteViews(context.packageName, R.layout.widget_ledger)
        views.setTextViewText(R.id.widget_date, SimpleDateFormat("MM月dd日", Locale.CHINA).format(now.time))
        views.setTextViewText(R.id.widget_today, "%.2f".format(todayExpense))
        views.setTextViewText(
            R.id.widget_month,
            "本月支出 %.2f · 收入 %.2f".format(monthExpense, monthIncome),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_title, open)
        manager.updateAppWidget(widgetId, views)
    }

    private fun sameDay(raw: String, now: Calendar): Boolean {
        val cal = LedgerRepository.parseTime(raw) ?: return false
        return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
            cal.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)
    }

    private fun sameMonth(raw: String, now: Calendar): Boolean {
        val cal = LedgerRepository.parseTime(raw) ?: return false
        return cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }

    companion object {
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LedgerWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, LedgerWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        fun requestPin(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context.packageName, LedgerWidgetProvider::class.java.name)
            runCatching { manager.requestPinAppWidget(component, android.os.Bundle(), null) }
        }
    }
}