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
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.window.LocalSnackbar
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.ui.components.AppDialog
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.CsvImporter
import com.eta.autoledger.data.XlsxImporter
import com.eta.autoledger.data.XlsxExporter
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.reminder.ReminderScheduler
import com.eta.autoledger.service.LedgerNotificationListener
import com.eta.autoledger.widget.LedgerWidgetProvider

@Composable
internal fun SystemAccessPage(prefs: AppPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var beat by remember { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
    // 渠道是否存在：不存在时点进去会崩（系统渠道页拿不到 id），必须先建再跳
    val autoChannelExists = remember(beat) { SystemAccess.channelExists(context, SystemAccess.CHANNEL_AUTO) }
    val shotChannelExists = remember(beat) { SystemAccess.channelExists(context, SystemAccess.CHANNEL_SHOT_RESULT) }
    val moduleOk = remember(beat) { SystemAccess.moduleActive(context) }
    // 是否拿到 adb 授予的 WRITE_SECURE_SETTINGS —— 决定「一键修复」能否真写回
    val secureOk = remember(beat) { SystemAccess.canWriteSecureSettings(context) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prefs.smsEnabled = true
        } else {
            scope.launch { snackbar.show("未授予短信权限，短信记账不会生效") }
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
                        scope.launch { snackbar.showLong("需要先用 adb 授予 WRITE_SECURE_SETTINGS") }
                    } else {
                        val (notifyFixed, accessFixed) = SystemAccess.ensureGrants(context)
                        val fixed = if (notifyFixed) "已开启" else "仍失败"
                        val accessText = if (accessFixed) "已开启" else "仍失败"
                        scope.launch { snackbar.showLong("修复结果：通知使用权=$fixed，无障碍=$accessText") }
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
                    scope.launch { snackbar.show("已复制 adb 命令") }
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
                        scope.launch { snackbar.showLong("当前系统没有可直达的自启动页，请在应用详情里手动允许") }
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
                summary = when {
                    !autoChannelExists -> "ℹ️ 尚未创建（点一下：先建渠道，再打开系统设置）"
                    autoChannelOk -> "✅ 已开启"
                    else -> "⚠️ 被关闭（看不到新账单，但账单仍会入库）"
                },
                onClick = { SystemAccess.openChannel(context, SystemAccess.CHANNEL_AUTO) },
            )
            RowDivider()
            ArrowPreference(
                title = "「识图记账」通知渠道",
                summary = when {
                    !shotChannelExists -> "ℹ️ 尚未创建（点一下：先建渠道，再打开系统设置）"
                    shotChannelOk -> "✅ 已开启"
                    else -> "⚠️ 被关闭（AI 识别结果无法确认入账）"
                },
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

        // 危险操作区：放页尾 + danger 文字，避免在「功能」页被误触
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "清空记账数据",
                        style = MiuixTheme.textStyles.body1.copy(color = LedgerTheme.danger),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "删除全部账目，操作不可恢复",
                        style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary),
                    )
                }
            }
        }
    }

    AppDialog(
        show = showClearConfirm,
        title = "清空所有账目？",
        summary = "该操作不可恢复，确定要删除全部记账数据吗？",
        maxWidth = 420.dp,
        outsideMargin = DpSize(24.dp, 24.dp),
        panelColor = LedgerTheme.card,
        onDismissRequest = { showClearConfirm = false },
    ) {
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.inset).clickable { showClearConfirm = false },
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary))
            }
            Box(
                modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.tint(LedgerTheme.danger)).clickable {
                        scope.launch { LedgerRepository.clear(context) }
                        showClearConfirm = false
                        scope.launch { snackbar.show("已清空记账数据") }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "清空",
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = LedgerTheme.danger,
                    ),
                )
            }
        }
    }
}

/** 短信记账要的是危险权限 RECEIVE_SMS，没授予时开关不该显示为已开启。 */
