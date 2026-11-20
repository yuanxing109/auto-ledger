package com.eta.autoledger.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import androidx.core.content.ContextCompat
import com.eta.autoledger.screenshot.ScreenshotCaptureService
import com.eta.autoledger.screenshot.ScreenshotRequestActivity

/**
 * 控制中心截屏记账入口。当前只负责把用户带到 AI 配置/截屏授权流程；
 * 真正的 MediaProjection 截屏与 AI 解析在下一步接入。
 */
class ScreenshotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "截屏记账"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = AppPrefs(this)
        val needsSetup = !prefs.screenshotEnabled || prefs.aiBaseUrl.isBlank() || prefs.aiModel.isBlank()
        if (!needsSetup) {
            Toast.makeText(this, "正在截屏并解析…", Toast.LENGTH_SHORT).show()
            val service = Intent(this, ScreenshotCaptureService::class.java).apply {
                action = ScreenshotCaptureService.ACTION_CAPTURE_ROOT
            }
            ContextCompat.startForegroundService(this, service)
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_OPEN_AI_SETTINGS, true)
        }
        Toast.makeText(this, "请先开启截屏记账并配置 AI", Toast.LENGTH_SHORT).show()
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}