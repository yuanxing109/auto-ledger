package com.eta.autoledger

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 敏感字段（AI API Key、WebDAV 应用密码）的加密存储。
 *
 * 存在的理由：这些是**凭据**，不是普通配置。明文放在 `auto_ledger.xml` 里，
 * 任何拿到 `/data/data/com.eta.autoledger/shared_prefs/` 的路径（root、adb backup、
 * 有漏洞的备份/文件分享组件）都能直接读走，而且用户往往复用同一个 key / 密码。
 *
 * 独立文件 `auto_ledger_secure.xml` 而不是把普通字段一起加密：
 * - 加密库初始化要走 Android Keystore，有失败可能（部分机型 Keystore 损坏、
 *   用户改过锁屏凭据、厂商裁剪）。把它和 60 多个普通开关隔离，
 *   加密层挂掉时账目、主题、开关这些照样能读写，只是敏感字段退化为不可用。
 * - 迁移边界清晰：普通文件里删掉旧字段即可，不必整体搬迁。
 *
 * [get] 返回 null 表示加密层不可用（初始化异常）。调用方必须做降级处理，
 * 不能因为拿不到加密存储就崩——记账 App 因为 Keystore 异常打不开是不可接受的。
 */
internal object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val FILE_NAME = "auto_ledger_secure"

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var failed = false

    /** 预热是否已经派发过（每个进程只需一次）。 */
    @Volatile
    private var warmed = false

    /**
     * 后台预热：首次 [get] 要建 MasterKey、打开加密库，是几十毫秒级的
     * Keystore + 磁盘操作。放在启动时的后台线程做掉，别等用户点进
     * 设置页才在主线程付这笔账。
     *
     * 注意这只是"提前"，不是"异步化"：如果预热还没完成主线程就调 [get]，
     * 仍会在 synchronized 上等到它结束。但预热从进程启动就开始跑，
     * 正常情况下用户走到设置页时早已缓存好。
     */
    fun warmUp(context: Context) {
        if (warmed) return
        warmed = true
        val app = context.applicationContext
        Thread({ get(app) }, "secure-prefs-warmup").apply { isDaemon = true }.start()
    }

    /**
     * 取加密 SharedPreferences；初始化失败返回 null 并记住失败（不反复重试，
     * 否则每次读设置页都要再撞一次 Keystore 异常）。
     */
    @Suppress("DEPRECATION")
    fun get(context: Context): SharedPreferences? {
        cached?.let { return it }
        if (failed) return null
        synchronized(this) {
            cached?.let { return it }
            if (failed) return null
            return runCatching {
                val app = context.applicationContext
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onFailure {
                // 只记日志不抛：加密不可用时由 AppPrefs 降级，App 仍要能用
                Log.w(TAG, "加密存储初始化失败，敏感字段降级为明文存储", it)
                failed = true
            }.getOrNull()?.also { cached = it }
        }
    }
}
