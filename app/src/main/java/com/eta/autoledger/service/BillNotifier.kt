package com.eta.autoledger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import com.eta.autoledger.R
import com.eta.autoledger.data.NotifyBill
import java.util.Locale

/**
 * 自动记账结果的统一通知出口。
 *
 * 通知监听（[LedgerNotificationListener]）与短信广播（[SmsReceiver]）都走这里，
 * 保证同一个渠道、同一个通知 id、同一个「撤销」动作，用户不会看到两套提示。
 */
object BillNotifier {

    private const val TAG = "AutoLedger"

    /** 自动记账通知渠道 id。 */
    const val CHANNEL_AUTO = "auto_ledger_bill"

    /**
     * 提示「刚入账一笔」。用户关掉单笔通知（[AppPrefs.singleMuted]）时静默入库。
     */
    fun notifyAdded(context: Context, bill: NotifyBill) {
        val prefs = AppPrefs(context)
        if (prefs.singleMuted) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 用 externalId 而不是自增主键：addImported 是 REPLACE 插入，回填不了 id
        // requestCode 取 externalId 的 hash，否则多笔账单会互相覆写 EXTRA
        val undo = PendingIntent.getBroadcast(
            context,
            bill.externalId.hashCode(),
            Intent(context, UndoBillReceiver::class.java).apply {
                action = UndoBillReceiver.ACTION_UNDO
                putExtra(UndoBillReceiver.EXTRA_EXTERNAL_ID, bill.externalId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val amountText = "¥" + String.format(Locale.CHINA, "%,.2f", bill.amount)
        val sign = when (bill.type) {
            "income" -> "+"
            "other" -> " "
            else -> "-"
        }
        val kind = when (bill.type) {
            "income" -> "收入"
            "other" -> "其他"
            else -> "支出"
        }
        val builder = Notification.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("自动记账 $kind $sign$amountText")
            .setContentText("${bill.category} · ${bill.time} · ${bill.note}")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("${bill.source} · ${bill.category} · ${bill.time}\n${bill.note}"),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(prefs.persistentSingle)
            .addAction(Notification.Action.Builder(null, "撤销", undo).build())
        runCatching { manager.notify(UndoBillReceiver.NOTIFY_ID, builder.build()) }
            .onFailure { Log.w(TAG, "自动记账通知发送失败", it) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_AUTO,
            "自动记账",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "通知监听与短信自动生成的账单"
        }
        manager.createNotificationChannel(channel)
    }
}