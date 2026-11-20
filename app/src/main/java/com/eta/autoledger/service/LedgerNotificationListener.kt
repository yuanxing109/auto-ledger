package com.eta.autoledger.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知监听入口。当前骨架只负责把通知内容打到 logcat，
 * 后续在这里接：包名白名单 -> 金额/关键词解析 -> 去重 -> Room 入库。
 */
class LedgerNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        Log.i(
            TAG,
            "pkg=${sbn.packageName} key=${sbn.key} title=$title text=$text bigText=$bigText",
        )
    }

    companion object {
        private const val TAG = "AutoLedger"
    }
}