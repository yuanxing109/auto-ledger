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
import com.eta.autoledger.data.WidgetTotals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class LedgerWidgetProvider : AppWidgetProvider() {

    /**
     * 以前这里在主线程 `LedgerRepository.load()` 全表扫一遍再在内存里 filter/sum，
     * 账目一多就是 ANR 风险。现在走两条 SUM 区间查询，并 goAsync() 挪到 IO 协程。
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val totals = LedgerRepository.widgetTotals(appContext)
                appWidgetIds.forEach { updateWidget(appContext, appWidgetManager, it, totals) }
            } finally {
                pending?.finish()
            }
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        totals: WidgetTotals,
    ) {
        val now = Calendar.getInstance()
        val views = RemoteViews(context.packageName, R.layout.widget_ledger)
        views.setTextViewText(R.id.widget_date, SimpleDateFormat("MM月dd日", Locale.CHINA).format(now.time))
        views.setTextViewText(R.id.widget_today, "%.2f".format(totals.todayExpense))
        views.setTextViewText(
            R.id.widget_month,
            "本月支出 %.2f · 收入 %.2f".format(totals.monthExpense, totals.monthIncome),
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

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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