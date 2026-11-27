package com.eta.autoledger.ui.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 报表图片分享的文件与 Intent 编排。
 *
 * cache 文件先完整写入磁盘，再交给 FileProvider。分享目标可能延迟读取 URI，
 * 因此清理采用“延时删除 + Activity 销毁兜底”的方式，而不是启动 chooser 后立刻删除。
 */
internal object ReportShareExporter {

    private const val FILE_PREFIX = "report_share_"
    private const val DIRECTORY_NAME = "report-shares"
    private const val CLEANUP_DELAY_MS = 10 * 60 * 1000L
    private const val STALE_AFTER_MS = 10 * 60 * 1000L
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun share(context: Context, image: ImageBitmap) {
        val file = withContext(Dispatchers.IO) {
            writePng(context, image.asAndroidBitmap())
        }
        val send = sendIntent(context, file)
        withContext(Dispatchers.Main.immediate) {
            val chooser = Intent.createChooser(send, "分享报表").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
        scheduleCleanup(file)
    }

    internal fun fileUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    internal fun sendIntent(context: Context, file: File): Intent {
        val uri = fileUri(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AutoLedger 报表", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun writePng(context: Context, bitmap: Bitmap): File {
        require(!bitmap.isRecycled) { "分享图片已释放" }
        val unique = System.currentTimeMillis().toString() + "_" +
            UUID.randomUUID().toString().take(8)
        val dir = File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
        val target = File(dir, FILE_PREFIX + unique + ".png")
        if (target.exists()) target.delete()
        return try {
            FileOutputStream(target).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "PNG 编码失败"
                }
            }
            check(target.length() > 0L) { "PNG 文件为空" }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        File(context.cacheDir, DIRECTORY_NAME)
            .listFiles { file -> file.name.startsWith(FILE_PREFIX) && file.name.endsWith(".png") }
            .orEmpty()
            .filter { file -> now - file.lastModified() >= STALE_AFTER_MS }
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun scheduleCleanup(file: File) {
        cleanupScope.launch {
            delay(CLEANUP_DELAY_MS)
            val expired = file.exists() &&
                System.currentTimeMillis() - file.lastModified() >= CLEANUP_DELAY_MS
            if (expired) runCatching { file.delete() }
        }
    }
}
