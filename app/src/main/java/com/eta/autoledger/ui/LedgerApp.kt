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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.eta.autoledger.ui.rememberMotionDuration
import com.eta.autoledger.ui.components.AppDialog
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.eta.autoledger.ui.window.LedgerSnackbar
import com.eta.autoledger.ui.window.LedgerSnackbarHost
import com.eta.autoledger.ui.window.LocalSnackbar

private data class Tab(
    val label: String,
    val icon: ImageVector,
)


@Composable
fun LedgerApp(openAiSettings: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
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
        // 全局 Snackbar：自己维护「同一时刻只有一条」的模型（见 LedgerSnackbar），
        // 新提示直接顶掉旧的，不会在屏幕里排队堆叠。
        val snackbar = remember { LedgerSnackbar() }
        fun analyzeImage(uri: Uri) {
            scope.launch {
                Toast.makeText(context, "正在交给 AI 识别…", Toast.LENGTH_SHORT).show()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val prefs = AppPrefs.get(context)
                        if (prefs.aiBaseUrl.isBlank() || prefs.aiModel.isBlank()) {
                            error("请先在设置中配置 AI 模型")
                        }
                        val base64 = VisionHelper.readImageBase64(context, uri)
                        VisionHelper.callVisionModel(prefs, base64, VisionHelper.MIME_JPEG)
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

        var reportTab by rememberSaveable { mutableIntStateOf(1) }
        val tabs = listOf(
            Tab("记账", MiuixIcons.Home),
            Tab("记一笔", MiuixIcons.Add),
            Tab("报表", MiuixIcons.GridView),
        )

        // 系统「移除动画」开关：开发者选项 / 无障碍里的 animator_duration_scale 为 0 时，
        // ValueAnimator.getDurationScale() 返回 0f。此时把过渡时长压到 0，等价于瞬时切换。
        // 三处转场（overlay / 底部页签 / 报表周期）共用 rememberMotionDuration，
        // 避免出现「主页动画关了、报表页还在淡入」这种不一致。
        val overlayDuration = rememberMotionDuration(200)
        val pageDuration = rememberMotionDuration(180)
        val overlay = when {
            showSettings -> 1
            showSearch -> 2
            else -> 0
        }
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = overlay,
                    transitionSpec = { fadeIn(tween(overlayDuration)) togetherWith fadeOut(tween(overlayDuration)) },
                    label = "overlay",
                ) { page ->
                    when (page) {
                        1 -> SettingsScreen(
                            initialPage = settingsPage,
                            onClose = { showSettings = false },
                        )

                        2 -> SearchScreen(onClose = { showSearch = false })

                        else -> Scaffold(
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
                                    // 左右滑动 + 淡入淡出：让用户感知「往左/往右切了一页」。
                                    // 方向由目标页与当前页的序号差决定 —— 从首页点到报表往左滑，
                                    // 从报表回首页往右滑，符合底部导航的空间隐喻。
                                    // 纯 fade 的问题是没有方向感，三个页签看起来像同一个页面在换内容。
                                    val forward = targetState > initialState
                                    val dir = if (forward) 1 else -1
                                    // 滑动距离只用页宽的 1/6：整屏平移在快速连点时会显得晃，
                                    // 小位移 + 淡入足够表达方向，也更接近系统页签切换的手感。
                                    (slideInHorizontally(tween(pageDuration)) { full -> dir * full / 6 } +
                                        fadeIn(tween(pageDuration)))
                                        .togetherWith(
                                            slideOutHorizontally(tween(pageDuration)) { full -> -dir * full / 6 } +
                                                fadeOut(tween(pageDuration))
                                        )
                                },
                                label = "page",
                            ) { tab ->
                                when (tab) {
                                    0 -> HomeScreen(Modifier.padding(padding), onSearch = { showSearch = true })
                                    1 -> AddScreen(Modifier.padding(padding), onDone = { selected = 0 })
                                    else -> ReportScreen(modifier = Modifier.padding(padding), tab = reportTab)
                                }
                            }
                        }
                    }
                }

                // 全局提示宿主挂在根层：主页 / 设置页 / 搜索页共用同一个出口，
                // 而且始终叠在内容之上，不会再像列表项一样堆进账目里。
                LedgerSnackbarHost(
                    snackbar = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // 常驻组合、只切 show：这样关闭时 AppDialog 内部能播完退场再摘掉窗口。
                // 外面再包一层 if (showAiSheet) 的话，一点关闭组件就被移出组合，退场动画没机会播。
                AiActionSheet(
                    show = showAiSheet,
                    onDismiss = { showAiSheet = false },
                    onCamera = { captureVision() },
                    onGallery = { pickImageVision() },
                )
            }
        }
    }
}

@Composable
private fun AiActionSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    AppDialog(
        show = show,
        title = "识图记账",
        summary = "拍照或上传账单图片，交给已配置的 AI 解析。",
        widthFraction = 0.88f,
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetOption("📷", "拍照识图", Modifier.weight(1f), onCamera)
            SheetOption("🖼️", "上传图片识图", Modifier.weight(1f), onGallery)
        }
    }
}

@Composable
private fun SheetOption(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(LedgerTheme.inset)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = emoji, fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = MiuixTheme.textStyles.body1, color = LedgerTheme.text)
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
    val barShape = RoundedCornerShape(50)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(barShape)
                .background(LedgerTheme.surface),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LedgerTheme.divider),
        )
    }
}

@Composable
private fun ReportTopBar(tab: Int, onTab: (Int) -> Unit) {
    val barShape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(barShape)
                .background(LedgerTheme.surface)
                .padding(bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = "收支报表",
                    style = MiuixTheme.textStyles.title3,
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = LocalIndication.current,
                            ) { onTab(index) }
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.body2.copy(
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LedgerTheme.divider),
        )
    }
}

@Composable
private fun LedgerBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        val dockShape = RoundedCornerShape(50)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(dockShape)
                .background(LedgerTheme.surface)
                .border(1.dp, LedgerTheme.borderSoft, dockShape),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockTab("记账", MiuixIcons.Home, selected == 0, Modifier.weight(1f)) { onSelect(0) }
                DockTab("记一笔", MiuixIcons.Add, selected == 1, Modifier.weight(1f)) { onSelect(1) }
                DockTab("报表", MiuixIcons.GridView, selected == 2, Modifier.weight(1f)) { onSelect(2) }
            }
        }
    }
}

@Composable
private fun DockTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val chipAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "dockChip",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "dockScale",
    )
    val color = if (selected) LedgerTheme.accent else LedgerTheme.textSecondary
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 32.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) LedgerTheme.accent.copy(alpha = 0.16f + 0.06f * chipAlpha)
                    else Color.Transparent
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = color)
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MiuixTheme.textStyles.footnote2, color = color)
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Icon(imageVector = icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = MiuixTheme.textStyles.footnote2, color = color)
    }
}