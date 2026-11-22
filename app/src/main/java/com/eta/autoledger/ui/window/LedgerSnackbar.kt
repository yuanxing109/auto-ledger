package com.eta.autoledger.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult

/**
 * 全局 Snackbar 宿主：把 `Toast` 换成 Snackbar 的落点。
 *
 * 为什么要换：
 *  - `Toast` 是系统窗口，会盖在导航栏之上、跟应用配色完全无关（浅色主题弹一个黑底白字框）；
 *  - `Toast` 不能带操作按钮（比如"撤销"），而记账这类操作天然需要"撤销"；
 *  - `Toast` 在 Android 13+ 需要 POST_NOTIFICATIONS 权限，用户拒掉就静默丢失提示。
 *
 * 用法（页面内）：
 * ```
 * val snackbar = LocalSnackbar.current
 * snackbar.show("已记录 ¥12.00")
 * ```
 *
 * 宿主挂在根部 Scaffold 的 `snackbarHost` 槽（见 `ui/LedgerApp.kt`），
 * 这样无论当前在哪个页面、哪个弹层，提示都从同一个位置升起。
 */
@Composable
fun LedgerSnackbarHost(
    state: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SnackbarHost(state = state)
    }
}

/**
 * Snackbar 的调用入口。
 *
 * 刻意做成"拿到就是能发"的一等公民：页面不需要自己 `remember { SnackbarHostState() }`，
 * 也不需要把 state 层层往下传（首页、报表页、设置页都有这个需求）。
 */
class LedgerSnackbar internal constructor(private val state: SnackbarHostState) {

    /** 发送一条短提示。 */
    suspend fun show(message: String, actionLabel: String? = null) {
        state.showSnackbar(message = message, actionLabel = actionLabel, duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Short)
    }

    /** 发送一条长提示（带操作按钮时用它，让按钮有足够时间被点到）。 */
    suspend fun showLong(message: String, actionLabel: String? = null) {
        state.showSnackbar(message = message, actionLabel = actionLabel, duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Long)
    }

    /**
     * 发送一条带撤销动作的提示，返回用户是否点了动作。
     *
     * 记账场景的默认写法：删除/清空这类不可逆操作，配一个"撤销"。
     */
    suspend fun showUndo(message: String, actionLabel: String = "撤销"): Boolean {
        val result = state.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = false,
            duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Long,
        )
        return result == SnackbarResult.ActionPerformed
    }

    /** 直接拿到底层 state（需要自定义 SnackbarHost 时用）。 */
    val hostState: SnackbarHostState get() = state
}

/** 全局 Snackbar 调用入口。在任何 `@Composable` 里用 `LocalSnackbar.current`。 */
val LocalSnackbar = staticCompositionLocalOf<LedgerSnackbar> {
    // 预览/测试环境没有宿主时给一个空实现，避免崩溃。
    LedgerSnackbar(SnackbarHostState())
}
