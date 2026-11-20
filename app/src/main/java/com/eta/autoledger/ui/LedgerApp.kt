package com.eta.autoledger.ui

import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eta.autoledger.ui.screen.AddScreen
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.screenshot.VisionHelper
import com.eta.autoledger.settings.SettingsPage
import com.eta.autoledger.settings.SettingsScreen
import com.eta.autoledger.ui.screen.HomeScreen
import com.eta.autoledger.ui.screen.ReportScreen
import com.eta.autoledger.ui.screen.SearchScreen
import com.eta.autoledger.ui.theme.AppTheme

private data class Tab(
    val label: String,
    val icon: ImageVector,
)


@Composable
fun LedgerApp(openAiSettings: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    // 全站唯一主题根：解析「外观」模式 → 灌 Miuix 色板/字体槽 → 同步自绘色板。
    // 之后任何页面都不许再包 MiuixTheme / MaterialTheme，否则深色会出现两套色。
    AppTheme(mode = prefs.themeMode) {
        var selected by rememberSaveable { mutableIntStateOf(0) }
        var showSettings by rememberSaveable { mutableStateOf(openAiSettings) }
        var settingsPage by rememberSaveable {
            mutableStateOf(if (openAiSettings) SettingsPage.Ai else SettingsPage.Main)
        }
        var showAiSheet by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        fun analyzeImage(uri: Uri) {
            scope.launch {
                Toast.makeText(context, "正在交给 AI 识别…", Toast.LENGTH_SHORT).show()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val prefs = AppPrefs(context)
                        if (prefs.aiBaseUrl.isBlank() || prefs.aiModel.isBlank()) {
                            error("请先在设置中配置 AI 模型")
                        }
                        val base64 = VisionHelper.readImageBase64(context, uri)
                        VisionHelper.callVisionModel(prefs, base64)
                    }
                }
                result.onSuccess { entry -> VisionHelper.postResult(context, entry, null) }
                    .onFailure { t ->
                        VisionHelper.postResult(context, null, "识别失败：${t.message ?: t.javaClass.simpleName}")
                    }
            }
        }
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { analyzeImage(it) }
        }
        var cameraUri by remember { mutableStateOf<Uri?>(null) }
        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            cameraUri?.let { if (ok) analyzeImage(it) }
        }
        fun pickImageVision() {
            showAiSheet = false
            imagePicker.launch("image/*")
        }
        fun captureVision() {
            showAiSheet = false
            val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
        BackHandler(enabled = selected != 0) { selected = 0 }

        if (showSettings) {
            SettingsScreen(
                initialPage = settingsPage,
                onClose = { showSettings = false },
            )
            return@MiuixTheme
        }
        var reportTab by rememberSaveable { mutableIntStateOf(1) }
        val tabs = listOf(
            Tab("记账", MiuixIcons.Home),
            Tab("记一笔", MiuixIcons.Add),
            Tab("报表", MiuixIcons.GridView),
        )

        Scaffold(
            topBar = {
                if (selected == 2) {
                    ReportTopBar(
                        tab = reportTab,
                        onTab = { reportTab = it },
                    )
                } else {
                    LedgerTopBar(
                        title = tabs[selected].label,
                        isHome = selected == 0,
                        showAi = selected != 2,
                        onBack = if (selected == 1) ({ selected = 0 }) else null,
                        onAi = { showAiSheet = true },
                        onSettings = {
                            settingsPage = SettingsPage.Main
                            showSettings = true
                        },
                    )
                }
            },
            bottomBar = {
                if (selected != 1) {
                    LedgerBottomBar(selected = selected, onSelect = { selected = it })
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                },
                label = "page",
            ) { page ->
                when (page) {
                    0 -> HomeScreen(Modifier.padding(padding), onSearch = { showSearch = true })
                    1 -> AddScreen(Modifier.padding(padding), onDone = { selected = 0 })
                    else -> ReportScreen(modifier = Modifier.padding(padding), tab = reportTab)
                }
            }
        }

        if (showSearch) {
            SearchScreen(onClose = { showSearch = false })
        }

        if (showAiSheet) {
            AiActionSheet(
                onDismiss = { showAiSheet = false },
                onCamera = { captureVision() },
                onGallery = { pickImageVision() },
            )
        }
    }
}

@Composable
private fun AiActionSheet(onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(LedgerTheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text("识图记账", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LedgerTheme.text))
                Spacer(Modifier.height(6.dp))
                Text("拍照或上传账单图片，交给已配置的 AI 解析。", style = TextStyle(fontSize = 12.sp, color = LedgerTheme.textSecondary))
                Spacer(Modifier.height(16.dp))
                SheetOption("📷", "拍照识图", onCamera)
                Spacer(Modifier.height(10.dp))
                SheetOption("🖼️", "上传图片识图", onGallery)
            }
        }
    }
}

@Composable
private fun SheetOption(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LedgerTheme.inset)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = TextStyle(fontSize = 20.sp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = TextStyle(fontSize = 14.sp, color = LedgerTheme.text))
    }
}

@Composable
private fun LedgerTopBar(
    title: String,
    isHome: Boolean,
    showAi: Boolean,
    onBack: (() -> Unit)? = null,
    onSettings: () -> Unit = {},
    onAi: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp),
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                )
            }
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            modifier = Modifier.align(Alignment.Center),
        )
        if (showAi) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAi) {
                    Icon(
                        imageVector = AiScanIcon,
                        contentDescription = "AI识图",
                        // ImageVector 里的 SolidColor 会被 tint 覆盖；不显式给色时默认 LocalContentColor（黑），深色主题下会消失
                        tint = LedgerTheme.text,
                    )
                }
                if (isHome) {
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = MiuixIcons.Settings,
                            contentDescription = "设置",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportTopBar(tab: Int, onTab: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LedgerTheme.surface)
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = "收支报表",
                style = TextStyle(fontSize = 17.sp, color = LedgerTheme.text),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("周报", "月报", "年报").forEachIndexed { index, label ->
                val selected = tab == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTab(index) }
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) LedgerTheme.text else LedgerTheme.textSecondary,
                        ),
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) LedgerTheme.accent else Color.Transparent),
                    )
                }
                if (index < 2) {
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(12.dp)
                            .background(LedgerTheme.divider),
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(64.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.12f)),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            BottomNavItem(
                label = "记账",
                icon = MiuixIcons.Home,
                selected = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(96.dp))
            BottomNavItem(
                label = "报表",
                icon = MiuixIcons.GridView,
                selected = selected == 2,
                onClick = { onSelect(2) },
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-14).dp)
                .shadow(elevation = 10.dp, shape = CircleShape, clip = false),
        ) {
            top.yukonga.miuix.kmp.basic.FloatingActionButton(
                onClick = { onSelect(1) },
                minWidth = 64.dp,
                minHeight = 64.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "记一笔",
                    // FAB 底色是 accent（浅色深蓝 / 深色亮蓝），图标取 onAccent 才能两头都有对比度
                    tint = LedgerTheme.onAccent,
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.onSurfaceContainerVariant
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Icon(imageVector = icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = MiuixTheme.textStyles.footnote2, color = color)
    }
}