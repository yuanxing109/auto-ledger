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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eta.autoledger.ui.rememberMotionDuration
import com.eta.autoledger.ui.theme.OilTheme

/**
 * Oil UI 弹窗容器。
 *
 * 面板保持不透明实色，透明度只用于窗口遮罩。内容退出动画仍由组件接管，调用方
 * 只需要在组合中保留实例并切换 [show]。
 */
@Composable
fun OilDialog(
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
    val colors = OilTheme.colors
    val enterMillis = rememberMotionDuration(150)
    val exitMillis = rememberMotionDuration(100)
    val scrimInteraction = remember { MutableInteractionSource() }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) closing = false
    }

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show && !closing
    val scrimAlpha by animateFloatAsState(
        targetValue = if (show && !closing) 1f else 0f,
        animationSpec = tween(if (show && !closing) enterMillis else exitMillis),
        label = "oil-dialog-scrim",
    )
    val settled = visibleState.isIdle && !visibleState.currentState

    LaunchedEffect(settled, closing) {
        if (closing && settled) onDismissRequest()
    }
    if (settled && (!show || closing)) return

    Dialog(
        onDismissRequest = { if (dismissEnabled && !closing) closing = true },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentAlignment = align,
        ) {
            if (scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = scrimAlpha }
                        .background(colors.scrim)
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
                enter = fadeIn(tween(enterMillis)) + scaleIn(
                    animationSpec = tween(enterMillis),
                    initialScale = 0.96f,
                ),
                exit = fadeOut(tween(exitMillis)) + scaleOut(
                    animationSpec = tween(exitMillis),
                    targetScale = 0.96f,
                ),
            ) {
                val panelShape = shape ?: OilTheme.shapes.dialog
                Column(
                    modifier = Modifier
                        .then(
                            if (widthFraction != null) {
                                Modifier.fillMaxWidth(widthFraction)
                            } else {
                                Modifier.fillMaxWidth().widthIn(max = maxWidth)
                            },
                        )
                        .then(
                            if (heightFraction != null) {
                                Modifier.fillMaxHeight(heightFraction)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = outsideMargin.width, vertical = outsideMargin.height)
                        .clip(panelShape)
                        .background(panelColor ?: colors.surfaceRaised)
                        .border(1.dp, colors.line, panelShape)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier)
                        .padding(contentPadding),
                ) {
                    if (title != null) {
                        OilText(
                            text = title,
                            style = OilTheme.typography.sectionTitle,
                            color = colors.ink,
                        )
                        if (summary != null) {
                            Spacer(Modifier.height(5.dp))
                            OilText(
                                text = summary,
                                style = OilTheme.typography.caption,
                                color = colors.inkMuted,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    content()
                }
            }
        }
    }
}
