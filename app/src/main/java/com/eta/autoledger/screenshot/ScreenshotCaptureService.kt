package com.eta.autoledger.screenshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import com.eta.autoledger.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenshotCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskLock = Any()
    private var captureInProgress = false
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 总开关最先判：关掉「截屏记账」后，无论是磁贴、通知还是无障碍快捷方式
        // 触发，都在这里直接退出——不建前台通知、不抓图、不调 AI。
        // 原来只在磁贴里判 needsSetup，主链路完全不看这个开关，导致关了照样记账。
        if (!AppPrefs.get(this).screenshotEnabled) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val progress = progressNotification("正在截屏并交给 AI 解析…")
        val isRootCapture = intent?.action == ACTION_CAPTURE_ROOT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isRootCapture) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(PROGRESS_ID, progress, type)
        } else {
            startForeground(PROGRESS_ID, progress)
        }
        if (intent?.action == ACTION_CAPTURE_ROOT) {
            if (!claimCapture(startId)) return START_NOT_STICKY
            scope.launch {
                try {
                    // 磁贴点击后面板仍会播放收起动画；先进入前台服务再等待，既不会阻塞主线程，
                    // 也不会因 TileService 被系统解绑而丢失延迟任务。
                    delay(TILE_CAPTURE_SETTLE_DELAY_MS)
                    val shot = ScreenshotAccessibilityService.capture()
                    if (shot == null) {
                        postRootFallback("请先在 设置 → 功能管理 → 无障碍截图 中开启服务")
                    } else {
                        processBitmap(shot)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    finishWithError("截屏失败：${failure.message ?: failure.javaClass.simpleName}")
                } finally {
                    finishCapture()
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_CAPTURE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!claimCapture(startId)) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            finishWithError("没有拿到截屏授权")
            finishCapture()
            return START_NOT_STICKY
        }
        scope.launch {
            runCatching { captureAndParse(resultCode, data) }
                .onFailure {
                    if (it !is CancellationException) {
                        finishWithError("截屏或解析失败：${it.message ?: it.javaClass.simpleName}")
                    }
                }
            finishCapture()
        }
        return START_NOT_STICKY
    }

    private fun captureAndParse(resultCode: Int, data: Intent) {
        val prefs = AppPrefs.get(this)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
            ?: run {
                finishWithError("MediaProjection 初始化失败")
                return
            }

        val metrics = screenMetrics()
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {}
        }, Handler(Looper.getMainLooper()))

        val virtualDisplay: VirtualDisplay? = projection.createVirtualDisplay(
            "auto-ledger-shot",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * metrics.widthPixels
                val raw = createBitmap(
                    metrics.widthPixels + rowPadding / plane.pixelStride,
                    metrics.heightPixels,
                    Bitmap.Config.ARGB_8888,
                )
                raw.copyPixelsFromBuffer(plane.buffer)
                bitmap = Bitmap.createBitmap(raw, 0, 0, metrics.widthPixels, metrics.heightPixels)
                raw.recycle()
            } finally {
                image.close()
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        val captured = latch.await(4, TimeUnit.SECONDS) && bitmap != null
        runCatching { virtualDisplay?.release() }
        runCatching { reader.close() }
        runCatching { projection.stop() }

        val shot = bitmap
        if (!captured || shot == null) {
            finishWithError("截屏失败，请手动上传（金融类 App 可能禁止截屏）")
            return
        }

        processBitmap(shot)
    }

    private fun processBitmap(shot: Bitmap) {
        val prefs = AppPrefs.get(this)
        val bytes = ByteArrayOutputStream().use { out ->
            shot.compress(Bitmap.CompressFormat.PNG, 85, out)
            out.toByteArray()
        }
        shot.recycle()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        if (prefs.aiBaseUrl.isBlank() || prefs.aiModel.isBlank()) {
            finishWithResult(null, "截屏成功，但还没有配置 AI 模型")
            return
        }

        val parsed = runCatching {
            VisionHelper.callVisionModel(prefs, base64, VisionHelper.MIME_PNG)
        }.getOrElse {
            finishWithResult(null, "AI 解析失败：${it.message ?: it.javaClass.simpleName}")
            return
        }
        finishWithResult(parsed, null)
    }

    private fun postRootFallback(message: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_RESULT,
                "记账识别结果",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val a11y = PendingIntent.getActivity(
            this,
            11,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("截屏记账")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "开启无障碍截图", a11y)
            .build()
        runCatching { manager.notify(9004, notification) }
    }

    private fun finishWithError(message: String) {
        notifyResult(null, message)
    }

    private fun finishWithResult(entry: ParsedEntry?, error: String?) {
        notifyResult(entry, error)
    }

    private fun claimCapture(startId: Int): Boolean = synchronized(taskLock) {
        latestStartId = startId
        if (captureInProgress) {
            false
        } else {
            captureInProgress = true
            true
        }
    }

    private fun finishCapture() {
        val stopId = synchronized(taskLock) {
            if (!captureInProgress) return@synchronized null
            captureInProgress = false
            latestStartId
        } ?: return
        stopSelfResult(stopId)
    }

    private fun notifyResult(entry: ParsedEntry?, error: String?) {
        VisionHelper.postResult(this, entry, error)
    }
    private fun progressNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("截屏记账")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "截屏处理中", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "记账识别结果", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun screenMetrics(): DisplayMetrics {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = density
            }
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CAPTURE = "com.eta.autoledger.CAPTURE"
        const val ACTION_CAPTURE_ROOT = "com.eta.autoledger.CAPTURE_ROOT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val CHANNEL_PROGRESS = "screenshot_progress"
        const val CHANNEL_RESULT = "screenshot_result"
        const val PROGRESS_ID = 9001
        const val RESULT_ID = 9002
        private const val TILE_CAPTURE_SETTLE_DELAY_MS = 700L
    }
}

