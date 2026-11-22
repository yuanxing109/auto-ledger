package com.eta.autoledger.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.MainActivity
import com.eta.autoledger.R
import org.json.JSONObject

/**
 * 跨厂商「超级岛 / 灵动岛 / 实况窗」通知出口。
 *
 * 各家机制不同，这里做统一封装，且任何一步失败都自动降级为标准通知，绝不丢提醒：
 *  - 小米 HyperOS 2/3：标准 Notification + extras["miui.focus.param"](param_v2 JSON)，
 *    超级岛与「焦点通知」同一套数据接口（需在小米开发者平台开通并配置业务ID）。
 *  - vivo OriginOS：extras 里的 notification.superx.* Bundle（原子岛/原子通知，需申请权限）。
 *  - Android 16+：Notification.ProgressStyle + FLAG_PROMOTED_ONGOING（Live Updates 实况通知），
 *    这是通用标准通道，越来越多国产系统（含 HyperOS3 / ColorOS15 / OriginOS6）直接消费。
 *  - OPPO 流体云 / 荣耀灵动胶囊：官方走各自 SDK/模板协议，未配置时退回标准通知。
 */
object IslandNotifier {

    private const val TAG = "AutoLedger"

    enum class Vendor { XIAOMI, VIVO, OPPO, HONOR, SAMSUNG, OTHER }

    /** 当前设备厂商（用 Build.MANUFACTURER/BRAND，二者常有一个不准，取并集判断）。 */
    fun vendor(): Vendor {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        val s = "$m $b"
        return when {
            s.contains("xiaomi") || s.contains("redmi") || s.contains("poco") -> Vendor.XIAOMI
            s.contains("vivo") || s.contains("iqoo") -> Vendor.VIVO
            s.contains("oppo") || s.contains("oneplus") || s.contains("realme") || s.contains("oplus") -> Vendor.OPPO
            s.contains("honor") || s.contains("hihonor") -> Vendor.HONOR
            s.contains("samsung") -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    fun vendorLabel(v: Vendor): String = when (v) {
        Vendor.XIAOMI -> "小米 HyperOS"
        Vendor.VIVO -> "vivo OriginOS"
        Vendor.OPPO -> "OPPO ColorOS"
        Vendor.HONOR -> "荣耀 MagicOS"
        Vendor.SAMSUNG -> "三星 One UI"
        Vendor.OTHER -> "通用 Android"
    }

    /** Android 16（API 36）起支持 Live Updates 实况通知。 */
    fun supportsLiveUpdates(): Boolean = Build.VERSION.SDK_INT >= 36

    /** 系统是否允许本 App 发布「推广常驻通知（实况上岛）」。false 时系统只会显示普通通知。 */
    fun canPostPromoted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
    }

    data class Spec(
        /** 通知 id；同一 id 会覆盖更新同一条岛通知。 */
        val id: Int,
        val title: String,
        val text: String,
        /** 岛/锁屏上的一句话关键文本，留空则用 title。 */
        val critical: String = "",
        val channelId: String,
        val channelName: String,
        /** 常驻不可滑掉（实况窗/进行中类）。 */
        val ongoing: Boolean = false,
        /** 是否尝试厂商私有岛通道；false 时只走标准/Live Updates。 */
        val enableVendorIsland: Boolean = true,
        val category: String = Notification.CATEGORY_REMINDER,
    )

    /** 发一条尽量上岛的通知；不支持时等价于普通高优通知。 */
    fun deliver(context: Context, spec: Spec) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager, spec)

        val prefs = AppPrefs.get(context)
        val content = PendingIntent.getActivity(
            context,
            spec.id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(Notification.BigTextStyle().bigText(spec.text))
            .setContentIntent(content)
            .setAutoCancel(!spec.ongoing)
            .setOngoing(spec.ongoing)
            .setCategory(spec.category)
            .setShowWhen(true)

        // ① 通用 Android 16 实况通知（最稳，国产新系统普遍已适配）
        if (spec.enableVendorIsland && prefs.islandLiveUpdates && supportsLiveUpdates()) {
            applyLiveUpdate(builder, spec.title, spec.critical)
        }

        // ② 厂商私有扩展
        if (spec.enableVendorIsland) {
            applyVendorExtras(context, builder, spec.title, spec.text, content, prefs)
        }

        // 常驻实况通知必须 ongoing，否则 HyperOS 上只会一闪而过；给一个「知道了」用于手动清除
        if (spec.ongoing) {
            val dismiss = PendingIntent.getBroadcast(
                context,
                spec.id + 100_000,
                Intent(context, IslandDismissReceiver::class.java).apply {
                    action = IslandDismissReceiver.ACTION_DISMISS
                    putExtra(IslandDismissReceiver.EXTRA_ID, spec.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                    "知道了",
                    dismiss,
                ).build(),
            )
        }
        val notification = builder.build()
        // 请求提升为「推广常驻」；未获授权时系统会忽略，不影响普通展示
        if (supportsLiveUpdates() && spec.enableVendorIsland && prefs.islandLiveUpdates) {
            runCatching { notification.flags = notification.flags or Notification.FLAG_PROMOTED_ONGOING }
        }

        runCatching { manager.notify(spec.id, notification) }
            .onFailure {
                Log.w(TAG, "岛通知发送失败，降级普通通知", it)
                runCatching { manager.notify(spec.id, plain(context, spec, content)) }
            }
    }

    private fun ensureChannel(manager: NotificationManager, spec: Spec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(spec.channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(spec.channelId, spec.channelName, NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    // ---------------- Android 16 Live Updates ----------------

    private fun applyLiveUpdate(builder: Notification.Builder, title: String, critical: String) {
        val short = critical.ifBlank { title }.take(16)
        try {
            // 保留已设置的 BigTextStyle（属于"可提升"样式之一），不强行套进度条
            builder.setShortCriticalText(short)
            builder.setRequestPromotedOngoing(true)
            builder.setOnlyAlertOnce(true)
            // 字面量兜底（常量在 36.1 才公开；HyperOS 3.1+/ColorOS16 靠这些键识别实况）
            // 注意：不能 colorized，系统会因此取消实时提升资格
            val extras = Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putCharSequence("android.shortCriticalText", short)
            }
            builder.addExtras(extras)
        } catch (t: Throwable) {
            Log.w(TAG, "Live Updates 设置失败，忽略", t)
        }
    }

    // ---------------- 小米 HyperOS 超级岛 / 焦点通知 ----------------

    private fun applyXiaomi(context: Context, builder: Notification.Builder, title: String, text: String, prefs: AppPrefs) {
        // 业务ID需在小米开发者平台开通「超级岛/焦点通知」后获得；未配置则不发送私有参数，
        // 避免非法 param_v2 导致通知被系统丢弃。
        val business = prefs.miuiBusinessId.trim()
        if (business.isEmpty()) return
        try {
            val textInfo = JSONObject().apply {
                put("frontTitle", title)
                put("title", title)
                put("content", text)
            }
            val bigArea = JSONObject().apply {
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("textInfo", textInfo)
                })
                put("picInfo", PIC_KEY)
            }
            val smallArea = JSONObject().apply { put("picInfo", PIC_KEY) }
            val paramIsland = JSONObject().apply {
                put("islandProperty", 2) // 2=常驻
                put("bigIslandArea", bigArea)
                put("smallIslandArea", smallArea)
                put("shareData", JSONObject().apply { put("title", text.take(24)) })
            }
            val baseInfo = JSONObject().apply {
                put("title", title)
                put("content", text)
                put("type", 1)
            }
            val paramV2 = JSONObject().apply {
                put("business", business)
                put("ticker", title)
                put("aodTitle", title)
                put("param_island", paramIsland)
                put("baseInfo", baseInfo)
            }
            val root = JSONObject().apply { put("param_v2", paramV2) }

            val extras = Bundle().apply {
                putString("miui.focus.param", root.toString())
                val pics = Bundle().apply {
                    putParcelable(PIC_KEY, Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
                }
                putBundle("miui.focus.pics", pics)
            }
            builder.addExtras(extras)
        } catch (t: Throwable) {
            Log.w(TAG, "小米超级岛参数构建失败，降级", t)
        }
    }

    // ---------------- vivo OriginOS 原子岛 / 原子通知 ----------------

    private fun applyVivo(builder: Notification.Builder, title: String, text: String, click: PendingIntent) {
        try {
            val extras = Bundle().apply {
                putString("notification.superx.operation", "1")   // 新增
                putBoolean("notification.superx.showNotify", true)
                putString("notification.superx.template", "1")    // 基础模板
                putParcelable("notification.superx.clickResp", click)
                putString("notification.superx.scene", "1")
                val base = Bundle().apply {
                    putString("notification.superx.baseInfos.business", "1")
                    putString("notification.superx.baseInfos.showTime", System.currentTimeMillis().toString())
                    putString("notification.superx.baseInfos.np", title)
                    putCharSequence("notification.superx.baseInfos.title", title)
                    putCharSequence("notification.superx.baseInfos.content", text)
                }
                putBundle("notification.superx.baseInfos", base)
            }
            builder.addExtras(extras)
        } catch (t: Throwable) {
            Log.w(TAG, "vivo 原子岛参数构建失败，降级", t)
        }
    }

    /** 给外部（如单笔自动记账）自带的通知装饰岛参数；发送仍由调用方负责。 */
    fun decorate(
        context: Context,
        builder: Notification.Builder,
        title: String,
        text: String,
        critical: String,
        click: PendingIntent,
        enableVendorIsland: Boolean,
    ) {
        if (!enableVendorIsland) return
        val prefs = AppPrefs.get(context)
        if (prefs.islandLiveUpdates && supportsLiveUpdates()) applyLiveUpdate(builder, title, critical)
        applyVendorExtras(context, builder, title, text, click, prefs)
    }

    private fun applyVendorExtras(
        context: Context,
        builder: Notification.Builder,
        title: String,
        text: String,
        click: PendingIntent,
        prefs: AppPrefs,
    ) {
        when (vendor()) {
            Vendor.XIAOMI -> applyXiaomi(context, builder, title, text, prefs)
            Vendor.VIVO -> applyVivo(builder, title, text, click)
            else -> Unit // OPPO/HONOR 需官方 SDK，退回标准通知
        }
    }

    private fun plain(context: Context, spec: Spec, content: PendingIntent): Notification =
        Notification.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(Notification.BigTextStyle().bigText(spec.text))
            .setContentIntent(content)
            .setAutoCancel(!spec.ongoing)
            .setOngoing(spec.ongoing)
            .build()

    private const val PIC_KEY = "miui.focus.pic"
}
