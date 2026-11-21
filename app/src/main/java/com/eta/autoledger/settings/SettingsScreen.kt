package com.eta.autoledger.settings

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.glass.glassHueDrag
import com.eta.autoledger.ui.glass.GlassCard
import com.eta.autoledger.ui.glass.GlassAmbience
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import com.eta.autoledger.ui.glass.LiquidSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.CsvImporter
import com.eta.autoledger.data.XlsxImporter
import com.eta.autoledger.data.XlsxExporter
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.reminder.ReminderScheduler
import com.eta.autoledger.service.LedgerNotificationListener
import com.eta.autoledger.ui.theme.ThemeMode
import com.eta.autoledger.widget.LedgerWidgetProvider


enum class SettingsPage(val title: String, val parent: SettingsPage?) {
    Main("设置", null),
    Auto("自动记账", Main),
    Notifications("记账通知", Main),
    DailyReminder("每日记账提醒", Notifications),
    SingleNotify("单笔自动记账通知", Notifications),
    Categories("记账分类管理", Main),
    Features("功能管理", Main),
    Budget("预算管理", Main),
    Import("导入数据", Main),
    Export("导出数据", Main),
    Widget("添加桌面小部件", Main),
    Ai("AI 模型与自动化", Main),
    SysAccess("系统权限与保活", Main),
    Appearance("外观", Main),
    Backup("云备份", Main),
}

@Composable
fun SettingsScreen(initialPage: SettingsPage = SettingsPage.Main, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val context = LocalContext.current
    val prefs = remember(context) { AppPrefs.get(context) }

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
            when (page) {
                SettingsPage.Main -> MainPage(onOpen = { page = it })
                SettingsPage.SysAccess -> SystemAccessPage(prefs = prefs)
                SettingsPage.Auto -> AutoPage(prefs = prefs, onImport = { page = SettingsPage.Import })
                SettingsPage.Notifications -> NotificationsPage(
                    prefs = prefs,
                    onDaily = { page = SettingsPage.DailyReminder },
                    onSingle = { page = SettingsPage.SingleNotify },
                )
                SettingsPage.DailyReminder -> DailyReminderPage(prefs)
                SettingsPage.SingleNotify -> SingleNotifyPage(prefs)
                SettingsPage.Categories -> CategoriesPage()
                SettingsPage.Features -> FeaturesPage(prefs, onAi = { page = SettingsPage.Ai })
                SettingsPage.Budget -> BudgetPage(prefs, onDone = { page = SettingsPage.Main })
                SettingsPage.Import -> ImportPage()
                SettingsPage.Export -> ExportPage()
                SettingsPage.Widget -> WidgetPage()
                SettingsPage.Ai -> AiPage(prefs)
                SettingsPage.Appearance -> AppearancePage(prefs = prefs)
                SettingsPage.Backup -> BackupPage(prefs = prefs)
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
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
        )
    }
}

@Composable
private fun SettingsAmbienceCard() {
    val hue = GlassAmbience.hue
    GlassCard(
        modifier = Modifier.fillMaxWidth().glassHueDrag(),
        veil = LedgerTheme.surface.copy(alpha = 0.45f),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "氛围色", style = MiuixTheme.textStyles.title4, color = LedgerTheme.text, modifier = Modifier.weight(1f))
                Text(text = "%.0f°".format(hue), style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "横向拖动这条卡片，改变全局玻璃氛围色",
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.hsv(0f, 0.6f, 0.9f),
                                Color.hsv(60f, 0.6f, 0.9f),
                                Color.hsv(120f, 0.6f, 0.9f),
                                Color.hsv(180f, 0.6f, 0.9f),
                                Color.hsv(240f, 0.6f, 0.9f),
                                Color.hsv(300f, 0.6f, 0.9f),
                                Color.hsv(360f, 0.6f, 0.9f),
                            )
                        )
                    ),
            )
        }
    }
}

@Composable
private fun MainPage(onOpen: (SettingsPage) -> Unit) {
    SettingsPageColumn {
        SettingsAmbienceCard()
        SettingsCard {
            ArrowPreference(title = "自动记账", summary = "读取收支信息，自动生成账单", onClick = { onOpen(SettingsPage.Auto) })
            RowDivider()
            ArrowPreference(title = "记账通知", summary = "每日提醒、周期账单与单笔通知", onClick = { onOpen(SettingsPage.Notifications) })
            RowDivider()
            ArrowPreference(title = "记账分类管理", summary = "管理支出、收入和其他分类", onClick = { onOpen(SettingsPage.Categories) })
            RowDivider()
            ArrowPreference(
                title = "外观",
                summary = "跟随系统 / 浅色 / 深色",
                onClick = { onOpen(SettingsPage.Appearance) },
            )
        }
        SettingsCard {
            ArrowPreference(
                title = "系统权限与保活",
                summary = "通知使用权、短信权限、电池白名单与通知渠道状态",
                onClick = { onOpen(SettingsPage.SysAccess) },
            )
            RowDivider()
            ArrowPreference(
                title = "云备份",
                summary = "备份到 WebDAV（坚果云 / 群晖等），换机可恢复",
                onClick = { onOpen(SettingsPage.Backup) },
            )
        }
        SettingsCard {
            ArrowPreference(title = "功能管理", summary = "小部件、截屏记账与通知权限", onClick = { onOpen(SettingsPage.Features) })
            RowDivider()
            ArrowPreference(title = "预算管理", summary = "设置预算金额、周期与提醒", onClick = { onOpen(SettingsPage.Budget) })
        }
        SettingsCard {
            ArrowPreference(title = "导入数据", summary = "支持 Excel / CSV 账单", onClick = { onOpen(SettingsPage.Import) })
            RowDivider()
ArrowPreference(title = "导出数据", summary = "导出 CSV / XLSX 账单文件", onClick = { onOpen(SettingsPage.Export) })
        }
        SettingsCard {
            ArrowPreference(title = "添加桌面小部件", summary = "在桌面展示今日与本月收支", onClick = { onOpen(SettingsPage.Widget) })
        }
        SettingsCard {
            ArrowPreference(title = "AI 模型与自动化", summary = "配置本地/第三方大模型，用于识图记账", onClick = { onOpen(SettingsPage.Ai) })
        }
    }
}

@Composable
private fun AutoPage(prefs: AppPrefs, onImport: () -> Unit) {
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
            Toast.makeText(context, "未授予短信权限，短信记账不会生效", Toast.LENGTH_SHORT).show()
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
    }
}

@Composable
private fun NotificationsPage(prefs: AppPrefs, onDaily: () -> Unit, onSingle: () -> Unit) {
    val context = LocalContext.current
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
                checked = prefs.islandDaily,
                onCheckedChange = { prefs.islandDaily = it },
            )
            RowDivider()
            SwitchPreference(
                title = "日历提醒",
                checked = prefs.calendarDaily,
                onCheckedChange = { prefs.calendarDaily = it },
            )
            RowDivider()
            SwitchPreference(
                title = "不提示",
                summary = "取消所有每日提醒",
                checked = prefs.dailyMuted,
                onCheckedChange = {
                    prefs.dailyMuted = it
                    if (it) {
                        prefs.islandDaily = false
                        prefs.calendarDaily = false
                    }
                },
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

    editing?.let { (list, item) ->
        CategoryEditDialog(
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
    addingTo?.let { list ->
        CategoryEditDialog(
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
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    SettingsCard {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
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
                if (rowIndex != cells.chunked(5).lastIndex) Spacer(Modifier.height(10.dp))
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
                    .clip(RoundedCornerShape(11.dp))
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
            style = TextStyle(fontSize = 10.sp, color = TextSecondary),
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
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(LedgerTheme.card)
                .padding(20.dp),
        ) {
            Column {
                Text(text = title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LedgerTheme.inset)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                    cursorBrush = SolidColor(AccentBlue),
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) Text("分类名称", style = TextStyle(fontSize = 14.sp, color = TextSecondary))
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onDelete != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LedgerTheme.tint(LedgerTheme.danger))
                                .clickable(onClick = onDelete),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("删除", style = TextStyle(fontSize = 13.sp, color = LedgerTheme.danger))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LedgerTheme.inset)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消", style = TextStyle(fontSize = 13.sp, color = TextSecondary))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentBlue)
                            .clickable {
                                if (name.isNotBlank()) onSave(name.trim())
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("保存", style = TextStyle(fontSize = 13.sp, color = LedgerTheme.onAccent))
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetPage(prefs: AppPrefs, onDone: () -> Unit) {
    val context = LocalContext.current
    var amount by remember { mutableStateOf(prefs.budgetAmount) }
    var startDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Calendar.getInstance().time)) }

    SettingsPageColumn {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("预算金额", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¥", style = TextStyle(fontSize = 22.sp, color = TextPrimary))
                    Spacer(Modifier.width(6.dp))
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
            ArrowPreference(
                title = "开始时间",
                summary = startDate,
                onClick = {
                    val c = Calendar.getInstance()
                    DatePickerDialog(context, { _, y, m, d ->
                        startDate = "%04d-%02d-%02d".format(y, m + 1, d)
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                },
            )
            RowDivider()
            ArrowPreference(
                title = "重复周期",
                summary = prefs.budgetRepeat,
                onClick = {
                    prefs.budgetRepeat = when (prefs.budgetRepeat) {
                        "每周" -> "每月"
                        "每月" -> "每年"
                        else -> "每周"
                    }
                },
            )
            RowDivider()
            ArrowPreference(
                title = "结束方式",
                summary = prefs.budgetEnd,
                onClick = {
                    prefs.budgetEnd = when (prefs.budgetEnd) {
                        "永不结束" -> "按日期结束"
                        "按日期结束" -> "按次数结束"
                        else -> "永不结束"
                    }
                },
            )
            RowDivider()
            ArrowPreference(
                title = "预算提醒",
                summary = "剩余 ${prefs.budgetReminder}%",
                onClick = {
                    prefs.budgetReminder = when (prefs.budgetReminder) {
                        80 -> 50
                        50 -> 20
                        else -> 80
                    }
                },
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
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LedgerTheme.inset)
                    .clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = TextStyle(fontSize = 14.sp, color = TextSecondary))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentBlue)
                    .clickable {
                        prefs.budgetAmount = amount
                        Toast.makeText(context, "预算已保存", Toast.LENGTH_SHORT).show()
                        onDone()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", style = TextStyle(fontSize = 14.sp, color = LedgerTheme.onAccent))
            }
        }
    }
}

@Composable
private fun ImportPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            Toast.makeText(context, "正在导入，请稍候…", Toast.LENGTH_SHORT).show()
            scope.launch {
                // 解析任意用户文件，畸形 CSV/XLSX/编码异常都可能抛异常，必须兜住否则直接崩溃
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val mime = context.contentResolver.getType(uri).orEmpty()
                        val name = uri.lastPathSegment.orEmpty()
                        if (mime.contains("spreadsheetml") || name.endsWith(".xlsx", true)) {
                            XlsxImporter.import(context, uri)
                        } else {
                            val text = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.readText().orEmpty()
                            CsvImporter.import(context, text)
                        }
                    }
                }
                result.onSuccess { count ->
                    Toast.makeText(context, "导入完成：新增 $count 笔，重复记录已跳过", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "导入失败：文件格式无法识别（${e.message ?: "解析错误"}）", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    SettingsPageColumn {
        HintText("支持导入 Excel / CSV 格式，推荐单次 1MB 以内。")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImportPlatformCard("微信", "账单导入教程", Modifier.weight(1f)) {
                Toast.makeText(context, "微信账单导入教程", Toast.LENGTH_SHORT).show()
            }
            ImportPlatformCard("支付宝", "账单导入教程", Modifier.weight(1f)) {
                Toast.makeText(context, "支付宝账单导入教程", Toast.LENGTH_SHORT).show()
            }
            ImportPlatformCard("其他平台", "通用导入教程", Modifier.weight(1f)) {
                Toast.makeText(context, "通用导入教程", Toast.LENGTH_SHORT).show()
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
}

@Composable
private fun ImportPlatformCard(title: String, summary: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(16.dp), clip = false)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        cornerRadius = 16.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
            Spacer(Modifier.height(4.dp))
            Text(summary, style = TextStyle(fontSize = 11.sp, color = TextSecondary))
        }
    }
}

/** 导出格式：CSV / XLSX 共用同一份筛选结果，只是编码器不同。 */
@Composable
private fun FeaturesPage(prefs: AppPrefs, onAi: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
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
            RowDivider()
            ArrowPreference(
                title = "通知使用权",
                summary = if (LedgerNotificationListener.isEnabled(context)) "已开启，正在监听账单通知" else "未开启，点击授予通知使用权",
                onClick = { LedgerNotificationListener.openSettings(context) },
            )
            RowDivider()
            ArrowPreference(
                title = "无障碍截图（可选）",
                summary = if (com.eta.autoledger.screenshot.ScreenshotAccessibilityService.isEnabled()) "已开启，可直接截屏" else "开启后无需 Root/录屏授权即可截屏",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
            RowDivider()
            ArrowPreference(
                title = "清空记账数据",
                summary = "删除全部账目，操作不可恢复",
                onClick = { showClearConfirm = true },
            )
        }
    }

    if (showClearConfirm) {
        Dialog(onDismissRequest = { showClearConfirm = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(LedgerTheme.card)
                    .padding(20.dp),
            ) {
                Column {
                    Text("清空所有账目？", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
                    Spacer(Modifier.height(8.dp))
                    Text("该操作不可恢复，确定要删除全部记账数据吗？", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(LedgerTheme.inset).clickable { showClearConfirm = false },
                            contentAlignment = Alignment.Center,
                        ) { Text("取消", style = TextStyle(fontSize = 13.sp, color = TextSecondary)) }
                        Box(
                            modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(LedgerTheme.tint(LedgerTheme.danger)).clickable {
                                    scope.launch {
                                        LedgerRepository.clear(context)
                                    }
                                    showClearConfirm = false
                                    Toast.makeText(context, "已清空记账数据", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text("清空", style = TextStyle(fontSize = 13.sp, color = LedgerTheme.danger)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPage(prefs: AppPrefs) {
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

        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("API URL / Base URL", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(6.dp))
                SettingsInput(value = baseUrl, placeholder = "https://api.openai.com/v1", onValueChange = { baseUrl = it })
                Spacer(Modifier.height(14.dp))
                Text("API Key", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(6.dp))
                SettingsInput(
                    value = key,
                    placeholder = "sk-...",
                    onValueChange = { key = it },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentBlue)
                            .clickable {
                                if (baseUrl.isBlank()) {
                                    Toast.makeText(context, "请填写 Base URL", Toast.LENGTH_SHORT).show()
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
                                            Toast.makeText(context, "获取失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
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
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LedgerTheme.inset)
                            .clickable {
                                if (baseUrl.isBlank() || model.isBlank()) {
                                    Toast.makeText(context, "请先填写 URL 并选择模型", Toast.LENGTH_SHORT).show()
                                } else {
                                    loading = true
                                    scope.launch {
                                        val result = testConnection(baseUrl, key, model)
                                        loading = false
                                        Toast.makeText(
                                            context,
                                            if (result.isSuccess) "连接成功" else "连接失败：${result.exceptionOrNull()?.message}",
                                            Toast.LENGTH_LONG,
                                        ).show()
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
                else Toast.makeText(context, "先获取模型列表", Toast.LENGTH_SHORT).show()
            })
        }

        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Prompt 提示词", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(6.dp))
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
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AccentBlue)
                .clickable {
                    prefs.aiBaseUrl = baseUrl
                    prefs.aiKey = key
                    prefs.aiModel = model
                    prefs.aiPrompt = prompt
                    Toast.makeText(context, "AI 配置已保存", Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("保存配置", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LedgerTheme.onAccent))
        }
    }

    if (showModels) {
        Dialog(onDismissRequest = { showModels = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(LedgerTheme.card)
                    .padding(16.dp),
            ) {
                Column {
                    Text("选择模型", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                        models.forEach { name ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        model = name
                                        showModels = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = name,
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        color = if (name == model) AccentBlue else TextPrimary,
                                    ),
                                )
                            }
                        }
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
        textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
        cursorBrush = SolidColor(AccentBlue),
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = TextStyle(fontSize = 14.sp, color = TextSecondary))
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
                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                            style = TextStyle(fontSize = 16.sp, color = AccentBlue),
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf(prefs.davLastResult) }

    fun run(label: String, block: suspend () -> com.eta.autoledger.data.BackupRunner.Result) {
        if (busy) return
        busy = true
        Toast.makeText(context, "$label…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = block()
            lastResult = prefs.davLastResult
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            busy = false
        }
    }

    SettingsPageColumn {
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
                summary = "读取云端最新备份，按去重规则合并进本地",
                onClick = {
                    run("正在恢复") { com.eta.autoledger.data.BackupRunner.restore(context, prefs) }
                },
            )
            RowDivider()
            ArrowPreference(
                title = "测试连接",
                summary = "建目录 + 上传探针文件，验证地址与凭据",
                onClick = {
                    if (busy) {
                        Toast.makeText(context, "上一个操作还没结束", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
            RowDivider()
            ArrowPreference(
                title = "备份时刻",
                summary = "建议设在夜间（实际执行受系统调度影响，可能顺延）",
                onClick = {
                    val parts = prefs.davAutoTime.split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 3
                    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    TimePickerDialog(
                        context,
                        { _, h, m -> prefs.davAutoTime = "%02d:%02d".format(h, m) },
                        hour,
                        minute,
                        true,
                    ).show()
                },
            )
            RowDivider()
            InfoRow(title = "上次结果", value = lastResult.ifBlank { "尚未备份" })
        }

        HintText(
            "坚果云：网页版「账户信息 → 安全选项 → 添加应用密码」，用那串密码填上面的「应用密码」。\n" +
                "群晖：控制面板启用 WebDAV Server 后，地址填 https://你的域名:5006/备份目录。\n" +
                "备份文件为 JSON（不是数据库文件），换机后先装 App，再点「从云端恢复」即可。",
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
        Text(text = label, style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LedgerTheme.inset)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = TextStyle(fontSize = 12.sp, color = LedgerTheme.textTertiary),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
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