package com.eta.autoledger.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 过渡动画时长的统一来源。
 *
 * 系统「移除动画」开关（开发者选项的 animator_duration_scale、无障碍里的
 * 「移除动画」）为 0 时，[ValueAnimator.getDurationScale] 返回 0f。这时必须把时长
 * 压到 0 —— 否则动画会「瞬移式」播放，比不做动画更难看；无障碍用户明确要求了
 * 不要动画。
 *
 * 原先这个判断只写在 LedgerApp 的 overlay 里（局部变量 overlayDuration），
 * 报表页与搜索页拿不到。抽到这里后所有转场共用一份逻辑，不会出现
 * 「主页动画关了、报表页还在淡入」这种不一致。
 *
 * 注意 getDurationScale 是进程级的静态值，只在组合时读一次即可；用户中途改系统
 * 设置的话，下次进程重启（或页面重建）才生效 —— 与原先 overlayDuration 的行为一致。
 */
@Composable
fun rememberMotionDuration(baseMillis: Int = 200): Int {
    val context = LocalContext.current.applicationContext
    val scale = remember(context) { animatorDurationScale(context) }
    return if (scale == 0f) 0 else baseMillis
}

/** Android 13 起才公开 [ValueAnimator.getDurationScale]；旧系统从系统设置安全读取。 */
internal fun animatorDurationScale(context: Context): Float {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return runCatching { ValueAnimator.getDurationScale() }.getOrDefault(1f)
    }
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
}
