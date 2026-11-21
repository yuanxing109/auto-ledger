package com.eta.autoledger.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android 11+ 的系统截图能力：无需 Root、不弹 MediaProjection 录屏授权，
 * 只需用户在无障碍设置里开启一次本服务。
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private suspend fun take(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // 8 秒兜底：系统回调可能永不返回，否则调用方协程与前台服务会永久挂起
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        buffer.close()
                        if (cont.isActive) cont.resume(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this@ScreenshotAccessibilityService),
                    callback,
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ScreenshotAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        suspend fun capture(): Bitmap? = instance?.take()
    }
}