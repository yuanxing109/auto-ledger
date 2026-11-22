package com.eta.autoledger.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 点击「知道了」时取消对应的常驻实况通知。 */
class IslandDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id != -1) {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.eta.autoledger.action.ISLAND_DISMISS"
        const val EXTRA_ID = "island_notify_id"
    }
}
