package com.eta.autoledger.settings

import com.eta.autoledger.ui.theme.OilTheme
import com.eta.autoledger.ui.theme.oilPageSurface
import com.eta.autoledger.ui.components.OilText
import com.eta.autoledger.ui.components.OilIconButton
import com.eta.autoledger.ui.components.OilScaffold
import com.eta.autoledger.ui.components.OilButton
import com.eta.autoledger.ui.OilIcons
import com.eta.autoledger.ui.OilIcon
import androidx.core.content.edit
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.rememberMotionDuration
import com.eta.autoledger.ui.window.LocalSnackbar
import com.eta.autoledger.ui.theme.LedgerDimens
import com.eta.autoledger.ui.components.ImportPreviewDialog
import com.eta.autoledger.notification.IslandNotifier
import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.eta.autoledger.ui.components.OilDialog
import com.eta.autoledger.ui.components.OilCard
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.ImportDedup
import com.eta.autoledger.data.CsvImporter
import com.eta.autoledger.data.XlsxImporter
import com.eta.autoledger.network.AiClient
import com.eta.autoledger.reminder.ReminderScheduler
import com.eta.autoledger.service.LedgerNotificationListener
import com.eta.autoledger.ui.theme.ThemeMode


enum class SettingsPage(val title: String, val parent: SettingsPage?) {
    Main("设置", null),

    // ── 一级：8 个 ──────────────────────────────────────────────
    Auto("自动记账", Main),
    LedgerSettings("记账设置", Main),
    Notifications("通知与提醒", Main),
    DataBackup("数据与备份", Main),
    AppearanceWidget("外观与小部件", Main),
    Features("AI 与高级功能", Main),
    SysAccess("权限与后台", Main),
    About("关于", Main),

    // ── 二级：挂在上面 8 个之下 ──────────────────────────────────
    DailyReminder("每日记账提醒", Notifications),
    SingleNotify("单笔记账通知", Notifications),
    Categories("记账分类管理", LedgerSettings),
    Budget("预算管理", LedgerSettings),
    Import("导入数据", DataBackup),
    Export("导出数据", DataBackup),
    Backup("云备份", DataBackup),
    Appearance("外观", AppearanceWidget),
    Widget("添加桌面小部件", AppearanceWidget),
    Ai("AI 模型与自动化", Features),
    ParseRulesAdvanced("高级解析规则", Auto),
}

/**
 * 页面层级：Main 为 0，一级 1，二级 2。
 *
 * 转场方向由层级差决定 —— 进入更深一层从右侧滑入，退回上一层从左侧滑入，
 * 与用户「往里点 / 往回退」的心理模型一致。不用页面栈存历史，是因为
 * 层级本身就由 [SettingsPage.parent] 链定义好了，多存一份反而会不一致。
 */
private fun SettingsPage.depth(): Int {
    var depth = 0
    var cursor = parent
    while (cursor != null) {
        depth++
        cursor = cursor.parent
    }
    return depth
}

@Composable
fun SettingsScreen(initialPage: SettingsPage = SettingsPage.Main, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val context = LocalContext.current
    val prefs = remember(context) { AppPrefs.get(context) }
    val motion = rememberMotionDuration(200)

    BackHandler {
        val parent = page.parent
        if (parent == null) onClose() else page = parent
    }

    OilScaffold(
        containerColor = Color.Transparent,
        topBar = {
            SettingsTopBar(title = page.title, onBack = {
                val parent = page.parent
                if (parent == null) onClose() else page = parent
            })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .oilPageSurface()
                .padding(padding),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    // 层级变深 = 往里进，新页从右边推入、旧页往左退；
                    // 层级变浅 = 往回退，新页从左边推入、旧页往右退。
                    // 位移只用 1/4 屏宽：整屏平移在层级间跳转时会显得晃，
                    // 小位移 + 淡入已经足够表达方向。
                    val forward = targetState.depth() >= initialState.depth()
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(motion)) { full -> dir * full / 4 } +
                        fadeIn(tween(motion)))
                        .togetherWith(
                            slideOutHorizontally(tween(motion)) { full -> -dir * full / 4 } +
                                fadeOut(tween(motion))
                        )
                },
                label = "settings-page",
            ) { current ->
                when (current) {
                    // ── 一级 ──
                    SettingsPage.Main -> MainPage(onOpen = { page = it })
                    SettingsPage.Auto -> AutoPage(
                        prefs = prefs,
                        onImport = { page = SettingsPage.Import },
                        onManageRules = { page = SettingsPage.ParseRulesAdvanced },
                    )
                    SettingsPage.ParseRulesAdvanced -> RuleManagePage()
                    SettingsPage.LedgerSettings -> LedgerSettingsPage(onOpen = { page = it })
                    SettingsPage.Notifications -> NotificationsPage(
                        prefs = prefs,
                        onDaily = { page = SettingsPage.DailyReminder },
                        onSingle = { page = SettingsPage.SingleNotify },
                    )
                    SettingsPage.DataBackup -> DataBackupPage(onOpen = { page = it })
                    SettingsPage.AppearanceWidget -> AppearanceWidgetPage(prefs = prefs, onOpen = { page = it })
                    SettingsPage.Features -> FeaturesPage(prefs, onAi = { page = SettingsPage.Ai })
                    SettingsPage.SysAccess -> SystemAccessPage(prefs = prefs)
                    SettingsPage.About -> AboutPage()

                    // ── 二级 ──
                    SettingsPage.DailyReminder -> DailyReminderPage(prefs)
                    SettingsPage.SingleNotify -> SingleNotifyPage(prefs)
                    SettingsPage.Categories -> LedgerCategoriesPage()
                    SettingsPage.Budget -> BudgetPage(prefs, onDone = { page = SettingsPage.LedgerSettings })
                    SettingsPage.Import -> ImportPage()
                    SettingsPage.Export -> ExportPage()
                    SettingsPage.Backup -> BackupPage(prefs = prefs)
                    SettingsPage.Appearance -> AppearancePage(prefs = prefs)
                    SettingsPage.Widget -> WidgetPage()
                    SettingsPage.Ai -> AiPage(prefs)
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    com.eta.autoledger.ui.components.OilTopBar(
        title = title,
        navigationIcon = OilIcons.Back,
        onNavigationClick = onBack,
    )
}

@Composable
private fun MainPage(onOpen: (SettingsPage) -> Unit) {
    OilSettingsPageColumn {
        // 常用：记账、提醒与个性化
        OilSettingsGroup {
            SettingsNavigationRow(
                title = "自动记账",
                summary = "读取收支信息，自动生成账单",
                onClick = { onOpen(SettingsPage.Auto) },
            )
            OilSettingsDivider()
            SettingsNavigationRow(
                title = "记账设置",
                summary = "分类管理与预算",
                onClick = { onOpen(SettingsPage.LedgerSettings) },
            )
            OilSettingsDivider()
            SettingsNavigationRow(
                title = "通知与提醒",
                summary = "每日提醒、周期账单与单笔通知",
                onClick = { onOpen(SettingsPage.Notifications) },
            )
            OilSettingsDivider()
            SettingsNavigationRow(
                title = "数据与备份",
                summary = "导入、导出与 WebDAV 云备份",
                onClick = { onOpen(SettingsPage.DataBackup) },
            )
            OilSettingsDivider()
            SettingsNavigationRow(
                title = "外观与小部件",
                summary = "主题模式与桌面收支卡片",
                onClick = { onOpen(SettingsPage.AppearanceWidget) },
            )
        }

        // 系统与支持：高级能力、权限和版本信息
        OilSettingsGroup {
            SettingsNavigationRow(
                title = "AI 与高级功能",
                summary = "截屏记账、AI 识图与模型配置",
                onClick = { onOpen(SettingsPage.Features) },
            )
            OilSettingsDivider()
            SettingsNavigationRow(
                title = "权限与后台",
                summary = "通知使用权、电池白名单、通知渠道与数据清理",
                onClick = { onOpen(SettingsPage.SysAccess) },
            )
            OilSettingsDivider()
            SettingsNavigationRow(
                title = "关于",
                summary = "版本信息、开源许可与安全设置",
                onClick = { onOpen(SettingsPage.About) },
            )
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val arrowColor by animateColorAsState(
        targetValue = if (pressed) LedgerTheme.accent else LedgerTheme.textTertiary,
        animationSpec = tween(rememberMotionDuration(120)),
        label = "settings-arrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = LocalDimens.current.cardPadding, vertical = LocalDimens.current.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OilText(text = title, style = OilTheme.typography.bodyStrong, color = LedgerTheme.text)
            Spacer(Modifier.height(LedgerDimens.xs))
            OilText(text = summary, style = OilTheme.typography.caption, color = LedgerTheme.textSecondary)
        }
        OilIcon(
            imageVector = OilIcons.ChevronForward,
            contentDescription = null,
            tint = arrowColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 「记账设置」分组页：分类 + 预算。 */
@Composable
private fun LedgerSettingsPage(onOpen: (SettingsPage) -> Unit) {
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilSettingsRow(
                title = "记账分类管理",
                summary = "管理支出、收入和其他分类",
                onClick = { onOpen(SettingsPage.Categories) },
            )
            OilSettingsDivider()
            OilSettingsRow(
                title = "预算管理",
                summary = "预算金额与起始日",
                onClick = { onOpen(SettingsPage.Budget) },
            )
        }
        OilHintText("分类决定自动记账时账单被归到哪里；预算只影响首页预算卡片。")
    }
}

/** 「数据与备份」分组页：导入 / 导出 / 云备份。 */
@Composable
private fun DataBackupPage(onOpen: (SettingsPage) -> Unit) {
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilSettingsRow(
                title = "导入数据",
                summary = "支持 Excel / CSV 账单",
                onClick = { onOpen(SettingsPage.Import) },
            )
            OilSettingsDivider()
            OilSettingsRow(
                title = "导出数据",
                summary = "导出 CSV / XLSX 账单文件",
                onClick = { onOpen(SettingsPage.Export) },
            )
        }
        OilSettingsGroup {
            OilSettingsRow(
                title = "云备份",
                summary = "备份到 WebDAV（坚果云 / 群晖等），换机可恢复",
                onClick = { onOpen(SettingsPage.Backup) },
            )
        }
        OilHintText("导入用于把别的 App 的账单搬进来；云备份用于换机和防丢。")
    }
}

/** 「外观与小部件」分组页：主题 + 桌面小部件。 */
@Composable
private fun AppearanceWidgetPage(prefs: AppPrefs, onOpen: (SettingsPage) -> Unit) {
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilSettingsRow(
                title = "外观",
                summary = when (prefs.themeMode) {
                    "light" -> "浅色"
                    "dark" -> "深色"
                    else -> "跟随系统"
                },
                onClick = { onOpen(SettingsPage.Appearance) },
            )
            OilSettingsDivider()
            OilSettingsRow(
                title = "添加桌面小部件",
                summary = "在桌面展示今日与本月收支",
                onClick = { onOpen(SettingsPage.Widget) },
            )
        }
    }
}

/** 「关于」页：版本 + 许可 + 数据说明。 */
@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilInfoRow(title = "版本", value = versionName)
            OilSettingsDivider()
            OilInfoRow(title = "包名", value = context.packageName)
        }
        OilSettingsGroup {
            OilInfoRow(title = "开源许可", value = "Oil UI · Compose · Room · Vico")
        }
        OilHintText(
            "隐私说明：账目、分类、预算、周期账单全部存放在本机数据库，本 App 不设服务器、不做账号体系、不上传统计数据。\n" +
                "以下两种情况才会产生网络请求，且只发往你自己填写的地址：\n" +
                "1）配置 AI 模型后使用识图记账，截图会发给你选的模型服务商；\n" +
                "2）配置 WebDAV 后手动备份或恢复，账目 JSON 会发给你填的网盘。\n" +
                "卸载 App 或清除应用数据会一并删除本机账目，建议先导出或备份。",
        )
    }
}

@Composable
private fun AutoPage(
    prefs: AppPrefs,
    onImport: () -> Unit,
    onManageRules: () -> Unit,
) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val ledgerListenerGranted = LedgerNotificationListener.isEnabled(context)
    // 短信是危险权限，开关只是偏好，真正能不能收到广播看 RECEIVE_SMS 有没有授予
    var smsGranted by remember { mutableStateOf(hasSmsPermission(context)) }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        smsGranted = granted
        prefs.smsEnabled = granted
        if (!granted) {
            scope.launch { snackbar.show("未授予短信权限，短信记账不会生效") }
        }
    }
    OilSettingsPageColumn {
        OilHintText("读取收支信息，自动生成账单。")
        OilSettingsGroup {
            OilSettingsRow(
                title = "通知使用权",
                summary = if (ledgerListenerGranted) "已开启，正在监听账单通知" else "未开启，点击授予通知使用权",
                onClick = { LedgerNotificationListener.openSettings(context) },
            )
        }
        OilSettingsGroup {
            OilSwitchSettingsRow(
                title = "短信记账",
                summary = if (smsGranted) "解析银行与服务号短信里的收支信息" else "未授予短信权限，点击开关授权",
                checked = prefs.smsEnabled && smsGranted,
                onCheckedChange = { on ->
                    when {
                        !on -> prefs.smsEnabled = false
                        smsGranted -> prefs.smsEnabled = true
                        else -> smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    }
                },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "支付宝",
                summary = "解析支付宝账单通知",
                checked = prefs.alipayEnabled,
                onCheckedChange = { prefs.alipayEnabled = it },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "微信",
                summary = "解析微信支付账单通知",
                checked = prefs.wechatEnabled,
                onCheckedChange = { prefs.wechatEnabled = it },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "其他",
                summary = "云闪付、抖音、快手、滴滴、美团支付等",
                checked = prefs.otherEnabled,
                onCheckedChange = { prefs.otherEnabled = it },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "截屏记账",
                summary = "接收系统截屏并交给 AI 解析",
                checked = prefs.screenshotEnabled,
                onCheckedChange = { prefs.screenshotEnabled = it },
            )
        }
        OilSettingsGroup {
            OilSettingsRow(title = "账单导入", summary = "手动导入微信、支付宝账单", onClick = onImport)
        }
        OilSettingsGroup {
            OilSettingsRow(
                title = "高级解析规则",
                summary = "系统内置与自定义规则，可启停、可测试",
                onClick = onManageRules,
            )
        }
    }
}

@Composable
private fun NotificationsPage(prefs: AppPrefs, onDaily: () -> Unit, onSingle: () -> Unit) {
    val context = LocalContext.current
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilSettingsRow(title = "每日记账提醒", summary = prefs.reminderTime, onClick = onDaily)
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "记账日报",
                summary = "每日固定时间提醒",
                checked = prefs.dailyReminder,
                onCheckedChange = {
                    prefs.dailyReminder = it
                    if (it) ReminderScheduler.scheduleDaily(context, prefs.reminderTime)
                    else ReminderScheduler.cancelDaily(context)
                },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "记账周报",
                summary = "每周一上午接收",
                checked = prefs.weeklyReport,
                onCheckedChange = {
                    prefs.weeklyReport = it
                    if (it) ReminderScheduler.scheduleWeekly(context) else ReminderScheduler.cancelWeekly(context)
                },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "记账月报",
                summary = "每月一号上午接收",
                checked = prefs.monthlyReport,
                onCheckedChange = {
                    prefs.monthlyReport = it
                    if (it) ReminderScheduler.scheduleMonthly(context) else ReminderScheduler.cancelMonthly(context)
                },
            )
        }
        OilSettingsGroup {
            OilSettingsRow(title = "单笔记账通知", summary = "生成自动账单时的提醒方式", onClick = onSingle)
        }
    }
}

@Composable
private fun DailyReminderPage(prefs: AppPrefs) {
    val context = LocalContext.current
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilSettingsRow(
                title = "提醒时间",
                summary = prefs.reminderTime,
                onClick = {
                    val parts = prefs.reminderTime.split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 20
                    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    TimePickerDialog(context, { _, h, m ->
                        prefs.reminderTime = "%02d:%02d".format(h, m)
                        if (prefs.dailyReminder) {
                            ReminderScheduler.cancelDaily(context)
                            ReminderScheduler.scheduleDaily(context, prefs.reminderTime)
                        }
                    }, hour, minute, true).show()
                },
            )
        }
        OilHintText("选择通知方式（可多选）")
        OilSettingsGroup {
            OilSwitchSettingsRow(
                title = "超级岛提醒",
                summary = "每日提醒以实况通知呈现",
                checked = prefs.islandDaily,
                onCheckedChange = { prefs.islandDaily = it },
            )
        }
    }
}

@Composable
private fun SingleNotifyPage(prefs: AppPrefs) {
    val context = LocalContext.current
    fun islandSummary(): String {
        val vendor = IslandNotifier.vendorLabel(IslandNotifier.vendor())
        return if (IslandNotifier.supportsLiveUpdates()) "$vendor · 实况胶囊（免业务ID）" else "$vendor · 系统版本较低"
    }
    OilSettingsPageColumn {
        OilHintText("生成自动记账单时的通知方式（可多选）。")
        OilSettingsGroup {
            OilSwitchSettingsRow(
                title = "超级岛提醒",
                checked = prefs.islandSingle,
                onCheckedChange = { prefs.islandSingle = it },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "常驻消息通知",
                checked = prefs.persistentSingle,
                onCheckedChange = { prefs.persistentSingle = it },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "不提示",
                summary = "生成自动记账单时不提示",
                checked = prefs.singleMuted,
                onCheckedChange = { prefs.singleMuted = it },
            )
        }
        OilSettingsGroup {
            OilSettingsRow(
                title = "立即发送测试提醒",
                summary = "在前台发一条实况通知，验证是否上岛",
                onClick = {
                    IslandNotifier.deliver(
                        context,
                        IslandNotifier.Spec(
                            id = 2099,
                            title = "单笔记账通知",
                            text = "今天共 2 笔：支出 ¥31.50 · 收入 ¥0.00",
                            critical = "记账 2 笔",
                            channelId = "ledger_island_test",
                            channelName = "实况测试",
                            ongoing = true,
                            enableVendorIsland = true,
                        ),
                    )
                },
            )
            OilSettingsDivider()
            OilSwitchSettingsRow(
                title = "超级岛 / 实况提醒",
                summary = islandSummary(),
                checked = prefs.islandLiveUpdates,
                onCheckedChange = { prefs.islandLiveUpdates = it },
            )
        }
    }
}

@Composable
private fun BudgetPage(prefs: AppPrefs, onDone: () -> Unit) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var amount by remember { mutableStateOf(prefs.budgetAmount) }
    var showStartDayPicker by remember { mutableStateOf(false) }

    OilSettingsPageColumn {
        OilSettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                OilText("预算金额", style = OilTheme.typography.caption.copy(color = TextSecondary))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OilText("¥", style = OilTheme.typography.amount.copy(color = TextPrimary))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = OilTheme.typography.amount.copy(color = TextPrimary),
                        cursorBrush = SolidColor(AccentBlue),
                    )
                }
            }
        }
        OilSettingsGroup {
            // 预算起始日：发薪日不是 1 号时，本月 = 起始日 ~ 次月起始日前一天
            OilSettingsRow(
                title = "预算起始日",
                summary = "${prefs.budgetStartDay} 日" + if (prefs.budgetStartDay == 1) "（自然月）" else "",
                onClick = { showStartDayPicker = true },
            )
        }
        OilSettingsGroup {
            OilSwitchSettingsRow(
                title = "首页显示预算卡片",
                summary = "开启后，预算进度会显示在首页核心资产卡底部",
                checked = prefs.homeBudgetCard,
                onCheckedChange = { prefs.homeBudgetCard = it },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                OilText("取消", style = OilTheme.typography.body.copy(color = TextSecondary))
            }
            OilButton(
                text = "保存",
                onClick = {
                    prefs.budgetAmount = amount
                    scope.launch { snackbar.show("预算已保存") }
                    onDone()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    // 常驻组合、只切 show：外面用 if 包起来的话，一点选或点遮罩组件就被移出组合，
    // OilDialog 接管的退场动画没机会播。
    StartDayPickerDialog(
        show = showStartDayPicker,
        selected = prefs.budgetStartDay,
        onDismiss = { showStartDayPicker = false },
        onPick = { day ->
            prefs.budgetStartDay = day
            showStartDayPicker = false
        },
    )
}

/**
 * 预算起始日选择器：7 列 × 4 行网格，1..28 全部可选。
 *
 * 为什么是 28 上限：29/30/31 在 2 月不存在，会让预算周期边界漂移甚至跳过整月。
 * 为什么用网格而不是循环点击：原来点一下 +1，改到 28 号要点 27 下。
 */
@Composable
private fun StartDayPickerDialog(
    show: Boolean,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    OilDialog(show = show, title = "预算起始日", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OilText(
                text = "选发薪日；预算周期 = 该日 ~ 次月该日前一天。1 号即自然月。",
                style = OilTheme.typography.caption,
                color = LedgerTheme.textSecondary,
            )
            Spacer(Modifier.height(16.dp))

            // 7 列 × 4 行 = 28 天
            for (row in 0 until 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (col in 0 until 7) {
                        val day = row * 7 + col + 1
                        val isSelected = day == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) LedgerTheme.accent else LedgerTheme.inset)
                                .clickable { onPick(day) },
                            contentAlignment = Alignment.Center,
                        ) {
                            OilText(
                                text = day.toString(),
                                style = OilTheme.typography.body.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) LedgerTheme.onAccent else LedgerTheme.text,
                                ),
                            )
                        }
                    }
                }
                if (row < 3) Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                OilText("取消", style = OilTheme.typography.caption, color = LedgerTheme.textSecondary)
            }
        }
    }
}

@Composable
private fun ImportPage() {
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 教程卡：原本 3 个 onClick 都是空实现（点了没反应）。改为弹出说明对话框。
    var guideFor by remember { mutableStateOf<String?>(null) }
    var importPlan by remember { mutableStateOf<ImportDedup.Plan?>(null) }
    var importing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch { snackbar.show("正在解析，请稍候…") }
            scope.launch {
                // 解析任意用户文件，畸形 CSV/XLSX/编码异常都可能抛异常，必须兜住否则直接崩溃
                val parsed = withContext(Dispatchers.IO) {
                    runCatching {
                        val mime = context.contentResolver.getType(uri).orEmpty()
                        val name = uri.lastPathSegment.orEmpty()
                        val entries = if (mime.contains("spreadsheetml") || name.endsWith(".xlsx", true)) {
                            XlsxImporter.parse(context, uri)
                        } else {
                            // 必须读原始字节再判编码：支付宝账单常见 GBK，
                            // 按 UTF-8 硬读会让中文表头全变乱码，解析器随即错位。
                            val bytes = context.contentResolver.openInputStream(uri)
                                ?.use { it.readBytes() } ?: ByteArray(0)
                            CsvImporter.parse(CsvImporter.decode(bytes))
                        }
                        // 本地启发式 + 可选 AI 判重：解析完先出预览，不直接写库
                        ImportDedup.plan(context, entries, aiCheck = true)
                    }
                }
                parsed.onSuccess { value ->
                    if (value.isEmpty) {
                        snackbar.showLong("这个文件里没有解析到账目，确认导出的是账单明细吗？")
                    } else {
                        importPlan = value
                    }
                }.onFailure { e ->
                    scope.launch { snackbar.showLong("导入失败：文件格式无法识别（${e.message ?: "解析错误"}）") }
                }
            }
        }
    }
    val currentPlan = importPlan
    if (currentPlan != null) {
        ImportPreviewDialog(
            show = true,
            plan = currentPlan,
            busy = importing,
            onDismiss = { if (!importing) importPlan = null },
            onCommit = { decisions ->
                importing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ImportDedup.commit(context, currentPlan, decisions) }
                    }
                    importing = false
                    importPlan = null
                    result.onSuccess { r ->
                        val extra = buildString {
                            if (r.merged > 0) append("，合并 ").append(r.merged).append(" 笔")
                            if (r.skipped > 0) append("，跳过 ").append(r.skipped).append(" 笔")
                        }
                        snackbar.show("导入完成：新增 " + r.added + " 笔" + extra)
                    }.onFailure { e ->
                        snackbar.showLong("写入失败：" + (e.message ?: "数据库异常"))
                    }
                }
            },
        )
    }
    OilSettingsPageColumn {
        OilHintText("支持导入 Excel / CSV 格式，推荐单次 1MB 以内。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ImportPlatformCard("微信", "账单导入教程", Modifier.weight(1f)) {
                guideFor = "微信"
            }
            ImportPlatformCard("支付宝", "账单导入教程", Modifier.weight(1f)) {
                guideFor = "支付宝"
            }
            ImportPlatformCard("其他平台", "通用导入教程", Modifier.weight(1f)) {
                guideFor = "其他平台"
            }
        }
        OilButton(
            text = "本地文件导入",
            onClick = { launcher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 教程卡说明对话框：给出可执行的导出路径，而不是一句空提示。
    // 常驻组合、只切 show：外面用 guideFor?.let { } 包起来的话，一点「知道了」组件就被移出组合，
    // OilDialog 接管的退场动画播不出来。关闭后 guideFor 置 null，这里留住最后一次的平台名。
    var lastGuideFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(guideFor) { if (guideFor != null) lastGuideFor = guideFor }
    lastGuideFor?.let { platform ->
        val steps = when (platform) {
            "微信" -> "微信 → 我 → 服务 → 钱包 → 账单 → 右上角「常见问题」→「下载账单」→ 选「用于个人对账」→ 填邮箱收 CSV"
            "支付宝" -> "支付宝 → 我的 → 账单 → 右上角「⋯」→「开具交易流水证明」→ 选「用于个人对账」→ 填邮箱收 CSV"
            else -> "各银行/电商 App 一般在「我的 → 账单 / 订单」里提供导出或邮件发送 CSV/Excel；" +
                    "也可以在对应 App 内搜索「账单导出」查看官方指引。"
        }
        OilDialog(
            show = guideFor != null,
            title = "$platform 账单导出",
            maxWidth = 420.dp,
            outsideMargin = DpSize(24.dp, 24.dp),
            onDismissRequest = { guideFor = null },
        ) {
            Spacer(Modifier.height(10.dp))
            OilText(steps, style = OilTheme.typography.caption, color = LedgerTheme.textSecondary)
            Spacer(Modifier.height(16.dp))
            OilButton(
                text = "知道了",
                onClick = { guideFor = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImportPlatformCard(title: String, summary: String, modifier: Modifier, onClick: () -> Unit) {
    OilCard(modifier = modifier, onClick = onClick) {
        Column {
            OilText(title, style = OilTheme.typography.bodyStrong.copy(color = TextPrimary))
            Spacer(Modifier.height(4.dp))
            OilText(summary, style = OilTheme.typography.caption.copy(color = TextSecondary))
        }
    }
}

/** 导出格式：CSV / XLSX 共用同一份筛选结果，只是编码器不同。 */
@Composable
private fun FeaturesPage(prefs: AppPrefs, onAi: () -> Unit) {
    OilSettingsPageColumn {
        OilSettingsGroup {
            OilSwitchSettingsRow(
                title = "截屏记账",
                summary = "控制中心快捷开关 + AI 识图",
                checked = prefs.screenshotEnabled,
                onCheckedChange = { prefs.screenshotEnabled = it },
            )
            OilSettingsDivider()
            OilSettingsRow(title = "AI 模型与自动化", summary = "配置模型、Key 与 Prompt", onClick = onAi)
        }
        OilHintText("「清空记账数据」已移到「权限与后台」页底部，避免误触。")
    }
}

@Composable
private fun AiPage(prefs: AppPrefs) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val dimens = LocalDimens.current
    var baseUrl by remember { mutableStateOf(prefs.aiBaseUrl) }
    var key by remember { mutableStateOf(prefs.aiKey) }
    var prompt by remember { mutableStateOf(prefs.aiPrompt) }
    var model by remember { mutableStateOf(prefs.aiModel) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

    fun requestModels() {
        if (fetchingModels) return
        if (baseUrl.isBlank()) {
            scope.launch { snackbar.show("请先填写 Base URL") }
            return
        }
        fetchingModels = true
        scope.launch {
            val result = try {
                fetchModels(baseUrl, key)
            } finally {
                fetchingModels = false
            }
            result.onSuccess { fetched ->
                models = fetched.distinct()
                if (models.isEmpty()) {
                    snackbar.showLong("接口未返回模型，可直接手动输入模型名")
                } else {
                    showModels = true
                }
            }.onFailure { error ->
                snackbar.showLong("获取失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun checkConnection() {
        if (testingConnection) return
        val normalizedModel = model.trim()
        if (baseUrl.isBlank() || normalizedModel.isBlank()) {
            scope.launch { snackbar.show("请先填写 Base URL 和 Model Name") }
            return
        }
        testingConnection = true
        scope.launch {
            val result = try {
                testConnection(baseUrl, key, normalizedModel)
            } finally {
                testingConnection = false
            }
            result.onSuccess { snackbar.showLong(it) }
                .onFailure { error ->
                    snackbar.showLong("连接失败：${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    OilSettingsPageColumn {
        OilHintText("您的数据仅发送至您配置的本地/第三方大模型，本 App 不保存截图。")

        OilSettingsGroup {
            Column(modifier = Modifier.padding(dimens.cardPadding)) {
                OilText(
                    "API URL / Base URL",
                    style = OilTheme.typography.caption,
                    color = LedgerTheme.textSecondary,
                )
                Spacer(Modifier.height(dimens.gapSmall))
                SettingsInput(value = baseUrl, placeholder = "https://api.openai.com/v1", onValueChange = { baseUrl = it })
                Spacer(Modifier.height(dimens.gap))
                OilText(
                    "API Key",
                    style = OilTheme.typography.caption,
                    color = LedgerTheme.textSecondary,
                )
                Spacer(Modifier.height(dimens.gapSmall))
                SettingsInput(
                    value = key,
                    placeholder = "sk-...",
                    onValueChange = { key = it },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(dimens.gap))
                OilText(
                    "Model Name",
                    style = OilTheme.typography.caption,
                    color = LedgerTheme.textSecondary,
                )
                Spacer(Modifier.height(dimens.gapSmall))
                SettingsInput(
                    value = model,
                    placeholder = "例如 gpt-4o 或 auto/hy4flash-free",
                    onValueChange = { value ->
                        model = value
                        prefs.aiModel = value.trim()
                    },
                    trailingContent = {
                        OilIconButton(
                            onClick = { requestModels() },
                        ) {
                            if (fetchingModels) {
                                OilText(
                                    text = "…",
                                    style = OilTheme.typography.body,
                                    color = LedgerTheme.textTertiary,
                                )
                            } else {
                                OilIcon(
                                    imageVector = OilIcons.Search,
                                    contentDescription = "获取模型列表",
                                    tint = LedgerTheme.accent,
                                )
                            }
                        }
                    },
                )
                Spacer(Modifier.height(dimens.gapSmall))
                OilText(
                    "输入后自动保存。右侧按钮可读取 /models 列表，也允许使用列表中不存在的自定义模型名。",
                    style = OilTheme.typography.caption,
                    color = LedgerTheme.textSecondary,
                )
                Spacer(Modifier.height(dimens.gap))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LedgerDimens.minTouch)
                        .clip(RoundedCornerShape(dimens.radiusCard))
                        .background(LedgerTheme.inset)
                        .border(1.dp, LedgerTheme.borderSoft, RoundedCornerShape(dimens.radiusCard))
                        .clickable(enabled = !testingConnection) { checkConnection() },
                    contentAlignment = Alignment.Center,
                ) {
                    OilText(
                        text = if (testingConnection) "测试中…" else "测试连接",
                        style = OilTheme.typography.body,
                        color = if (testingConnection) LedgerTheme.textTertiary else LedgerTheme.text,
                    )
                }
            }
        }

        OilSettingsGroup {
            Column(modifier = Modifier.padding(dimens.cardPadding)) {
                OilText(
                    "Prompt 提示词",
                    style = OilTheme.typography.caption,
                    color = LedgerTheme.textSecondary,
                )
                Spacer(Modifier.height(dimens.gapSmall))
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp)
                        .clip(RoundedCornerShape(dimens.radiusCard))
                        .background(LedgerTheme.inset)
                        .border(1.dp, LedgerTheme.borderSoft, RoundedCornerShape(dimens.radiusCard))
                        .padding(dimens.gap),
                    textStyle = OilTheme.typography.caption.copy(color = LedgerTheme.text),
                    cursorBrush = SolidColor(AccentBlue),
                )
            }
        }

        OilButton(
            text = "保存配置",
            onClick = {
                prefs.aiBaseUrl = baseUrl
                prefs.aiKey = key
                prefs.aiModel = model.trim()
                prefs.aiPrompt = prompt
                scope.launch { snackbar.show("AI 配置已保存") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    OilDialog(
        show = showModels,
        title = "选择模型",
        summary = "点击模型即可填入输入框，也可以手动输入列表之外的模型名。",
        maxWidth = 480.dp,
        outsideMargin = DpSize(dimens.cardPadding, dimens.cardPadding),
        onDismissRequest = { showModels = false },
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.gapSmall),
        ) {
            models.forEach { name ->
                val selected = name == model.trim()
                val shape = RoundedCornerShape(dimens.radiusCard)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LedgerDimens.minTouch)
                        .clip(shape)
                        .background(if (selected) LedgerTheme.surfacePressed else LedgerTheme.inset)
                        .border(1.dp, if (selected) LedgerTheme.accent else LedgerTheme.borderSoft, shape)
                        .clickable {
                            model = name
                            prefs.aiModel = name
                            showModels = false
                        }
                        .padding(horizontal = dimens.gap, vertical = dimens.gapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OilText(
                        text = name,
                        style = OilTheme.typography.body,
                        color = if (selected) LedgerTheme.accent else LedgerTheme.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        OilText(
                            text = "当前",
                            style = OilTheme.typography.caption,
                            color = LedgerTheme.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LedgerTheme.inset)
            .padding(start = 12.dp, end = if (trailingContent == null) 12.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            singleLine = true,
            textStyle = OilTheme.typography.body.copy(color = TextPrimary),
            cursorBrush = SolidColor(AccentBlue),
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) OilText(placeholder, style = OilTheme.typography.body.copy(color = TextSecondary))
                    inner()
                }
            },
        )
        trailingContent?.invoke()
    }
}

private suspend fun fetchModels(baseUrl: String, key: String): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatching { AiClient.fetchModels(baseUrl, key) }
}

/** 只读信息行：标题在左、值在右，用于展示运行模式与最近采集时间。 */
@Composable
private fun AppearancePage(prefs: AppPrefs) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var importingBackground by remember { mutableStateOf(false) }
    val modes = listOf(
        ThemeMode.SYSTEM to ("跟随系统" to "随系统的深色/浅色开关自动切换"),
        ThemeMode.LIGHT to ("浅色" to "始终使用浅色主题"),
        ThemeMode.DARK to ("深色" to "始终使用深色主题，夜间看账不刺眼"),
    )
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importingBackground) return@rememberLauncherForActivityResult
        importingBackground = true
        scope.launch {
            snackbar.show("正在导入背景图…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.filesDir, "custom_background_${System.currentTimeMillis()}.img")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选图片")
                    target
                }
            }
            importingBackground = false
            result.onSuccess { target ->
                val previous = prefs.customBackgroundPath
                prefs.customBackgroundPath = target.absolutePath
                if (previous.isNotBlank() && previous != target.absolutePath) {
                    withContext(Dispatchers.IO) { runCatching { File(previous).delete() } }
                }
                snackbar.show("背景图已更新")
            }.onFailure { error ->
                snackbar.showLong("背景图导入失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
    OilSettingsPageColumn {
        OilSettingsGroup {
            modes.forEachIndexed { index, entry ->
                if (index > 0) OilSettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { prefs.themeMode = entry.first }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OilText(
                            text = entry.second.first,
                            style = OilTheme.typography.body.copy(color = TextPrimary),
                        )
                        Spacer(Modifier.height(2.dp))
                        OilText(
                            text = entry.second.second,
                            style = OilTheme.typography.caption.copy(color = TextSecondary),
                        )
                    }
                    if (prefs.themeMode == entry.first) {
                        OilText(
                            text = "✓",
                            style = OilTheme.typography.body.copy(color = AccentBlue),
                        )
                    }
                }
            }
        }
        OilSettingsGroup {
            OilSettingsRow(
                title = if (importingBackground) "正在导入背景图…" else "自定义背景图",
                summary = if (prefs.customBackgroundPath.isBlank()) {
                    "从相册选择一张图片作为全局背景"
                } else {
                    "已设置，点击更换图片"
                },
                onClick = {
                    if (!importingBackground) backgroundPicker.launch(arrayOf("image/*"))
                },
            )
            if (prefs.customBackgroundPath.isNotBlank()) {
                OilSettingsDivider()
                OilSettingsRow(
                    title = "恢复默认背景",
                    summary = "恢复跟随主题的 Oil 纯色画布",
                    onClick = {
                        val previous = prefs.customBackgroundPath
                        prefs.customBackgroundPath = ""
                        scope.launch {
                            if (previous.isNotBlank()) {
                                withContext(Dispatchers.IO) { runCatching { File(previous).delete() } }
                            }
                            snackbar.show("已恢复默认背景")
                        }
                    },
                )
            }
        }
        OilHintText("深色模式下页面背景、卡片、文字与强调色都会跟随切换；小部件仍使用自身配色。")
    }
}

@Composable
private fun BackupPage(prefs: AppPrefs) {
    val snackbar = LocalSnackbar.current

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf(prefs.davLastResult) }
    var restorePlan by remember { mutableStateOf<ImportDedup.Plan?>(null) }

    fun run(label: String, block: suspend () -> com.eta.autoledger.data.BackupRunner.Result) {
        if (busy) return
        busy = true
        scope.launch { snackbar.show("$label…") }
        scope.launch {
            val result = block()
            lastResult = prefs.davLastResult
            snackbar.showLong(result.message)
            busy = false
        }
    }

    OilSettingsPageColumn {
        // 快捷模板：小白不必自己去查 WebDAV 地址长什么样
        OilSettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                OilText("快捷模板", style = OilTheme.typography.caption.copy(color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DavTemplateChip(
                        title = "坚果云",
                        selected = prefs.davUrl.startsWith("https://dav.jianguoyun.com"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            prefs.davUrl = "https://dav.jianguoyun.com/dav/"
                            scope.launch { snackbar.show("已填入坚果云地址，账号填邮箱、密码填应用密码") }
                        },
                    )
                    DavTemplateChip(
                        title = "群晖",
                        selected = prefs.davUrl.contains(":5006"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            prefs.davUrl = "https://你的域名:5006/auto-ledger"
                            scope.launch { snackbar.show("请把「你的域名」换成实际地址，端口保持 5006") }
                        },
                    )
                }
            }
        }

        OilSettingsGroup {
            DavField(
                label = "目录地址",
                hint = "https://dav.jianguoyun.com/dav/auto-ledger",
                value = prefs.davUrl,
                onChange = { prefs.davUrl = it },
            )
            OilSettingsDivider()
            DavField(
                label = "账号",
                hint = "坚果云登录邮箱 / 群晖用户名",
                value = prefs.davUser,
                onChange = { prefs.davUser = it },
            )
            OilSettingsDivider()
            DavField(
                label = "应用密码",
                hint = "坚果云要填「应用密码」，不是登录密码",
                value = prefs.davPass,
                onChange = { prefs.davPass = it },
                secret = true,
            )
        }

        OilSettingsGroup {
            OilSettingsRow(
                title = if (busy) "处理中…" else "立即备份",
                summary = "把全部账目编码成 JSON 上传，云端同名文件会被覆盖",
                onClick = {
                    run("正在备份") { com.eta.autoledger.data.BackupRunner.backup(context, prefs) }
                },
            )
            OilSettingsDivider()
            OilSettingsRow(
                title = "从云端恢复",
                summary = "读取云端最新备份，先出预览再决定合并哪些",
                onClick = {
                    if (busy) {
                        scope.launch { snackbar.show("上一个操作还没结束") }
                    } else {
                        busy = true
                        scope.launch {
                            snackbar.show("正在读取云端备份…")
                            val stage = com.eta.autoledger.data.BackupRunner.prepareRestore(context, prefs)
                            lastResult = prefs.davLastResult
                            busy = false
                            when (stage) {
                                is com.eta.autoledger.data.BackupRunner.Stage.Ready -> {
                                    if (stage.plan.isEmpty) {
                                        snackbar.showLong("备份里没有可恢复的账目")
                                    } else {
                                        restorePlan = stage.plan
                                    }
                                }
                                is com.eta.autoledger.data.BackupRunner.Stage.Failed ->
                                    snackbar.showLong(stage.message)
                            }
                        }
                    }
                },
            )
            OilSettingsDivider()
            OilSettingsRow(
                title = "测试连接",
                summary = "建目录 + 上传探针文件，验证地址与凭据",
                onClick = {
                    if (busy) {
                        scope.launch { snackbar.show("上一个操作还没结束") }
                    } else {
                        busy = true
                        scope.launch {
                            val message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val target = com.eta.autoledger.data.WebDavClient.target(prefs)
                                if (target == null) {
                                    "请先填写地址、账号与应用密码"
                                } else {
                                    runCatching { com.eta.autoledger.data.WebDavClient.test(target) }
                                        .getOrElse { it.message ?: it.javaClass.simpleName }
                                }
                            }
                            snackbar.showLong(message)
                            busy = false
                        }
                    }
                },
            )
        }

        OilSettingsGroup {
            OilSwitchSettingsRow(
                title = "每天自动备份",
                summary = "系统空闲且有网络时执行，间隔至少 20 小时",
                checked = prefs.davAuto,
                onCheckedChange = {
                    prefs.davAuto = it
                    if (it) com.eta.autoledger.service.BackupJobService.schedule(context)
                },
            )
            OilSettingsDivider()
            OilInfoRow(title = "上次结果", value = lastResult.ifBlank { "尚未备份" })
        }

        OilHintText(
            "坚果云：网页版「账户信息 → 安全选项 → 添加应用密码」，用那串密码填上面的「应用密码」。\n" +
                "群晖：控制面板启用 WebDAV Server 后，地址填 https://你的域名:5006/备份目录。\n" +
                "备份文件为 JSON（不是数据库文件），换机后先装 App，再点「从云端恢复」即可。",
        )
    }

    val plan = restorePlan
    if (plan != null) {
        ImportPreviewDialog(
            show = true,
            plan = plan,
            busy = busy,
            onDismiss = { if (!busy) restorePlan = null },
            onCommit = { decisions ->
                if (busy) return@ImportPreviewDialog
                busy = true
                scope.launch {
                    snackbar.show("正在写入本地…")
                    val result = com.eta.autoledger.data.BackupRunner.commitRestore(
                        context,
                        prefs,
                        plan,
                        decisions,
                    )
                    lastResult = prefs.davLastResult
                    restorePlan = null
                    busy = false
                    snackbar.showLong(result.message)
                }
            },
        )
    }
}

/** WebDAV 服务商快捷模板：选中态实心 accent，与分类 Chip 保持同一套视觉。 */
@Composable
private fun DavTemplateChip(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) LedgerTheme.accent else LedgerTheme.inset)
            .border(1.dp, if (selected) LedgerTheme.accent else LedgerTheme.borderSoft, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OilText(
            text = title,
            style = OilTheme.typography.caption.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) LedgerTheme.onAccent else LedgerTheme.text,
            ),
        )
    }
}

@Composable
private fun DavField(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OilText(text = label, style = OilTheme.typography.caption.copy(color = TextSecondary))
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LedgerTheme.inset)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty()) {
                OilText(
                    text = hint,
                    style = OilTheme.typography.caption.copy(color = LedgerTheme.textSecondary),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = OilTheme.typography.caption.copy(color = TextPrimary),
                cursorBrush = SolidColor(AccentBlue),
                visualTransformation = if (secret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun hasSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

private suspend fun testConnection(baseUrl: String, key: String, model: String): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val messages = JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "只回复 OK，不要输出其他内容。")
                },
            )
            AiClient.chat(baseUrl, key, model, messages, maxTokens = 128)
            "连接成功：模型已响应"
        }.recoverCatching { error ->
            if (error.message?.contains("模型返回为空") != true) throw error
            val available = AiClient.fetchModels(baseUrl, key)
            if (available.any { it.equals(model, ignoreCase = true) }) {
                "连接成功：模型已在列表中确认"
            } else {
                "连接成功：服务端与鉴权可用"
            }
        }
    }
