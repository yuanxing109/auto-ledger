package com.eta.autoledger.settings

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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.CsvImporter
import com.eta.autoledger.data.XlsxImporter
import com.eta.autoledger.data.XlsxExporter
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.reminder.ReminderScheduler
import com.eta.autoledger.service.LedgerNotificationListener
import com.eta.autoledger.widget.LedgerWidgetProvider

private val PageBg = Color(0xFFF2F7FF)
private val AccentBlue = Color(0xFF2F6BFF)
private val TextPrimary = Color(0xFF1B1C1E)
private val TextSecondary = Color(0xFF8A94A6)
private val CardBorder = Color(0xFFE1ECFF)

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
}

@Composable
fun SettingsScreen(initialPage: SettingsPage = SettingsPage.Main, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val context = LocalContext.current
    val prefs = remember(context) { AppPrefs(context) }

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
private fun SettingsPageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, shape, clip = false)
            .border(1.dp, CardBorder, shape),
        cornerRadius = 18.dp,
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(Color(0xFFEAF0FA)),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 12.sp, color = TextSecondary),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun MainPage(onOpen: (SettingsPage) -> Unit) {
    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(title = "自动记账", summary = "读取收支信息，自动生成账单", onClick = { onOpen(SettingsPage.Auto) })
            RowDivider()
            ArrowPreference(title = "记账通知", summary = "每日提醒、周期账单与单笔通知", onClick = { onOpen(SettingsPage.Notifications) })
            RowDivider()
            ArrowPreference(title = "记账分类管理", summary = "管理支出、收入和其他分类", onClick = { onOpen(SettingsPage.Categories) })
        }
        SettingsCard {
            ArrowPreference(
                title = "系统权限与保活",
                summary = "通知使用权、短信权限、电池白名单与通知渠道状态",
                onClick = { onOpen(SettingsPage.SysAccess) },
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
                title = "小爱建议提醒",
                checked = prefs.assistantDaily,
                onCheckedChange = { prefs.assistantDaily = it },
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
                        prefs.assistantDaily = false
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
).map { ManagedCategory(it, Color(0xFF5B8DEF), "•") }

private val initialIncome = listOf(
    "其他", "退款", "收债", "借入", "红包", "理财", "薪资", "转账",
).map { ManagedCategory(it, Color(0xFF34C759), "•") }

private val initialOther = listOf(
    "代付", "提现", "充值", "还款",
).map { ManagedCategory(it, Color(0xFFFF9F43), "•") }

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
                val item = ManagedCategory(newName, Color(0xFF5B8DEF), "•")
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
                    .background(if (isAdd) AccentBlue.copy(alpha = 0.12f) else item.color.copy(alpha = 0.18f)),
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
    "餐饮" -> Color(0xFFFF9F43)
    "购物" -> Color(0xFFB57BFF)
    "转账" -> AccentBlue
    "交通" -> Color(0xFF5AC8FA)
    "红包" -> Color(0xFFFF5E7A)
    "薪资", "投资" -> Color(0xFF34C759)
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
                .background(Color.White)
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
                        .background(Color(0xFFF4F7FD))
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
                                .background(Color(0xFFFFE9EC))
                                .clickable(onClick = onDelete),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("删除", style = TextStyle(fontSize = 13.sp, color = Color(0xFFFF5E7A)))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF2F5FA))
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
                        Text("保存", style = TextStyle(fontSize = 13.sp, color = Color.White))
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
                    .background(Color(0xFFF2F5FA))
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
                Text("保存", style = TextStyle(fontSize = 14.sp, color = Color.White))
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
                val count = withContext(Dispatchers.IO) {
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
                Toast.makeText(context, "导入完成：新增 $count 笔，重复记录已跳过", Toast.LENGTH_SHORT).show()
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
            Text("本地文件导入", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
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
private enum class ExportFormat(
    val label: String,
    val hint: String,
    val extension: String,
    val mime: String,
) {
    Csv("CSV", "体积小，表格软件直接打开", "csv", "text/csv"),
    Xlsx("XLSX", "带列宽，Excel / WPS 原生格式", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

@Composable
private fun ExportPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monthStart = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.time)
    }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(System.currentTimeMillis()) }
    var start by remember { mutableStateOf(monthStart) }
    var end by remember { mutableStateOf(today) }
    var format by remember { mutableStateOf(ExportFormat.Csv) }
    var working by remember { mutableStateOf(false) }

    fun pickDate(onSet: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(context, { _, y, m, d ->
            onSet("%04d-%02d-%02d".format(y, m + 1, d))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    SettingsPageColumn {
        SettingsCard {
            ArrowPreference(title = "开始时间", summary = start.ifEmpty { "请选择" }, onClick = { pickDate { start = it } })
            RowDivider()
            ArrowPreference(title = "结束时间", summary = end.ifEmpty { "请选择" }, onClick = { pickDate { end = it } })
        }
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("导出格式", style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExportFormat.entries.forEach { item ->
                        val selected = format == item
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) Color(0xFFE8F0FF) else Color(0xFFF4F7FD))
                                .border(
                                    1.dp,
                                    if (selected) AccentBlue else Color(0x00000000),
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { format = item }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            Text(
                                item.label,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) AccentBlue else TextPrimary,
                                ),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(item.hint, style = TextStyle(fontSize = 11.sp, color = TextSecondary))
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (working) Color(0xFF9DBBF7) else AccentBlue)
                .clickable(enabled = !working) {
                    if (start.isEmpty() || end.isEmpty()) {
                        Toast.makeText(context, "请先选择开始时间和结束时间", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    val targetFormat = format
                    working = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching<Pair<Int, File>?> {
                                val from = LedgerRepository.parseTime("$start 00:00")?.timeInMillis ?: 0L
                                val to = LedgerRepository.parseTime("$end 23:59")?.timeInMillis ?: Long.MAX_VALUE
                                val entries = LedgerRepository.load(context)
                                    .filter { entry ->
                                        val stamp = if (entry.timeMillis > 0) {
                                            entry.timeMillis
                                        } else {
                                            LedgerRepository.parseTime(entry.time)?.timeInMillis ?: 0L
                                        }
                                        stamp in from..to
                                    }
                                    .sortedByDescending { it.timeMillis }
                                if (entries.isEmpty()) {
                                    null
                                } else {
                                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                    val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                                        .format(System.currentTimeMillis())
                                    val file = File(dir, "账单_$fileStamp.${targetFormat.extension}")
                                    if (targetFormat == ExportFormat.Csv) {
                                        file.writeText("\uFEFF" + CsvImporter.export(entries), Charsets.UTF_8)
                                    } else {
                                        XlsxExporter.write(entries, file)
                                    }
                                    entries.size to file
                                }
                            }
                        }
                        working = false
                        outcome.onSuccess { payload ->
                            if (payload == null) {
                                Toast.makeText(context, "该时间段没有账单", Toast.LENGTH_SHORT).show()
                            } else {
                                val (count, file) = payload
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = targetFormat.mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, "账单一共 $count 笔（${targetFormat.label}）")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "导出账单"))
                                Toast.makeText(context, "已生成 $count 笔账单 ${targetFormat.label}", Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { error ->
                            Toast.makeText(context, "导出失败：${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (working) "正在导出…" else "导出",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
            )
        }
    }
}

@Composable
private fun WidgetPage() {
    val context = LocalContext.current
    SettingsPageColumn {
        HintText("在桌面长按空白处，进入「添加小部件」，找到「自动记账」即可添加。")
        SettingsCard {
            ArrowPreference(title = "今日收支", summary = "2×2 小部件", onClick = {
                LedgerWidgetProvider.requestPin(context)
            })
            RowDivider()
            ArrowPreference(title = "本月概览", summary = "4×2 小部件", onClick = {
                LedgerWidgetProvider.requestPin(context)
            })
        }
    }
}

@Composable
private fun FeaturesPage(prefs: AppPrefs, onAi: () -> Unit) {
    val context = LocalContext.current
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
                    .background(Color.White)
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
                                .background(Color(0xFFF2F5FA)).clickable { showClearConfirm = false },
                            contentAlignment = Alignment.Center,
                        ) { Text("取消", style = TextStyle(fontSize = 13.sp, color = TextSecondary)) }
                        Box(
                            modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFE9EC)).clickable {
                                    LedgerRepository.clear(context)
                                    showClearConfirm = false
                                    Toast.makeText(context, "已清空记账数据", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text("清空", style = TextStyle(fontSize = 13.sp, color = Color(0xFFFF5E7A))) }
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
                        Text(if (loading) "获取中…" else "获取模型列表", style = TextStyle(fontSize = 12.sp, color = Color.White))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF2F5FA))
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
                        .background(Color(0xFFF4F7FD))
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
            Text("保存配置", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
        }
    }

    if (showModels) {
        Dialog(onDismissRequest = { showModels = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
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
            .background(Color(0xFFF4F7FD))
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
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 15.sp, color = TextPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = TextStyle(fontSize = 13.sp, color = TextSecondary))
    }
}

private fun formatStamp(value: Long): String =
    if (value <= 0L) "从未" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))

/**
 * 系统权限与保活体检页。
 *
 * 这些开关都没有 API 能直接申请（属于用户级设置），所以这里只做「读状态 + 一键跳转」，
 * 并把两条采集通道的最近入账时间摊开，用于回答「为什么没记账」。
 */
@Composable
private fun SystemAccessPage(prefs: AppPrefs) {
    val context = LocalContext.current
    var beat by remember { mutableIntStateOf(0) }

    // 从系统设置返回时本页不会重建，用 1.5s 轮询把状态刷新回来
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            beat++
        }
    }

    val listenerOk = remember(beat) { SystemAccess.notificationListenerGranted(context) }
    val accessOk = remember(beat) { SystemAccess.accessibilityGranted(context) }
    val smsOk = remember(beat) { SystemAccess.smsGranted(context) }
    val notifOk = remember(beat) { SystemAccess.notificationGranted(context) }
    val batteryOk = remember(beat) { SystemAccess.batteryUnrestricted(context) }
    val exactOk = remember(beat) { SystemAccess.exactAlarmAllowed(context) }
    val autoChannelOk = remember(beat) { SystemAccess.channelEnabled(context, SystemAccess.CHANNEL_AUTO) }
    val shotChannelOk = remember(beat) { SystemAccess.channelEnabled(context, SystemAccess.CHANNEL_SHOT_RESULT) }
    val moduleOk = remember(beat) { SystemAccess.moduleActive(context) }
    // 是否拿到 adb 授予的 WRITE_SECURE_SETTINGS —— 决定「一键修复」能否真写回
    val secureOk = remember(beat) { SystemAccess.canWriteSecureSettings(context) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prefs.smsEnabled = true
        } else {
            Toast.makeText(context, "未授予短信权限，短信记账不会生效", Toast.LENGTH_SHORT).show()
            prefs.smsEnabled = false
        }
        beat++
    }

    SettingsPageColumn {
        HintText("自动记账靠下面这些系统开关。哪一项没开，对应通道就会静默失效——排查「为什么没记账」时按这个顺序看。")

        SettingsCard {
            ArrowPreference(
                title = "一键修复",
                summary = when {
                    !secureOk -> "未授权自愈（先执行下方 adb 命令，再回来点这里）"
                    listenerOk && accessOk -> "✅ 两条通道都正常，无需修复"
                    else -> "点一下，把被系统关掉的通道写回去"
                },
                onClick = {
                    if (!secureOk) {
                        Toast.makeText(
                            context,
                            "需要先用 adb 授予 WRITE_SECURE_SETTINGS",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        val (notifyFixed, accessFixed) = SystemAccess.ensureGrants(context)
                        val fixed = if (notifyFixed) "已开启" else "仍失败"
                        val accessText = if (accessFixed) "已开启" else "仍失败"
                        Toast.makeText(
                            context,
                            "修复结果：通知使用权=$fixed，无障碍=$accessText",
                            Toast.LENGTH_LONG,
                        ).show()
                        beat++
                    }
                },
            )
            RowDivider()
            ArrowPreference(
                title = "adb 授权自愈",
                summary = if (secureOk) {
                    "✅ 已授权：通道被系统关掉会自动写回"
                } else {
                    "${SystemAccess.adbGrantCommand(context)}\n（点一下复制命令，在电脑终端执行一次）"
                },
                onClick = {
                    val command = SystemAccess.adbGrantCommand(context)
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    runCatching {
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
                    }
                    Toast.makeText(context, "已复制 adb 命令", Toast.LENGTH_SHORT).show()
                },
            )
        }

        SettingsCard {
            ArrowPreference(
                title = "通知使用权",
                summary = if (listenerOk) {
                    "✅ 已开启（通知通道工作中）"
                } else {
                    "⚠️ 未开启（通知通道完全不工作；若开关是灰的，先到应用信息页「允许受限设置」）"
                },
                onClick = { SystemAccess.openNotificationListener(context) },
            )
            RowDivider()
            ArrowPreference(
                title = "短信权限",
                summary = if (smsOk) "✅ 已授权（短信通道工作中）" else "⚠️ 未授权（短信通道不工作）",
                onClick = {
                    if (smsOk) SystemAccess.openAppDetails(context)
                    else smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                },
            )
            RowDivider()
            ArrowPreference(
                title = "无障碍截图服务",
                summary = if (accessOk) "✅ 已开启（截屏免录屏授权）" else "⚠️ 未开启（截屏记账走 MediaProjection 兜底）",
                onClick = { SystemAccess.openAccessibility(context) },
            )
        }

        SettingsCard {
            ArrowPreference(
                title = "电池优化白名单",
                summary = if (batteryOk) "✅ 无限制（不会被省电策略限制）" else "⚠️ 受限（国产 ROM 可能解绑后台服务）",
                onClick = { SystemAccess.openBattery(context) },
            )
            RowDivider()
            ArrowPreference(
                title = "自启动 / 后台运行",
                summary = "部分系统需要手动允许自启动与后台弹出界面",
                onClick = {
                    if (!SystemAccess.openAutoStart(context)) {
                        Toast.makeText(context, "当前系统没有可直达的自启动页，请在应用详情里手动允许", Toast.LENGTH_LONG).show()
                        SystemAccess.openAppDetails(context)
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RowDivider()
                ArrowPreference(
                    title = "精确闹钟",
                    summary = if (exactOk) "✅ 已允许（日报/提醒准时）" else "⚠️ 未允许（提醒会推迟到系统空闲）",
                    onClick = { SystemAccess.openExactAlarm(context) },
                )
            }
        }

        SettingsCard {
            ArrowPreference(
                title = "通知总开关",
                summary = if (notifOk) "✅ 已开启" else "⚠️ 已关闭（入库通知与撤销按钮都不可用）",
                onClick = { SystemAccess.openAppNotificationSettings(context) },
            )
            RowDivider()
            ArrowPreference(
                title = "「自动记账」通知渠道",
                summary = if (autoChannelOk) "✅ 已开启" else "⚠️ 被关闭（看不到新账单，但账单仍会入库）",
                onClick = { SystemAccess.openChannel(context, SystemAccess.CHANNEL_AUTO) },
            )
            RowDivider()
            ArrowPreference(
                title = "「识图记账」通知渠道",
                summary = if (shotChannelOk) "✅ 已开启" else "⚠️ 被关闭（AI 识别结果无法确认入账）",
                onClick = { SystemAccess.openChannel(context, SystemAccess.CHANNEL_SHOT_RESULT) },
            )
        }

        SettingsCard {
            InfoRow("运行模式", if (moduleOk) "Root 增强（LSPosed）" else "标准（通知 + 短信）")
            RowDivider()
            InfoRow("通知通道最近入账", formatStamp(prefs.lastNotifyAt))
            RowDivider()
            InfoRow("短信通道最近入账", formatStamp(prefs.lastSmsAt))
        }

        HintText("改完回到这一页，状态会在 1.5 秒内自动刷新。若「最近入账」长期显示「从未」，说明通道没通，先查上面标 ⚠️ 的项。")
    }
}

/** 短信记账要的是危险权限 RECEIVE_SMS，没授予时开关不该显示为已开启。 */
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