package com.eta.autoledger.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 WebDAV 客户端。
 *
 * 只做备份需要的四件事：建目录、上传、下载、列目录。
 * 坚果云、群晖、Nextcloud、TeraCloud 都支持这套最小集合。
 * 所有方法都是阻塞的，调用方负责放在 IO 线程。
 */
object WebDavClient {

    private const val TAG = "AutoLedger"
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 60_000

    /** 一次备份/恢复的目标；`dir` 是备份目录，备份文件放在里面。 */
    data class Target(val dir: String, val user: String, val pass: String) {
        val normalized: String get() = dir.trim().trimEnd('/')
        fun file(name: String): String = "$normalized/$name"
    }

    /** 把设置项转成 Target，未配置完整返回 null。 */
    fun target(prefs: com.eta.autoledger.AppPrefs): Target? =
        if (!prefs.davConfigured()) null else Target(prefs.davUrl, prefs.davUser, prefs.davPass)

    /**
     * 连通性检查：建目录 + 写探针 + 删探针。
     * 失败抛 [IOException]，消息可直接展示给用户。
     */
    fun test(target: Target): String {
        ensureDir(target)
        val probe = target.file(".autoledger_probe")
        put(target, probe, "ok".toByteArray())
        delete(target, probe)
        return "连接正常"
    }

    /** 目录不存在就 MKCOL；405（已存在）/301 都当成功。 */
    fun ensureDir(target: Target) {
        val base = target.normalized
        val schemeEnd = base.indexOf("://").let { if (it < 0) return else it + 3 }
        val parts = base.substring(schemeEnd).split('/')
        if (parts.size <= 1) return
        var prefix = base.substring(0, schemeEnd)
        for (i in 1 until parts.size) {
            if (parts[i].isEmpty()) continue
            prefix = "$prefix/${parts[i]}"
            val code = request(target, "MKCOL", prefix, null)
            // 201 新建成功；405 已存在；409 父目录缺失
            if (code !in 200..299 && code != 405) Log.d(TAG, "MKCOL $prefix -> $code")
        }
    }

    fun put(target: Target, url: String, bytes: ByteArray) {
        val code = request(target, "PUT", url, bytes)
        if (code !in 200..299) throw IOException("上传失败：HTTP $code")
    }

    /** 上传到目录下的固定文件名。 */
    fun upload(target: Target, name: String, bytes: ByteArray) = put(target, target.file(name), bytes)

    /** 文件不存在返回 null。 */
    fun download(target: Target, name: String): ByteArray? {
        val conn = open(target, "GET", target.file(name))
        return try {
            val code = conn.responseCode
            when {
                code == 404 -> null
                code in 200..299 -> conn.inputStream.use { it.readBytes() }
                else -> throw IOException("下载失败：HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 列目录里的文件名（不含路径）。 */
    fun list(target: Target): List<String> {
        val conn = open(target, "PROPFIND", target.normalized)
        conn.setRequestProperty("Depth", "1")
        conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        conn.doOutput = true
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: return emptyList()
            if (code !in 200..299) throw IOException("列目录失败：HTTP $code")
            Regex("<(?:d:|D:)?href>([^<]+)</(?:d:|D:)?href>")
                .findAll(text)
                .map { it.groupValues[1].trim().trimEnd('/').substringAfterLast('/') }
                .filter { it.isNotEmpty() && it != target.normalized.substringAfterLast('/') }
                .distinct()
                .toList()
        } finally {
            conn.disconnect()
        }
    }

    fun delete(target: Target, name: String) {
        val code = request(target, "DELETE", target.file(name), null)
        if (code !in 200..299 && code != 404) Log.d(TAG, "DELETE 失败 HTTP $code")
    }

    // ---------------- 内部 ----------------

    private fun request(target: Target, method: String, url: String, body: ByteArray?): Int {
        val conn = open(target, method, url)
        return try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            } else {
                conn.setRequestProperty("Content-Length", "0")
            }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun open(target: Target, method: String, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.instanceFollowRedirects = false
        val token = android.util.Base64.encodeToString(
            "${target.user}:${target.pass}".toByteArray(),
            android.util.Base64.NO_WRAP,
        )
        conn.setRequestProperty("Authorization", "Basic $token")
        return conn
    }
}