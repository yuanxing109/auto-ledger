package com.eta.autoledger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.rememberMotionDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全站唯一的对话框容器（实心 surface，已去玻璃）。
 *
 * 默认是「居中卡片」：圆角走 LocalDimens.radiusSheet，1dp borderSoft 描边，
 * 自带 title / summary 头部。底部半屏面板、无头部的自定义弹窗靠参数切换：
 *
 * - [align]：面板在窗口里的落点，底部面板传 [Alignment.BottomCenter]。
 * - [title]：传 null 表示不画内置头部，标题由调用方自己在 [content] 里排。
 * - [shape]：传 null 用默认 sheet 圆角；底部面板一般传只圆上两角。
 * - [widthFraction] / [heightFraction]：按比例占位，用于半屏面板；
 *   不传则宽度受 [maxWidth] 约束、高度随内容。
 * - [panelColor]：面板底色，默认 surface。
 * - [modifier]：追加到面板最外层的 Modifier（如 navigationBarsPadding）。
 *
 * ## 进出场动画
 *
 * 内容 `scaleIn(0.95) + fadeIn` 进场、`scaleOut(0.95) + fadeOut` 退场；
 * 遮罩透明度由 [animateFloatAsState] 跟随，进慢出快（150 / 100ms），
 * 符合「出现要看清、消失要利落」的观感。三处时长都走 [rememberMotionDuration]，
 * 系统「移除动画」开启时统一归零。
 *
 * ## 为什么退场要自己接管
 *
 * 早期调用点是 `if (showX) AppDialog(...)` 或 `x?.let { AppDialog(...) }` ——
 * 用户一点关闭，父级立刻把它移出组合，Dialog 从窗口上摘掉，退场动画根本没机会播。
 * 所以这里不让 `onDismissRequest` 直达父级：先置 [closing]，播完退场动画
 * （[MutableTransitionState.isIdle] 且目标为 false）再回调父级去卸载。
 *
 * 三种关闭路径：
 * - 用户点遮罩 / 返回键 → [closing] 置 true → 播退场 → 回调父级卸载；
 * - 父级自己把 `show` 置 false（组件仍在组合里）→ targetState 转 false → 播退场 → 摘掉 Dialog；
 * - 父级直接把组件移出组合 → 无法播退场。要覆盖这条，调用方必须常驻组合、
 *   只切 `show`（现有调用点已全部改成这种写法）。
 *
 * 另：用 [MutableTransitionState] 而不是 `AnimatedVisibility(visible = show)`，
 * 是因为后者在首次组合时若 visible 已为 true 会直接呈现、不播进场 —— 弹窗会「闪」出来。
 *
 * 执行异步写入时传 `dismissEnabled = false`，返回键和遮罩都不会触发退场，
 * 避免父级拒绝关闭后窗口状态与 `show` 脱节。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    show: Boolean = true,
    dismissEnabled: Boolean = true,
    title: String? = null,
    maxWidth: Dp = 560.dp,
    summary: String? = null,
    outsideMargin: DpSize = DpSize(20.dp, 20.dp),
    insideMargin: DpSize = DpSize(20.dp, 20.dp),
    contentPadding: PaddingValues = PaddingValues(insideMargin.width, insideMargin.height),
    align: Alignment = Alignment.Center,
    shape: Shape? = null,
    widthFraction: Float? = null,
    heightFraction: Float? = null,
    panelColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val enterMillis = rememberMotionDuration(150)
    val exitMillis = rememberMotionDuration(100)
    val scrimInteraction = remember { MutableInteractionSource() }

    var closing by remember { mutableStateOf(false) }
    // 父级重新打开时（同一实例被复用）复位，否则第二次打开会立刻又走退场。
    LaunchedEffect(show) { if (show) closing = false }

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show && !closing

    val scrimAlpha by animateFloatAsState(
        targetValue = if (show && !closing) 1f else 0f,
        animationSpec = tween(if (show && !closing) enterMillis else exitMillis),
        label = "dialog-scrim",
    )

    // 退场动画播完再通知父级卸载。currentState=false 且 targetState=false 即 isIdle。
    val settled = visibleState.isIdle && !visibleState.currentState
    LaunchedEffect(settled, closing) {
        if (closing && settled) {
            onDismissRequest()
        }
    }

    // 该收起来了就整块摘掉 Dialog：父级把 show 置 false，或自己已经播完退场。
    // 后者不能指望父级一定响应 —— 万一调用方没把 show 关掉，
    // 留一个不可见的全屏 Dialog 窗口会一直吃掉触摸事件。
    if (settled && (!show || closing)) return

    Dialog(
        onDismissRequest = { if (dismissEnabled && !closing) closing = true },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = align) {
            // 遮罩跟着内容一起渐入渐出。alpha 为 0 时不画，省一次全屏混合。
            // 必须自己接管点击：铺满全屏后窗口内已经没有「外部」区域，
            // 系统 Dialog 的 dismissOnClickOutside 不会再触发，不补这一下就点不掉了。
            if (scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = scrimAlpha }
                        .background(LedgerTheme.scrim)
                        .then(
                            if (dismissEnabled) {
                                Modifier.clickable(
                                    interactionSource = scrimInteraction,
                                    indication = null,
                                ) { if (!closing) closing = true }
                            } else {
                                Modifier
                            },
                        ),
                )
            }

            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(enterMillis)) + scaleIn(tween(enterMillis), initialScale = 0.95f),
                exit = fadeOut(tween(exitMillis)) + scaleOut(tween(exitMillis), targetScale = 0.95f),
            ) {
                val dialogShape = shape ?: RoundedCornerShape(LocalDimens.current.radiusSheet)
                Column(
                    modifier = Modifier
                        .then(
                            if (widthFraction != null) {
                                Modifier.fillMaxWidth(widthFraction)
                            } else {
                                Modifier.fillMaxWidth().widthIn(max = maxWidth)
                            },
                        )
                        .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier)
                        .padding(horizontal = outsideMargin.width, vertical = outsideMargin.height)
                        .clip(dialogShape)
                        .background(panelColor ?: LedgerTheme.surface)
                        .border(1.dp, LedgerTheme.borderSoft, dialogShape)
                        // 吃掉落在面板上的点击，别让它穿透到下面的遮罩把弹窗关掉。
                        // 用 pointerInput 而不是 clickable：不需要语义、涟漪和无障碍角色。
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier)
                        .padding(contentPadding),
                ) {
                    if (title != null) {
                        Text(text = title, style = MiuixTheme.textStyles.title3, color = LedgerTheme.text)
                        if (summary != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = summary,
                                style = MiuixTheme.textStyles.footnote1,
                                color = LedgerTheme.textSecondary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    content()
                }
            }
        }
    }
}
