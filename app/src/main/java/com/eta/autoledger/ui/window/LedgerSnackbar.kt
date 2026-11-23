package com.eta.autoledger.ui.window

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.theme.LedgerDimens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 全局轻提示。
 *
 * 为什么不用 Toast：系统 Toast 挂在 WindowManager 顶层，会盖住底部导航、
 * 无法带「撤销」这类操作按钮，Android 13+ 还要 POST_NOTIFICATIONS 权限。
 *
 * 为什么自己维护队列而不用 Miuix 的 SnackbarHostState：它的 SnackbarHost 内部是
 * LazyColumn，几条并发 show 会像列表项一样一条条堆在屏幕中间（连着删 5 笔就是
 * 5 条灰白横条），而且默认配色不跟随项目色板。这里改成「同一时刻只有一条」，
 * 新提示直接顶掉旧的，界面只有一个悬浮卡片。
 */
class LedgerSnackbar internal constructor() {

    /** 当前这一条；同一时刻最多一条，新的直接顶掉旧的。 */
    internal val current = mutableStateOf<Item?>(null)

    private var seq = 0

    suspend fun show(message: String, actionLabel: String? = null) {
        showInternal(message, actionLabel, SHORT_MS).await()
    }

    suspend fun showLong(message: String, actionLabel: String? = null) {
        showInternal(message, actionLabel, LONG_MS).await()
    }

    /** 带「撤销」的提示；返回 true 表示用户点了动作按钮。 */
    suspend fun showUndo(message: String, actionLabel: String = "撤销"): Boolean =
        showInternal(message, actionLabel, LONG_MS).await() == SnackbarResult.ActionPerformed

    internal fun performAction(item: Item) {
        finish(item, SnackbarResult.ActionPerformed)
    }

    internal fun dismiss(item: Item) {
        finish(item, SnackbarResult.Dismissed)
    }

    private fun finish(item: Item, result: SnackbarResult) {
        item.result.complete(result)
        // 只清自己：如果此刻屏幕上已经是更新的那条，别把它一起抹掉。
        if (current.value?.id == item.id) current.value = null
    }

    private fun showInternal(
        message: String,
        actionLabel: String?,
        durationMs: Long,
    ): CompletableDeferred<SnackbarResult> {
        // 关键：先把上一条结掉。它的 show* 立刻以 Dismissed 返回，
        // 界面同时换成新的这条——旧提示不会在屏幕里排队。
        current.value?.let { finish(it, SnackbarResult.Dismissed) }
        val item = Item(++seq, message, actionLabel, durationMs)
        current.value = item
        return item.result
    }

    internal data class Item(
        val id: Int,
        val message: String,
        val actionLabel: String?,
        val durationMs: Long,
        val result: CompletableDeferred<SnackbarResult> = CompletableDeferred(),
    )

    private companion object {
        const val SHORT_MS = 2_500L
        const val LONG_MS = 6_000L
    }
}

/**
 * 提示宿主：整屏最上层的一个 Box，只画当前这一条。
 *
 * 位置固定贴在底部导航之上（底栏 72dp + 12dp 外边距，再留导航栏内边距），
 * 卡片用项目色板（surface / text / accent / borderSoft），深色模式下不会出现灰白底。
 */
@Composable
fun LedgerSnackbarHost(snackbar: LedgerSnackbar, modifier: Modifier = Modifier) {
    val item = snackbar.current.value
    // 退场动画期间 item 已经是 null，还要把上一次的内容画完，否则卡片会瞬间消失。
    var shown by remember { mutableStateOf<LedgerSnackbar.Item?>(null) }
    LaunchedEffect(item) { if (item != null) shown = item }

    val dimens = LocalDimens.current
    val shape = remember(dimens.radiusCard) { RoundedCornerShape(dimens.radiusCard) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = dimens.pagePadding)
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = item != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { full -> full / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { full -> full / 3 },
        ) {
            shown?.let { data ->
                SnackbarCard(
                    item = data,
                    shape = shape,
                    onAction = { snackbar.performAction(data) },
                    onTimeout = { snackbar.dismiss(data) },
                )
            }
        }
    }
}

@Composable
private fun SnackbarCard(
    item: LedgerSnackbar.Item,
    shape: RoundedCornerShape,
    onAction: () -> Unit,
    onTimeout: () -> Unit,
) {
    val dimens = LocalDimens.current
    // 计时放在卡片里：显示即开始，到点自动收；换一条时 key 变化会重开计时。
    LaunchedEffect(item.id) {
        delay(item.durationMs)
        onTimeout()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)
            .background(LedgerTheme.surface)
            .border(1.dp, LedgerTheme.borderSoft, shape)
            .padding(horizontal = dimens.gapLarge, vertical = dimens.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.message,
            style = MiuixTheme.textStyles.body2,
            color = LedgerTheme.text,
            modifier = Modifier.weight(1f),
        )
        val label = item.actionLabel
        if (label != null) {
            Spacer(Modifier.width(dimens.gapSmall))
            Box(
                modifier = Modifier
                    .heightIn(min = LedgerDimens.minTouch)
                    .clip(RoundedCornerShape(dimens.radiusSmall))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = onAction,
                    )
                    .padding(horizontal = dimens.gapSmall),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = LedgerTheme.accent,
                )
            }
        }
    }
}

val LocalSnackbar = staticCompositionLocalOf<LedgerSnackbar> { LedgerSnackbar() }
