package com.eta.autoledger.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import com.eta.autoledger.R
import androidx.core.content.ContextCompat
import com.eta.autoledger.screenshot.ScreenshotCaptureService
import com.eta.autoledger.screenshot.ScreenshotRequestActivity

/**
 * 控制中心「截屏记账」磁贴。未配置 AI 时把用户带到设置页；已配置时延迟 1 秒再截屏，
 * 等控制中心面板收起，避免把下拉面板本身截进去。
 */
class ScreenshotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "截屏记账"
            icon = Icon.createWithResource(this@ScreenshotTileService, R.drawable.ic_tile_screenshot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点一下截当前屏幕"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = AppPrefs(this)
        val needsSetup = !prefs.screenshotEnabled || prefs.aiBaseUrl.isBlank() || prefs.aiModel.isBlank()
        if (!needsSetup) {
            Toast.makeText(this, "1 秒后截屏，请停在要识别的页面", Toast.LENGTH_SHORT).show()
            // 控制中心面板收起要几百毫秒，立刻截屏会把下拉面板本身截进去
            Handler(Looper.getMainLooper()).postDelayed({ startCapture() }, CAPTURE_DELAY_MS)
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

    /** 面板收起后再拉起前台服务，截到的就是面板下方那一屏。 */
    private fun startCapture() {
        val service = Intent(this, ScreenshotCaptureService::class.java).apply {
            action = ScreenshotCaptureService.ACTION_CAPTURE_ROOT
        }
        runCatching { ContextCompat.startForegroundService(this, service) }
            .onFailure {
                Toast.makeText(this, "启动截屏失败：${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private companion object {
        const val CAPTURE_DELAY_MS = 1_000L
    }
}