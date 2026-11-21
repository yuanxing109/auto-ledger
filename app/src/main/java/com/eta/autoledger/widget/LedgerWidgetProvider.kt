package com.eta.autoledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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

/**
 * 2×2「今日收支」小部件。
 *
 * 数据只跑几条聚合 SQL（见 [LedgerRepository.widgetTotals]），`goAsync()` 挪到 IO 协程，
 * 主线程只做 RemoteViews 拼装。宽度超过 [WIDE_MIN_WIDTH_DP] 时自动换成 4×2 布局，
 * 所以用户把小部件拉大也不会出现大片空白。
 */
open class LedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val totals = LedgerRepository.widgetTotals(appContext)
                appWidgetIds.forEach { id ->
                    val wide = widthOf(appWidgetManager, id) ?: prefersWide
                    safeRender(appContext, appWidgetManager, id, totals, wide)
                }
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val totals = LedgerRepository.widgetTotals(appContext)
                val wide = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= WIDE_MIN_WIDTH_DP
                safeRender(appContext, appWidgetManager, appWidgetId, totals, wide)
            } finally {
                pending?.finish()
            }
        }
    }

    /** 这个 provider 在拿不到尺寸时的默认版式；宽版子类覆盖为 true。 */
    protected open val prefersWide: Boolean get() = false

    private fun widthOf(manager: AppWidgetManager, widgetId: Int): Boolean? = runCatching {
        manager.getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= WIDE_MIN_WIDTH_DP
    }.getOrNull()

    companion object {
        private const val TAG = "AutoLedger"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 版式阈值。2 列宽在多数 launcher 约 170~190dp、4 列约 250~330dp，
         * 取 200dp 把 2×2 / 2×3 留在小版、3 列以上换宽版，避免临界抖动。
         */
        private const val WIDE_MIN_WIDTH_DP = 200

        /** 两种规格共用一份数据与一套拼装逻辑。 */
        fun render(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            totals: WidgetTotals,
            wide: Boolean,
        ) {
            val views = if (wide) {
                RemoteViews(context.packageName, R.layout.widget_ledger_wide).apply {
                    setTextViewText(
                        R.id.widget_date,
                        SimpleDateFormat("MM月dd日", Locale.CHINA).format(Calendar.getInstance().time),
                    )
                    setTextViewText(R.id.widget_month_expense, money(totals.monthExpense))
                    setTextViewText(R.id.widget_month_income, money(totals.monthIncome))
                    setTextViewText(R.id.widget_today_expense, "今日支出 ${money(totals.todayExpense)}")
                    setTextViewText(R.id.widget_today_income, "今日收入 ${money(totals.todayIncome)}")
                    setTextViewText(R.id.widget_month_count, "本月 ${totals.monthCount} 笔")
                    onClickOn(R.id.widget_title, openApp(context))
                }
            } else {
                RemoteViews(context.packageName, R.layout.widget_ledger).apply {
                    setTextViewText(
                        R.id.widget_date,
                        SimpleDateFormat("MM/dd", Locale.CHINA).format(Calendar.getInstance().time),
                    )
                    setTextViewText(R.id.widget_amount, money(totals.todayExpense))
                    setTextViewText(R.id.widget_month_expense, "支出 ${money(totals.monthExpense)}")
                    setTextViewText(R.id.widget_month_income, "收入 ${money(totals.monthIncome)}")
                    onClickOn(R.id.widget_amount, openApp(context))
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        /**
         * 宽版渲染失败时回退小版。
         *
         * RemoteViews 的 id 与布局不对应会在 launcher 侧才炸，这里兜住的是资源/拼装类异常；
         * id 一致性靠 [render] 内两个分支各自只引用本版布局里的 id 来保证。
         */
        fun safeRender(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            totals: WidgetTotals,
            wide: Boolean,
        ) {
            if (!wide) {
                render(context, manager, widgetId, totals, false)
                return
            }
            runCatching { render(context, manager, widgetId, totals, true) }
                .onFailure {
                    Log.w(TAG, "宽版小部件渲染失败，回退 2×2：${it.message}")
                    render(context, manager, widgetId, totals, false)
                }
        }

        private fun RemoteViews.onClickOn(viewId: Int, intent: PendingIntent) {
            setOnClickPendingIntent(viewId, intent)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun money(value: Double): String = "%,.2f".format(value)

        /** 任何写操作后刷新两种规格的全部实例。 */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            components(context).forEach { component ->
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return@forEach
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    setComponent(component)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        /** 请求钉到桌面：[wide] 决定 4×2 还是 2×2。返回是否成功把请求交给桌面（不支持时返回 false）。 */
        fun requestPin(context: Context, wide: Boolean): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            val target = if (wide) LedgerWidgetWideProvider::class.java else LedgerWidgetProvider::class.java
            if (!manager.isRequestPinAppWidgetSupported) return false
            return runCatching {
                manager.requestPinAppWidget(ComponentName(context, target), Bundle(), null)
            }.getOrDefault(false)
        }

        fun components(context: Context): List<ComponentName> = listOf(
            ComponentName(context, LedgerWidgetProvider::class.java),
            ComponentName(context, LedgerWidgetWideProvider::class.java),
        )
    }
}

/** 4×2「本月概览」：只改默认版式，刷新与拼装复用基类。 */
class LedgerWidgetWideProvider : LedgerWidgetProvider() {
    override val prefersWide: Boolean get() = true
}