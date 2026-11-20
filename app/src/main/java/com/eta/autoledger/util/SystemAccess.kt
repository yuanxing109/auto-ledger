package com.eta.autoledger.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.eta.autoledger.screenshot.ScreenshotAccessibilityService
import com.eta.autoledger.screenshot.VisionHelper
import com.eta.autoledger.service.BillNotifier
import com.eta.autoledger.service.LedgerNotificationListener

/**
 * 系统级权限/开关的探测与跳转。
 *
 * 这些开关没有 API 能"申请"，只能检测状态 + 把用户送到对应的系统页面，
 * 所以统一收在这里，供设置页的「系统权限与保活」一屏使用。
 */
object SystemAccess {

    /** 入库通知渠道（[com.eta.autoledger.service.BillNotifier]）。 */
    const val CHANNEL_AUTO = "auto_ledger_bill"

    /** 截屏进度与结果渠道（[com.eta.autoledger.screenshot.ScreenshotCaptureService]）。 */
    const val CHANNEL_SHOT_PROGRESS = "screenshot_progress"
    const val CHANNEL_SHOT_RESULT = "screenshot_result"

    /** 日报/周报/月报渠道（[com.eta.autoledger.reminder.ReminderReceiver]）。 */
    const val CHANNEL_REMINDER = "ledger_reminder"

    /** 模块激活标志，由 xposed/LedgerModule 在 system_server 里写入。 */
    private const val MODULE_FLAG = "autoledger_module_active"

    /**
     * `Settings.EXTRA_APP_UID`（"android.provider.extra.APP_UID"）在部分 SDK 上不可直接引用，
     * 但 MIUI / HyperOS 的通知设置页缺了它会空指针崩溃，所以用字面量补上。
     */
    private const val EXTRA_APP_UID = "android.provider.extra.APP_UID"

    // ---------------- 状态探测 ----------------

    /** 通知使用权：决定通知监听这条采集通道是否工作。 */
    fun notificationListenerGranted(context: Context): Boolean =
        LedgerNotificationListener.isEnabled(context)

    /** 无障碍：决定「无障碍截图」与无障碍兜底采集是否可用。 */
    fun accessibilityGranted(context: Context): Boolean {
        val target = ComponentName(context, ScreenshotAccessibilityService::class.java)
        val shortFlat = "${context.packageName}/.${ScreenshotAccessibilityService::class.java.simpleName}"
        return enabledAccessibilityServices(context).any { raw ->
            raw == shortFlat || ComponentName.unflattenFromString(raw) == target
        }
    }

    /** 短信运行时权限：决定 SMS_RECEIVED 广播是否还能收到。 */
    fun smsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** 通知总开关（含 Android 13+ 的 POST_NOTIFICATIONS 与用户手动关闭）。 */
    fun notificationGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 电池优化白名单：false 表示应用被省电策略约束。 */
    fun batteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 精确闹钟（Android 12+ 用户可单独撤销，撤销后 [android.app.AlarmManager] 会退化为不精确）。 */
    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarm.canScheduleExactAlarms()
    }

    /** 某个通知渠道是否仍处于开启状态（IMPORTANCE_NONE = 用户关掉了）。 */
    fun channelEnabled(context: Context, channelId: String): Boolean {
        if (!notificationGranted(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** LSPosed 模块是否已激活（读 system_server 写入的 Secure 标志）。 */
    fun moduleActive(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, MODULE_FLAG, 0) == 1
    }.getOrDefault(false)

    // ---------------- 跳转 ----------------

    fun openNotificationListener(context: Context) = LedgerNotificationListener.openSettings(context)

    fun openAccessibility(context: Context) = start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** 电池优化白名单页；ROM 不认直达 Intent 时退到电池优化列表。 */
    fun openBattery(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        if (!start(context, direct)) {
            start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val direct = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
            if (start(context, direct)) return
        }
        openAppDetails(context)
    }

    /** 渠道是否已创建。不存在时不能跳「渠道详情」页——MIUI/HyperOS 会直接崩。 */
    fun channelExists(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.getNotificationChannel(channelId) != null }.getOrDefault(false)
    }

    fun openChannel(context: Context, channelId: String) {
        // 先把两条业务的渠道建出来，再决定跳哪里
        runCatching {
            BillNotifier.ensureChannel(context)
            VisionHelper.ensureChannel(context)
        }
        val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(EXTRA_APP_UID, context.applicationInfo.uid)
        if (!channelExists(context, channelId)) {
            if (!start(context, appIntent)) openAppDetails(context)
            return
        }
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            .putExtra(EXTRA_APP_UID, context.applicationInfo.uid)
        if (!start(context, channelIntent) && !start(context, appIntent)) openAppDetails(context)
    }

    fun openAppNotificationSettings(context: Context) {
        start(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
    }

    /**
     * 国产 ROM 的自启动管理页没有统一 API，只能按厂商逐个试。
     * @return 是否成功打开了某一家的页面（都失败时调用方应退到应用详情）。
     */
    fun openAutoStart(context: Context): Boolean {
        for ((pkg, cls) in AUTO_START_PAGES) {
            val intent = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, intent)) return true
        }
        return false
    }

    // ---------------- 模式 C：WRITE_SECURE_SETTINGS 自授权 ----------------
    //
    // 侧载应用没法在运行时申请 WRITE_SECURE_SETTINGS（signature|privileged）。
    // 但用户可以用 adb / Shizuku 授予一次：
    //     adb shell pm grant com.eta.autoledger android.permission.WRITE_SECURE_SETTINGS
    // 之后本应用就能自己把「通知使用权」「无障碍」写回 Secure 设置，
    // 不必每次让用户翻系统设置；ROM 偷偷关掉开关也能被心跳任务救回来。

    private const val SECURE_WRITE = "android.permission.WRITE_SECURE_SETTINGS"

    /** 是否已授予 WRITE_SECURE_SETTINGS：决定「一键修复」是真写回还是只弹提示。 */
    fun canWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, SECURE_WRITE) == PackageManager.PERMISSION_GRANTED

    /**
     * 把组件追加进某个冒号分隔的 Secure 列表，不动列表里已有的其它条目。
     * 写回后重新读取做校验：部分 ROM（HyperOS / EMUI）会把这次写入回滚。
     */
    private fun appendComponent(context: Context, key: String, target: String): Boolean {
        val items = componentsFor(context, key)
        if (items.any { sameComponent(it, target) }) return true
        val next = (items + target).joinToString(":")
        runCatching { Settings.Secure.putString(context.contentResolver, key, next) }
        return componentsFor(context, key).any { sameComponent(it, target) }
    }

    /** `pkg/.Cls` 与 `pkg/pkg.Cls` 指向同一个组件，比较前先归一化。 */
    private fun sameComponent(raw: String, target: String): Boolean {
        if (raw == target) return true
        val a = ComponentName.unflattenFromString(raw) ?: return false
        val b = ComponentName.unflattenFromString(target) ?: return false
        return a == b
    }

    private fun componentsFor(context: Context, key: String): List<String> =
        Settings.Secure.getString(context.contentResolver, key).orEmpty()
            .split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** 把本应用写进「通知使用权」：NMS 的 SettingsObserver 会自动重绑，不用重启。 */
    fun ensureNotificationListener(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        if (notificationListenerGranted(context)) return true
        val target = ComponentName(context, LedgerNotificationListener::class.java).flattenToShortString()
        return appendComponent(context, "enabled_notification_listeners", target)
    }

    /** 把无障碍服务写进列表，并把总开关拨到 1。 */
    fun ensureAccessibility(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        if (accessibilityGranted(context)) return true
        val target = ComponentName(context, ScreenshotAccessibilityService::class.java).flattenToShortString()
        val wrote = appendComponent(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, target)
        if (wrote) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }
        return accessibilityGranted(context)
    }

    /**
     * 一键修复：只补写缺失的通道，已开的通道不碰。
     * 返回 (通知使用权, 无障碍) 的最终状态；没拿到 WRITE_SECURE_SETTINGS 时两项都是 false。
     */
    fun ensureGrants(context: Context): Pair<Boolean, Boolean> {
        if (!canWriteSecureSettings(context)) return false to false
        return ensureNotificationListener(context) to ensureAccessibility(context)
    }

    /** 当前能力档位，设置页用它显示「标准 / 可自愈 / Root 增强」，而不是让用户自己猜。 */
    fun capabilityLabel(context: Context): String = when {
        moduleActive(context) -> "Root 增强（LSPosed）"
        canWriteSecureSettings(context) -> "标准 + 自愈（adb 已授权）"
        else -> "标准（通知 + 短信）"
    }

    /** 「一键修复」需要的 adb 命令，可直接展示/复制给用户。 */
    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} $SECURE_WRITE"

    // ---------------- 内部工具 ----------------

    private fun enabledAccessibilityServices(context: Context): List<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse { false }

    /** 自启动页候选：MIUI / EMUI / ColorOS / OriginOS / One UI。 */
    private val AUTO_START_PAGES = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )
}