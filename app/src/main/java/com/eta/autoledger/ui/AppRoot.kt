package com.eta.autoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eta.autoledger.ui.theme.LedgerPalette

/**
 * 应用根容器：只铺一层底色，没有任何壁纸 / 染色层 / 玻璃采样。
 *
 * 玻璃体系已全部退役（页面统一使用不透明 surface），原先 GlassHost 在此
 * 每帧绘制壁纸位图 + 压暗层 + HSV 染色层的成本全部归零。
 */
@Composable
fun AppRoot(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(LedgerPalette.dark.page)) {
        content()
    }
}
