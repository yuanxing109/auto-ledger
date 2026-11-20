package com.eta.autoledger.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.TimeUnit

/** 有 Root 时直接调用 screencap，避免 MediaProjection 的“录屏/投放”授权弹窗。 */
object RootScreenshot {

    fun capture(context: Context): Bitmap? {
        val file = File(context.cacheDir, "root_shot.png")
        if (file.exists()) file.delete()
        return runCatching {
            val process = ProcessBuilder("su", "-c", "screencap -p ${file.absolutePath}")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(6, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            if (file.exists() && file.length() > 0) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                file.delete()
                bitmap
            } else {
                null
            }
        }.getOrNull()
    }

    fun hasRoot(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            return false
        }
        process.inputStream.bufferedReader().readText().trim() == "0"
    }.getOrDefault(false)
}