package com.eta.autoledger.ui


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
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
import androidx.compose.foundation.Image
import com.eta.autoledger.ui.rememberMotionDuration
import com.eta.autoledger.ui.components.OilDialog
import com.eta.autoledger.ui.components.OilIconBadge
import com.eta.autoledger.ui.components.OilScaffold
import com.eta.autoledger.ui.components.OilBottomBar
import com.eta.autoledger.ui.components.OilCard
import com.eta.autoledger.ui.components.OilNavItem
import com.eta.autoledger.ui.components.OilText
import com.eta.autoledger.ui.components.OilTopBar
import com.eta.autoledger.ui.components.OilTabBar
import com.eta.autoledger.ui.theme.LedgerDimens
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.ui.screen.AddScreen
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.screenshot.VisionHelper
import com.eta.autoledger.settings.SettingsPage
import com.eta.autoledger.settings.SettingsScreen
import com.eta.autoledger.ui.screen.HomeScreen
import com.eta.autoledger.ui.screen.ReportScreen
import com.eta.autoledger.ui.screen.SearchScreen
import com.eta.autoledger.ui.theme.AppTheme
import com.eta.autoledger.ui.theme.OilTheme
import com.eta.autoledger.ui.theme.LocalOilWallpaperActive
import com.eta.autoledger.ui.window.LedgerSnackbar
import com.eta.autoledger.ui.window.LedgerSnackbarHost
import com.eta.autoledger.ui.window.LocalSnackbar

private data class Tab(
    val label: String,
    val icon: ImageVector,
)


@Composable
fun LedgerApp(openAiSettings: Boolean = false, openAddRequest: Int = 0) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    // 全站唯一主题根：解析「外观」模式并注入 Oil 色板与字体 Token。
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

        LaunchedEffect(openAddRequest) {
            if (openAddRequest > 0) {
                showSettings = false
                showSearch = false
                showAiSheet = false
                selected = 1
            }
        }
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
                        VisionHelper.callVisionModel(
                            prefs = prefs,
                            base64 = base64,
                            mime = VisionHelper.MIME_JPEG,
                            expenseCategories = CategoryStore.names(context, 0),
                            incomeCategories = CategoryStore.names(context, 1),
                        )
                    }
                }
                result.onSuccess { entry -> VisionHelper.postResult(context, entry, null) }
                    .onFailure { t ->
                        VisionHelper.postResult(context, null, VisionHelper.userFacingError(t))
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
            val photos = File(context.cacheDir, "photos").apply { mkdirs() }
            val file = File(photos, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
        BackHandler(enabled = selected != 0) { selected = 0 }

        var reportTab by rememberSaveable { mutableIntStateOf(1) }
        val reportShareRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val tabs = listOf(
            Tab("记账", OilIcons.Home),
            Tab("记一笔", OilIcons.Add),
            Tab("报表", OilIcons.GridView),
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
        OilAppShell {
            CompositionLocalProvider(
                LocalSnackbar provides snackbar,
                LocalOilWallpaperActive provides prefs.customBackgroundPath.isNotBlank(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LedgerBackdrop(path = prefs.customBackgroundPath)
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

                        else -> OilScaffold(
                            containerColor = Color.Transparent,
                            topBar = {
                                if (selected == 2) {
                                    Column {
                                        OilTopBar(
                                            title = "收支报表",
                                            actions = {
                                                com.eta.autoledger.ui.components.OilIconButton(
                                                    icon = ReportShareIcon,
                                                    contentDescription = "分享报表",
                                                    onClick = { reportShareRequests.tryEmit(Unit) },
                                                )
                                            },
                                        )
                                        OilTabBar(
                                            labels = listOf("周报", "月报", "年报"),
                                            selectedIndex = reportTab,
                                            onSelected = { reportTab = it },
                                        )
                                    }
                                } else {
                                    OilTopBar(
                                        title = tabs[selected].label,
                                        navigationIcon = if (selected == 1) OilIcons.Back else null,
                                        onNavigationClick = { selected = 0 },
                                        actions = {
                                            com.eta.autoledger.ui.components.OilIconButton(
                                                icon = AiScanIcon,
                                                contentDescription = "AI识图",
                                                onClick = { showAiSheet = true },
                                            )
                                            if (selected == 0) {
                                                com.eta.autoledger.ui.components.OilIconButton(
                                                    icon = OilIcons.Settings,
                                                    contentDescription = "设置",
                                                    onClick = {
                                                        settingsPage = SettingsPage.Main
                                                        showSettings = true
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                            bottomBar = {
                                if (selected != 1) {
                                    OilBottomBar(
                                        items = tabs.map { OilNavItem(it.label, it.icon) },
                                        selectedIndex = selected,
                                        onSelected = { selected = it },
                                    )
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
                                    else -> ReportScreen(
                                        modifier = Modifier.padding(padding),
                                        tab = reportTab,
                                        shareRequests = reportShareRequests,
                                    )
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

                    // 常驻组合、只切 show：这样关闭时 OilDialog 内部能播完退场再摘掉窗口。
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
}

/** 静态位图背景。图片只解码一次，滚动和页面切换期间复用同一份 ImageBitmap。 */
@Composable
private fun LedgerBackdrop(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            if (path.isBlank()) null else runCatching { decodeBackgroundBitmap(path) }.getOrNull()
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(OilTheme.colors.page)) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun decodeBackgroundBitmap(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MAX_BACKGROUND_EDGE ||
        bounds.outHeight / sampleSize > MAX_BACKGROUND_EDGE
    ) {
        sampleSize *= 2
    }
    val bitmap: Bitmap = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return null
    return bitmap.asImageBitmap()
}

private const val MAX_BACKGROUND_EDGE = 2048

@Composable
private fun AiActionSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val dimens = LocalDimens.current
    OilDialog(
        show = show,
        widthFraction = 0.88f,
        outsideMargin = DpSize(dimens.cardPadding, dimens.cardPadding),
        insideMargin = DpSize(dimens.cardPadding, dimens.cardPadding),
        title = "识图记账",
        summary = "拍照或上传账单图片，交给已配置的 AI 解析。",
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.gap),
        ) {
            AiSheetOption(OilIcons.Scan, "拍照识图", Modifier.weight(1f), onCamera)
            AiSheetOption(OilIcons.Photos, "上传图片识图", Modifier.weight(1f), onGallery)
        }
    }
}

@Composable
private fun AiSheetOption(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OilCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        OilIconBadge(
            size = 48.dp,
            borderColor = OilTheme.colors.accent,
            backgroundColor = OilTheme.colors.surfaceMuted,
        ) {
            OilIcon(
                imageVector = icon,
                contentDescription = null,
                tint = OilTheme.colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        OilText(
            text = label,
            style = OilTheme.typography.bodyStrong,
            color = OilTheme.colors.ink,
            maxLines = 1,
        )
    }
}
