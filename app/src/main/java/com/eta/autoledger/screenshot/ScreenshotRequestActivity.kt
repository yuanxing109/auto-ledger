package com.eta.autoledger.screenshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** 透明的授权中转页：拿到 MediaProjection 授权后立刻启动抓屏服务。 */
class ScreenshotRequestActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenshotCaptureService::class.java).apply {
                action = ScreenshotCaptureService.ACTION_CAPTURE
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotCaptureService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(this, "未授权截屏", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开关关闭时不必弹出系统录屏授权框，直接退出。
        if (!com.eta.autoledger.AppPrefs.get(this).screenshotEnabled) {
            Toast.makeText(this, "截屏记账已在设置中关闭", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startProjection()
        }
    }

    private fun startProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}