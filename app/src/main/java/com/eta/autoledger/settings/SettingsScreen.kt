package com.eta.autoledger.settings

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.rememberMotionDuration
import com.eta.autoledger.ui.window.LocalSnackbar
import com.eta.autoledger.ui.theme.LedgerDimens
import com.eta.autoledger.ui.components.ImportPreviewDialog
import com.eta.autoledger.notification.IslandNotifier
import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import com.eta.autoledger.util.SystemAccess
import java.util.Date
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import com.eta.autoledger.ui.components.AppDialog
import com.eta.autoledger.ui.components.SwitchRow as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.ImportDedup
import com.eta.autoledger.data.CsvImporter
import com.eta.autoledger.data.XlsxImporter
import com.eta.autoledger.data.XlsxExporter
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.reminder.ReminderScheduler
import com.eta.autoledger.service.LedgerNotificationListener
import com.eta.autoledger.ui.theme.ThemeMode
import com.eta.autoledger.widget.LedgerWidgetProvider
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction


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
    SingleNotify("单笔自动记账通知", Notifications),
    Categories("记账分类管理", LedgerSettings),
    Budget("预算管理", LedgerSettings),
    Import("导入数据", DataBackup),
    Export("导出数据", DataBackup),
    Backup("云备份", DataBackup),
    Appearance("外观", AppearanceWidget),
    Widget("添加桌面小部件", AppearanceWidget),
    Ai("AI 模型与自动化", Features),
    ParseRules("高级解析设置", Auto),
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

    Scaffold(
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
                .background(PageBg)
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
                        onParseRules = { page = SettingsPage.ParseRules },
                        onManageRules = { page = SettingsPage.ParseRulesAdvanced },
                    )
                    SettingsPage.ParseRules -> ParseRulesPage(prefs)
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
                    SettingsPage.Categories -> CategoriesPage()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PageBg)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回", tint = TextPrimary)
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.main.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary),
        )
    }
}

@Composable
private fun SettingsAmbienceCard() {
    val cardShape = RoundedCornerShape(LocalDimens.current.radiusCard)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, cardShape)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "品牌色", style = MiuixTheme.textStyles.title4, color = LedgerTheme.text, modifier = Modifier.weight(1f))
                Text(text = "固定", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "全局强调色，用于主按钮、选中态与预算剩余。",
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LedgerTheme.accent),
            )
        }
    }
}

@Composable
private fun MainPage(onOpen: (SettingsPage) -> Unit) {
    SettingsPageColumn {
        // 品牌色卡片保留在首屏，但只作为视觉标识，不再是可点配置项。
        SettingsAmbienceCard()

        // 高频：每天/每周都会碰的三件事
        SettingsCard {
            ArrowPreference(
                title = "自动记账",
                summary = "读取收支信息，自动生成账单",
                onClick = { onOpen(SettingsPage.Auto) },
            )
            RowDivider()
            ArrowPreference(
                title = "记账设置",
                summary = "分类管理与预算",
                onClick = { onOpen(SettingsPage.LedgerSettings) },
            )
            RowDivider()
            ArrowPreference(
                title = "通知与提醒",
                summary = "每日提醒、周期账单与单笔通知",
                onClick = { onOpen(SettingsPage.Notifications) },
            )
        }

        // 中频：数据的进出与存档
        SettingsCard {
            ArrowPreference(
                title = "数据与备份",
                summary = "导入、导出与 WebDAV 云备份",
                onClick = { onOpen(SettingsPage.DataBackup) },
            )
            RowDivider()
            ArrowPreference(
                title = "外观与小部件",
                summary = "主题模式与桌面收支卡片",
                onClick = { onOpen(SettingsPage.AppearanceWidget) },
            )
        }

        // 低频：装完设一次
        SettingsCard {
            ArrowPreference(
                title = "AI 与高级功能",
                summary = "截屏记账、AI 识图与模型配置",
                onClick = { onOpen(SettingsPage.Features) },
            )
            RowDivider()
            ArrowPreference(
                title = "权限与后台",
                summary = "通知使用权、电池白名单、通知渠道与数据清理",
                onClick = { onOpen(SettingsPage.SysAccess) },
            )
        }

        SettingsCard {
            ArrowPreference(
                title = "关于",
                summary = "版本信息、开源许可与安全设置",
                onClick = { onOpen(SettingsPage.About) },
            )
        }
    }
}

/** 「记账设置」分组页：分类 + 预算。 */
@Composable
private fun LedgerSettingsPage(onOpen: (SettingsPage) -> Unit) {
    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(
                title = "记账分类管理",
                summary = "管理支出、收入和其他分类",
                onClick = { onOpen(SettingsPage.Categories) },
            )
            RowDivider()
            ArrowPreference(
                title = "预算管理",
                summary = "预算金额与起始日",
                onClick = { onOpen(SettingsPage.Budget) },
            )
        }
        HintText("分类决定自动记账时账单被归到哪里；预算只影响首页预算卡片。")
    }
}

/** 「数据与备份」分组页：导入 / 导出 / 云备份。 */
@Composable
private fun DataBackupPage(onOpen: (SettingsPage) -> Unit) {
    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(
                title = "导入数据",
                summary = "支持 Excel / CSV 账单",
                onClick = { onOpen(SettingsPage.Import) },
            )
            RowDivider()
            ArrowPreference(
                title = "导出数据",
                summary = "导出 CSV / XLSX 账单文件",
                onClick = { onOpen(SettingsPage.Export) },
            )
        }
        SettingsCard {
            ArrowPreference(
                title = "云备份",
                summary = "备份到 WebDAV（坚果云 / 群晖等），换机可恢复",
                onClick = { onOpen(SettingsPage.Backup) },
            )
        }
        HintText("导入用于把别的 App 的账单搬进来；云备份用于换机和防丢。")
    }
}

/** 「外观与小部件」分组页：主题 + 桌面小部件。 */
@Composable
private fun AppearanceWidgetPage(prefs: AppPrefs, onOpen: (SettingsPage) -> Unit) {
    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(
                title = "外观",
                summary = when (prefs.themeMode) {
                    "light" -> "浅色"
                    "dark" -> "深色"
                    else -> "跟随系统"
                },
                onClick = { onOpen(SettingsPage.Appearance) },
            )
            RowDivider()
            ArrowPreference(
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
    SettingsPageColumn {
        SettingsCard {
            InfoRow(title = "版本", value = versionName)
            RowDivider()
            InfoRow(title = "包名", value = context.packageName)
        }
        SettingsCard {
            InfoRow(title = "开源许可", value = "Miuix · Compose · Room · Vico")
        }
        HintText(
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
    onParseRules: () -> Unit,
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
    SettingsPageColumn {
        HintText("读取收支信息，自动生成账单。")
        SettingsCard {
            ArrowPreference(
                title = "通知使用权",
                summary = if (ledgerListenerGranted) "已开启，正在监听账单通知" else "未开启，点击授予通知使用权",
                onClick = { LedgerNotificationListener.openSettings(context) },
            )
        }
        SettingsCard {
            SwitchPreference(
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
            RowDivider()
            SwitchPreference(
                title = "支付宝",
                summary = "解析支付宝账单通知",
                checked = prefs.alipayEnabled,
                onCheckedChange = { prefs.alipayEnabled = it },
            )
            RowDivider()
            SwitchPreference(
                title = "微信",
                summary = "解析微信支付账单通知",
                checked = prefs.wechatEnabled,
                onCheckedChange = { prefs.wechatEnabled = it },
            )
            RowDivider()
            SwitchPreference(
                title = "其他",
                summary = "云闪付、抖音、快手、滴滴、美团支付等",
                checked = prefs.otherEnabled,
                onCheckedChange = { prefs.otherEnabled = it },
            )
            RowDivider()
            SwitchPreference(
                title = "截屏记账",
                summary = "接收系统截屏并交给 AI 解析",
                checked = prefs.screenshotEnabled,
                onCheckedChange = { prefs.screenshotEnabled = it },
            )
        }
        SettingsCard {
            ArrowPreference(title = "账单导入", summary = "手动导入微信、支付宝账单", onClick = onImport)
        }
        SettingsCard {
            ArrowPreference(
                title = "高级解析设置",
                summary = "自定义拦截词与强制认账词（${prefs.ignoreKeywords.size} 拦 / ${prefs.forceKeywords.size} 认）",
                onClick = onParseRules,
            )
        }
        SettingsCard {
            ArrowPreference(
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
    var refresh by remember { mutableStateOf(0) }
    val canPromote = remember(refresh) { IslandNotifier.canPostPromoted(context) }
    fun islandSummary(): String {
        val v = IslandNotifier.vendorLabel(IslandNotifier.vendor())
        return if (IslandNotifier.supportsLiveUpdates()) "$v · 实况胶囊（免业务ID）" else "$v · 系统版本较低"
    }
    fun promoteSummary(): String = when {
        !IslandNotifier.supportsLiveUpdates() -> "系统版本较低，仅普通通知"
        canPromote -> "已授权提升；能否上岛由系统决定"
        else -> "未开启，点这里去系统里打开「实时更新 / 焦点通知」"
    }
    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(title = "每日记账提醒", summary = prefs.reminderTime, onClick = onDaily)
            RowDivider()
            SwitchPreference(
                title = "记账日报",
                summary = "每日固定时间提醒",
                checked = prefs.dailyReminder,
                onCheckedChange = {
                    prefs.dailyReminder = it
                    if (it) ReminderScheduler.scheduleDaily(context, prefs.reminderTime)
                    else ReminderScheduler.cancelDaily(context)
                },
            )
            RowDivider()
            SwitchPreference(
                title = "记账周报",
                summary = "每周一上午接收",
                checked = prefs.weeklyReport,
                onCheckedChange = {
                    prefs.weeklyReport = it
                    if (it) ReminderScheduler.scheduleWeekly(context) else ReminderScheduler.cancelWeekly(context)
                },
            )
            RowDivider()
            SwitchPreference(
                title = "记账月报",
                summary = "每月一号上午接收",
                checked = prefs.monthlyReport,
                onCheckedChange = {
                    prefs.monthlyReport = it
                    if (it) ReminderScheduler.scheduleMonthly(context) else ReminderScheduler.cancelMonthly(context)
                },
            )
        }
        SettingsCard {
            ArrowPreference(title = "单笔自动记账通知", summary = "生成自动账单时的提醒方式", onClick = onSingle)
        }
        SettingsCard {
            ArrowPreference(
                title = "立即发送测试提醒",
                summary = "在前台发一条实况通知，验证是否上岛",
                onClick = {
                    IslandNotifier.deliver(
                        context,
                        IslandNotifier.Spec(
                            id = 2099,
                            title = "每日记账提醒",
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
            RowDivider()
            ArrowPreference(
                title = "实况上岛权限",
                summary = promoteSummary(),
                onClick = {
                    if (IslandNotifier.supportsLiveUpdates() && !canPromote) {
                        val ok = runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.isSuccess
                        if (!ok) {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        refresh++
                    }
                },
            )
            RowDivider()
            SwitchPreference(
                title = "超级岛 / 实况提醒",
                summary = islandSummary(),
                checked = prefs.islandLiveUpdates,
                onCheckedChange = { prefs.islandLiveUpdates = it },
            )
        }
    }
}

@Composable
private fun DailyReminderPage(prefs: AppPrefs) {
    val context = LocalContext.current
    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(
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
        HintText("选择通知方式（可多选）")
        SettingsCard {
            SwitchPreference(
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
    SettingsPageColumn {
        HintText("生成自动记账单时的通知方式（可多选）。")
        SettingsCard {
            SwitchPreference(
                title = "超级岛提醒",
                checked = prefs.islandSingle,
                onCheckedChange = { prefs.islandSingle = it },
            )
            RowDivider()
            SwitchPreference(
                title = "常驻消息通知",
                checked = prefs.persistentSingle,
                onCheckedChange = { prefs.persistentSingle = it },
            )
            RowDivider()
            SwitchPreference(
                title = "不提示",
                summary = "生成自动记账单时不提示",
                checked = prefs.singleMuted,
                onCheckedChange = { prefs.singleMuted = it },
            )
        }
    }
}

private data class ManagedCategory(val name: String, val color: Color, val emoji: String)

private val initialExpense = listOf(
    "代付", "其他", "宠物", "人情社交", "亲子", "美容", "还债", "借出", "投资",
    "旅行", "红包", "教育", "医疗", "娱乐", "通讯", "交通", "住房", "购物",
    "餐饮", "消费", "转账",
).map { ManagedCategory(it, AccentBlue, "•") }

private val initialIncome = listOf(
    "其他", "退款", "收债", "借入", "红包", "理财", "薪资", "转账",
).map { ManagedCategory(it, LedgerTheme.income, "•") }

private val initialOther = listOf(
    "代付", "提现", "充值", "还款",
).map { ManagedCategory(it, LedgerTheme.warning, "•") }

private val expenseNames = listOf(
    "代付", "其他", "宠物", "人情社交", "亲子", "美容", "还债", "借出", "投资",
    "旅行", "红包", "教育", "医疗", "娱乐", "通讯", "交通", "住房", "购物",
    "餐饮", "消费", "转账",
)
private val incomeNames = listOf("其他", "退款", "收债", "借入", "红包", "理财", "薪资", "转账")
private val otherNames = listOf("代付", "提现", "充值", "还款")

@Composable
private fun CategoriesPage() {
    val context = LocalContext.current
    var expense by remember { mutableStateOf(loadCategoryList(context, KEY_EXPENSE, expenseNames)) }
    var income by remember { mutableStateOf(loadCategoryList(context, KEY_INCOME, incomeNames)) }
    var other by remember { mutableStateOf(loadCategoryList(context, KEY_OTHER, otherNames)) }
    var editing by remember { mutableStateOf<Pair<List<ManagedCategory>, ManagedCategory>?>(null) }
    var addingTo by remember { mutableStateOf<List<ManagedCategory>?>(null) }
    // 弹窗要常驻组合才能播完退场动画，关闭后仍留一份数据给退场帧渲染。
    var lastEditing by remember { mutableStateOf<Pair<List<ManagedCategory>, ManagedCategory>?>(null) }
    var lastAddingTo by remember { mutableStateOf<List<ManagedCategory>?>(null) }
    LaunchedEffect(editing) { if (editing != null) lastEditing = editing }
    LaunchedEffect(addingTo) { if (addingTo != null) lastAddingTo = addingTo }

    SettingsPageColumn {
        HintText("轻触分类可编辑或删除，末尾的 + 用于新增。")
        CategorySection(
            title = "支出管理",
            items = expense,
            onEdit = { editing = expense to it },
            onAdd = { addingTo = expense },
        )
        CategorySection(
            title = "收入管理",
            items = income,
            onEdit = { editing = income to it },
            onAdd = { addingTo = income },
        )
        CategorySection(
            title = "其他管理",
            items = other,
            onEdit = { editing = other to it },
            onAdd = { addingTo = other },
        )
    }

    lastEditing?.let { (list, item) ->
        CategoryEditDialog(
            show = editing != null,
            title = "编辑分类",
            initialName = item.name,
            onDismiss = { editing = null },
            onDelete = {
                when (list) {
                    expense -> { expense = expense.filterNot { it.name == item.name }; saveCategoryList(context, KEY_EXPENSE, expense) }
                    income -> { income = income.filterNot { it.name == item.name }; saveCategoryList(context, KEY_INCOME, income) }
                    other -> { other = other.filterNot { it.name == item.name }; saveCategoryList(context, KEY_OTHER, other) }
                }
                editing = null
            },
            onSave = { newName ->
                when (list) {
                    expense -> { expense = expense.map { if (it.name == item.name) it.copy(name = newName) else it }; saveCategoryList(context, KEY_EXPENSE, expense) }
                    income -> { income = income.map { if (it.name == item.name) it.copy(name = newName) else it }; saveCategoryList(context, KEY_INCOME, income) }
                    other -> { other = other.map { if (it.name == item.name) it.copy(name = newName) else it }; saveCategoryList(context, KEY_OTHER, other) }
                }
                editing = null
            },
        )
    }
    lastAddingTo?.let { list ->
        CategoryEditDialog(
            show = addingTo != null,
            title = "添加分类",
            initialName = "",
            onDismiss = { addingTo = null },
            onDelete = null,
            onSave = { newName ->
                val item = ManagedCategory(newName, AccentBlue, "•")
                when (list) {
                    expense -> { expense = expense + item; saveCategoryList(context, KEY_EXPENSE, expense) }
                    income -> { income = income + item; saveCategoryList(context, KEY_INCOME, income) }
                    other -> { other = other + item; saveCategoryList(context, KEY_OTHER, other) }
                }
                addingTo = null
            },
        )
    }
}

@Composable
private fun CategorySection(
    title: String,
    items: List<ManagedCategory>,
    onEdit: (ManagedCategory) -> Unit,
    onAdd: () -> Unit,
) {
    Text(
        text = title,
        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    SettingsCard {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            val cells = items + ManagedCategory("添加", AccentBlue, "+")
            cells.chunked(5).forEachIndexed { rowIndex, rowItems ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { item ->
                        CategoryManageCell(
                            item = item,
                            isAdd = item.name == "添加",
                            onClick = { if (item.name == "添加") onAdd() else onEdit(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                if (rowIndex != cells.chunked(5).lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun CategoryManageCell(
    item: ManagedCategory,
    isAdd: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isAdd) AccentBlue.copy(alpha = 0.22f) else item.color.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isAdd) {
                    Icon(imageVector = MiuixIcons.Add, contentDescription = "添加", tint = AccentBlue, modifier = Modifier.size(18.dp))
                } else {
                    Text(text = CategoryStore.emoji(item.name), fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            style =MiuixTheme.textStyles.footnote2.copy(color = TextSecondary),
            textAlign = TextAlign.Center,
        )
    }
}

private const val KEY_EXPENSE = "cat_expense"
private const val KEY_INCOME = "cat_income"
private const val KEY_OTHER = "cat_other"

private fun loadCategoryList(context: android.content.Context, key: String, fallback: List<String>): List<ManagedCategory> {
    val raw = context.getSharedPreferences("auto_ledger", android.content.Context.MODE_PRIVATE).getString(key, "") ?: ""
    val names = if (raw.isBlank()) fallback else runCatching {
        val arr = JSONArray(raw)
        buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
    }.getOrElse { fallback }
    return names.map { ManagedCategory(it, CategoryStore.color(it), CategoryStore.emoji(it)) }
}

private fun saveCategoryList(context: android.content.Context, key: String, list: List<ManagedCategory>) {
    val arr = JSONArray()
    list.forEach { arr.put(it.name) }
    context.getSharedPreferences("auto_ledger", android.content.Context.MODE_PRIVATE)
        .edit().putString(key, arr.toString()).apply()
}

private fun categoryColor(name: String): Color = when (name) {
    "餐饮" -> LedgerTheme.warning
    "购物" -> LedgerTheme.chart4
    "转账" -> AccentBlue
    "交通" -> LedgerTheme.chart5
    "红包" -> LedgerTheme.chart6
    "薪资", "投资" -> LedgerTheme.income
    else -> AccentBlue
}

@Composable
private fun CategoryEditDialog(
    show: Boolean,
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String) -> Unit,
) {
    // 以 show 为 key：重新打开时按 initialName 重建，不残留上一次输入。
    var name by remember(show) { mutableStateOf(initialName) }
    AppDialog(
        show = show,
        maxWidth = 400.dp,
        outsideMargin = DpSize(32.dp, 24.dp),
        panelColor = LedgerTheme.card,
        onDismissRequest = onDismiss,
    ) {
        Text(text = title, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary))
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LedgerTheme.inset)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
            cursorBrush = SolidColor(AccentBlue),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) Text("分类名称", style = MiuixTheme.textStyles.body2.copy(color = TextSecondary))
                    inner()
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onDelete != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LedgerTheme.tint(LedgerTheme.danger))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("删除", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.danger))
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentBlue)
                    .clickable {
                        if (name.isNotBlank()) onSave(name.trim())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.onAccent))
            }
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

    SettingsPageColumn {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("预算金额", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¥", style = TextStyle(fontSize = 22.sp, color = TextPrimary))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 28.sp, color = TextPrimary),
                        cursorBrush = SolidColor(AccentBlue),
                    )
                }
            }
        }
        SettingsCard {
            // 预算起始日：发薪日不是 1 号时，本月 = 起始日 ~ 次月起始日前一天
            ArrowPreference(
                title = "预算起始日",
                summary = "${prefs.budgetStartDay} 日" + if (prefs.budgetStartDay == 1) "（自然月）" else "",
                onClick = { showStartDayPicker = true },
            )
        }
        SettingsCard {
            SwitchPreference(
                title = "首页显示预算卡片",
                summary = "开启后在记账首页展示（最多两个）",
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
                Text("取消", style = MiuixTheme.textStyles.body2.copy(color = TextSecondary))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentBlue)
                    .clickable {
                        prefs.budgetAmount = amount
                        scope.launch { snackbar.show("预算已保存") }
                        onDone()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", style = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.onAccent))
            }
        }
    }

    // 常驻组合、只切 show：外面用 if 包起来的话，一点选或点遮罩组件就被移出组合，
    // AppDialog 接管的退场动画没机会播。
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
    AppDialog(show = show, title = "预算起始日", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选发薪日；预算周期 = 该日 ~ 次月该日前一天。1 号即自然月。",
                style = MiuixTheme.textStyles.footnote1,
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
                            Text(
                                text = day.toString(),
                                style = MiuixTheme.textStyles.body2.copy(
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
                Text("取消", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
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
    SettingsPageColumn {
        HintText("支持导入 Excel / CSV 格式，推荐单次 1MB 以内。")
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AccentBlue)
                .clickable {
                    launcher.launch(arrayOf("*/*"))
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("本地文件导入", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LedgerTheme.onAccent))
        }
    }

    // 教程卡说明对话框：给出可执行的导出路径，而不是一句空提示。
    // 常驻组合、只切 show：外面用 guideFor?.let { } 包起来的话，一点「知道了」组件就被移出组合，
    // AppDialog 接管的退场动画播不出来。关闭后 guideFor 置 null，这里留住最后一次的平台名。
    var lastGuideFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(guideFor) { if (guideFor != null) lastGuideFor = guideFor }
    lastGuideFor?.let { platform ->
        val steps = when (platform) {
            "微信" -> "微信 → 我 → 服务 → 钱包 → 账单 → 右上角「常见问题」→「下载账单」→ 选「用于个人对账」→ 填邮箱收 CSV"
            "支付宝" -> "支付宝 → 我的 → 账单 → 右上角「⋯」→「开具交易流水证明」→ 选「用于个人对账」→ 填邮箱收 CSV"
            else -> "各银行/电商 App 一般在「我的 → 账单 / 订单」里提供导出或邮件发送 CSV/Excel；" +
                    "也可以在对应 App 内搜索「账单导出」查看官方指引。"
        }
        AppDialog(
            show = guideFor != null,
            title = "$platform 账单导出",
            maxWidth = 420.dp,
            outsideMargin = DpSize(24.dp, 24.dp),
            onDismissRequest = { guideFor = null },
        ) {
            Spacer(Modifier.height(10.dp))
            Text(steps, style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LedgerTheme.accent)
                    .clickable { guideFor = null },
                contentAlignment = Alignment.Center,
            ) {
                Text("知道了", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LedgerTheme.onAccent))
            }
        }
    }
}

@Composable
private fun ImportPlatformCard(title: String, summary: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(16.dp), clip = false)
            .border(1.dp, LedgerTheme.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        cornerRadius = 16.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary))
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MiuixTheme.textStyles.footnote2.copy(color = TextSecondary))
        }
    }
}

/** 导出格式：CSV / XLSX 共用同一份筛选结果，只是编码器不同。 */
@Composable
private fun FeaturesPage(prefs: AppPrefs, onAi: () -> Unit) {
    SettingsPageColumn {
        SettingsCard {
            SwitchPreference(
                title = "截屏记账",
                summary = "控制中心快捷开关 + AI 识图",
                checked = prefs.screenshotEnabled,
                onCheckedChange = { prefs.screenshotEnabled = it },
            )
            RowDivider()
            ArrowPreference(title = "AI 模型与自动化", summary = "配置模型、Key 与 Prompt", onClick = onAi)
        }
        HintText("「清空记账数据」已移到「权限与后台」页底部，避免误触。")
    }
}

@Composable
private fun AiPage(prefs: AppPrefs) {
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(prefs.aiBaseUrl) }
    var key by remember { mutableStateOf(prefs.aiKey) }
    var prompt by remember { mutableStateOf(prefs.aiPrompt) }
    var model by remember { mutableStateOf(prefs.aiModel) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

    SettingsPageColumn {
        HintText("您的数据仅发送至您配置的本地/第三方大模型，本 App 不保存截图。")

        // 预设服务商：选一个就把 Base URL 和默认模型名填好，只差一个 Key
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("服务商预设", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                AiPreset.entries.forEachIndexed { index, preset ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    val selected = baseUrl.trimEnd('/') == preset.baseUrl.trimEnd('/')
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) LedgerTheme.accent else LedgerTheme.inset)
                            .clickable {
                                baseUrl = preset.baseUrl
                                if (model.isBlank() || AiPreset.entries.any { it.defaultModel == model }) {
                                    model = preset.defaultModel
                                }
                                scope.launch {
                                    snackbar.show(
                                        if (preset.needsKey) "已填入 ${preset.label}，请补上 API Key"
                                        else "已填入 ${preset.label}，本地服务无需 Key",
                                    )
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.label,
                                style = MiuixTheme.textStyles.body2.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) LedgerTheme.onAccent else LedgerTheme.text,
                                ),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = preset.hint,
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (selected) LedgerTheme.onAccent else LedgerTheme.textSecondary,
                            )
                        }
                        if (selected) {
                            Text(
                                text = "已选",
                                style = MiuixTheme.textStyles.footnote1,
                                color = LedgerTheme.onAccent,
                            )
                        }
                    }
                }
            }
        }

        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("API URL / Base URL", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(8.dp))
                SettingsInput(value = baseUrl, placeholder = "https://api.openai.com/v1", onValueChange = { baseUrl = it })
                Spacer(Modifier.height(16.dp))
                Text("API Key", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(8.dp))
                SettingsInput(
                    value = key,
                    placeholder = "sk-...",
                    onValueChange = { key = it },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentBlue)
                            .clickable {
                                if (baseUrl.isBlank()) {
                                    scope.launch { snackbar.show("请填写 Base URL") }
                                } else {
                                    loading = true
                                    scope.launch {
                                        val result = fetchModels(baseUrl, key)
                                        loading = false
                                        if (result.isSuccess) {
                                            models = result.getOrThrow()
                                            if (models.isNotEmpty()) {
                                                model = models.first()
                                                showModels = true
                                            }
                                        } else {
                                            scope.launch { snackbar.showLong("获取失败：${result.exceptionOrNull()?.message}") }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (loading) "获取中…" else "获取模型列表", style = TextStyle(fontSize = 12.sp, color = LedgerTheme.onAccent))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LedgerTheme.inset)
                            .clickable {
                                if (baseUrl.isBlank() || model.isBlank()) {
                                    scope.launch { snackbar.show("请先填写 URL 并选择模型") }
                                } else {
                                    loading = true
                                    scope.launch {
                                        val result = testConnection(baseUrl, key, model)
                                        loading = false
                                        snackbar.showLong(if (result.isSuccess) "连接成功" else "连接失败：${result.exceptionOrNull()?.message}")
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("测试连接", style = TextStyle(fontSize = 12.sp, color = TextPrimary))
                    }
                }
            }
        }

        SettingsCard {
            ArrowPreference(title = "Model Name", summary = model.ifEmpty { "尚未选择模型" }, onClick = {
                if (models.isNotEmpty()) showModels = true
                else scope.launch { snackbar.show("先获取模型列表") }
            })
        }

        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Prompt 提示词", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LedgerTheme.inset)
                        .padding(12.dp),
                    textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                    cursorBrush = SolidColor(AccentBlue),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LedgerDimens.minTouch)
                .clip(RoundedCornerShape(16.dp))
                .background(AccentBlue)
                .clickable {
                    prefs.aiBaseUrl = baseUrl
                    prefs.aiKey = key
                    prefs.aiModel = model
                    prefs.aiPrompt = prompt
                    scope.launch { snackbar.show("AI 配置已保存") }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("保存配置", style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold, color = LedgerTheme.onAccent))
        }
    }

    AppDialog(
        show = showModels,
        title = "选择模型",
        maxWidth = 420.dp,
        outsideMargin = DpSize(24.dp, 24.dp),
        panelColor = LedgerTheme.card,
        onDismissRequest = { showModels = false },
    ) {
            Text("选择模型", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary))
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                models.forEach { name ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                model = name
                                showModels = false
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = name,
                            style = MiuixTheme.textStyles.footnote1.copy(
                                color = if (name == model) AccentBlue else TextPrimary,
                            ),
                        )
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
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LedgerTheme.inset)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        singleLine = true,
        textStyle = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
        cursorBrush = SolidColor(AccentBlue),
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = MiuixTheme.textStyles.body2.copy(color = TextSecondary))
                inner()
            }
        },
    )
}

private suspend fun fetchModels(baseUrl: String, key: String): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatching {
        val url = endpoint(baseUrl, "/models")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(120)}")
        val data = JSONObject(body).optJSONArray("data") ?: error("响应中没有 data")
        buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }
    }
}

/** 只读信息行：标题在左、值在右，用于展示运行模式与最近采集时间。 */
@Composable
private fun AppearancePage(prefs: AppPrefs) {
    val modes = listOf(
        ThemeMode.SYSTEM to ("跟随系统" to "随系统的深色/浅色开关自动切换"),
        ThemeMode.LIGHT to ("浅色" to "始终使用浅色主题"),
        ThemeMode.DARK to ("深色" to "始终使用深色主题，夜间看账不刺眼"),
    )
    SettingsPageColumn {
        SettingsCard {
            modes.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { prefs.themeMode = entry.first }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.second.first,
                            style = TextStyle(fontSize = 15.sp, color = TextPrimary),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = entry.second.second,
                            style = TextStyle(fontSize = 12.sp, color = TextSecondary),
                        )
                    }
                    if (prefs.themeMode == entry.first) {
                        Text(
                            text = "✓",
                            style = MiuixTheme.textStyles.body1.copy(color = AccentBlue),
                        )
                    }
                }
            }
        }
        HintText("深色模式下页面背景、卡片、文字与强调色都会跟随切换；小部件仍使用自身配色。")
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

    SettingsPageColumn {
        // 快捷模板：小白不必自己去查 WebDAV 地址长什么样
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("快捷模板", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
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

        SettingsCard {
            DavField(
                label = "目录地址",
                hint = "https://dav.jianguoyun.com/dav/auto-ledger",
                value = prefs.davUrl,
                onChange = { prefs.davUrl = it },
            )
            RowDivider()
            DavField(
                label = "账号",
                hint = "坚果云登录邮箱 / 群晖用户名",
                value = prefs.davUser,
                onChange = { prefs.davUser = it },
            )
            RowDivider()
            DavField(
                label = "应用密码",
                hint = "坚果云要填「应用密码」，不是登录密码",
                value = prefs.davPass,
                onChange = { prefs.davPass = it },
                secret = true,
            )
        }

        SettingsCard {
            ArrowPreference(
                title = if (busy) "处理中…" else "立即备份",
                summary = "把全部账目编码成 JSON 上传，云端同名文件会被覆盖",
                onClick = {
                    run("正在备份") { com.eta.autoledger.data.BackupRunner.backup(context, prefs) }
                },
            )
            RowDivider()
            ArrowPreference(
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
            RowDivider()
            ArrowPreference(
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

        SettingsCard {
            SwitchPreference(
                title = "每天自动备份",
                summary = "系统空闲且有网络时执行，间隔至少 20 小时",
                checked = prefs.davAuto,
                onCheckedChange = {
                    prefs.davAuto = it
                    if (it) com.eta.autoledger.service.BackupJobService.schedule(context)
                },
            )
            // 「备份时刻」已隐藏：BackupJobService 固定 6h 周期 + BackupRunner 20h 间隔判定，
            // 从不读取 davAutoTime，该选项只是假配置。
            RowDivider()
            InfoRow(title = "上次结果", value = lastResult.ifBlank { "尚未备份" })
        }

        HintText(
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

/**
 * 常见 OpenAI 兼容服务商预设。
 *
 * 只填 Base URL 与一个「大概率能跑」的默认模型名——模型名会随服务商更新换代，
 * 所以填完仍然鼓励用户点「获取模型列表」做一次校正。
 * 本地 Ollama 不需要 Key，[needsKey] 为 false，界面据此给出不同提示。
 */
private enum class AiPreset(
    val label: String,
    val hint: String,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
) {
    DEEPSEEK(
        label = "DeepSeek",
        hint = "国内直连，价格低，识图需 deepseek-vl 系列",
        baseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        needsKey = true,
    ),
    QWEN(
        label = "通义千问",
        hint = "阿里云百炼，需在控制台开通并建 Key",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-vl-plus",
        needsKey = true,
    ),
    OLLAMA(
        label = "本地 Ollama",
        hint = "电脑/手机本地跑，无需 Key，需填局域网 IP",
        baseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "llava",
        needsKey = false,
    ),
}

/** WebDAV 服务商快捷模板：选中态实心 accent，与分类 Chip 保持同一套视觉。 */
/**
 * 高级解析设置：用户自己维护「拦截词」与「强制认账词」。
 *
 * 规则优先级（与 NotificationRules.parseAs 一致）：
 *   1. 命中强制认账词 → 无视拦截词，直接入账
 *   2. 命中拦截词 → 丢弃，不生成账单
 *   3. 都没命中 → 走内置规则
 *
 * 交互按「先看后改」设计：默认词以 Chip 展示，点 X 即删；下方输入框回车即加。
 * 每次增删立刻写回 AppPrefs，没有「保存」按钮——设置项少，多一个确认步骤反而累。
 */
@Composable
private fun ParseRulesPage(prefs: AppPrefs) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    var ignoreInput by remember { mutableStateOf("") }
    var forceInput by remember { mutableStateOf("") }

    fun addKeyword(raw: String, current: Set<String>, onSet: (Set<String>) -> Unit, label: String) {
        val word = raw.trim()
        if (word.isEmpty()) return
        if (current.contains(word)) {
            scope.launch { snackbar.show("「$word」已在${label}里") }
            return
        }
        onSet(current + word)
    }

    SettingsPageColumn {
        HintText(
            "当一条短信同时包含拦截词和认账词时，以认账词为准。\n" +
                "例：「已支付」在认账词里，所以「花呗已支付 46.94 元」仍会入账。",
        )

        // ── 拦截词 ──────────────────────────────────────────────
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "忽略（拦截）关键词",
                    style = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "短信里出现这些词就不记账。「下单」「先用后付」这类只是占额度、钱还没扣。",
                    style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary),
                )
                Spacer(Modifier.height(12.dp))
                KeywordChips(
                    words = prefs.ignoreKeywords,
                    onRemove = { word -> prefs.ignoreKeywords = prefs.ignoreKeywords - word },
                )
                Spacer(Modifier.height(12.dp))
                KeywordInput(
                    value = ignoreInput,
                    placeholder = "输入词语后回车添加，如「抖音月付」",
                    onValueChange = { ignoreInput = it },
                    onSubmit = {
                        addKeyword(ignoreInput, prefs.ignoreKeywords, { prefs.ignoreKeywords = it }, "拦截词")
                        ignoreInput = ""
                    },
                )
            }
        }

        // ── 认账词 ──────────────────────────────────────────────
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "强制认账关键词",
                    style = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "出现这些词就认定钱真的动了，即使同时命中拦截词也会入账。",
                    style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary),
                )
                Spacer(Modifier.height(12.dp))
                KeywordChips(
                    words = prefs.forceKeywords,
                    onRemove = { word -> prefs.forceKeywords = prefs.forceKeywords - word },
                )
                Spacer(Modifier.height(12.dp))
                KeywordInput(
                    value = forceInput,
                    placeholder = "输入词语后回车添加，如「扣费成功」",
                    onValueChange = { forceInput = it },
                    onSubmit = {
                        addKeyword(forceInput, prefs.forceKeywords, { prefs.forceKeywords = it }, "认账词")
                        forceInput = ""
                    },
                )
            }
        }

        // ── 恢复默认 ────────────────────────────────────────────
        SettingsCard {
            ArrowPreference(
                title = "恢复默认词库",
                summary = "把两组词都还原成内置预设，你手动加的会被清掉",
                onClick = {
                    prefs.ignoreKeywords = AppPrefs.DEFAULT_IGNORE_KEYWORDS
                    prefs.forceKeywords = AppPrefs.DEFAULT_FORCE_KEYWORDS
                    scope.launch { snackbar.show("已恢复默认词库") }
                },
            )
        }

        HintText("修改即时生效，下一条短信或通知就会按新词库判断。")
    }
}

/** 词条以可删除 Chip 展示，用 FlowRow 自动换行，词多也不会被挤出屏幕。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordChips(words: Set<String>, onRemove: (String) -> Unit) {
    if (words.isEmpty()) {
        Text(
            "还没有任何词，短信将完全走内置规则。",
            style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textTertiary),
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.forEach { word ->
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(LedgerTheme.inset)
                    .border(1.dp, LedgerTheme.borderSoft, shape)
                    .clickable { onRemove(word) }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    word,
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.text),
                )
                Spacer(Modifier.width(4.dp))
                // 用 × 而不是图标：这行字太小，图标在这里认不出是「删除」
                Text(
                    "×",
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textTertiary),
                )
            }
        }
    }
}

/** 单行输入框，回车提交。 */
@Composable
private fun KeywordInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LedgerTheme.inset)
            .border(1.dp, LedgerTheme.borderSoft, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.textTertiary),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.text),
                cursorBrush = SolidColor(LedgerTheme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
        }
        Spacer(Modifier.width(8.dp))
        val enabled = value.isNotBlank()
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) LedgerTheme.accent else LedgerTheme.surfaceVariant)
                .clickable(enabled = enabled, onClick = onSubmit)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "添加",
                style = MiuixTheme.textStyles.footnote1.copy(
                    color = if (enabled) LedgerTheme.onAccent else LedgerTheme.textTertiary,
                ),
            )
        }
    }
}

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
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1.copy(
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
        Text(text = label, style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary))
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LedgerTheme.inset)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = TextStyle(fontSize = 12.sp, color = LedgerTheme.textSecondary),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.footnote1.copy(color = TextPrimary),
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

private suspend fun testConnection(baseUrl: String, key: String, model: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val url = endpoint(baseUrl, "/chat/completions")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1)
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            }))
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(120)}")
    }
}

private fun endpoint(baseUrl: String, path: String): String {
    var base = baseUrl.trim().trimEnd('/')
    if (!base.endsWith("/v1")) base += "/v1"
    return base + path
}