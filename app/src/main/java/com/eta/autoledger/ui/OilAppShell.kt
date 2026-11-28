package com.eta.autoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eta.autoledger.ui.theme.OilTheme

/** 应用唯一的根画布，始终铺一层不透明 Oil 页面底色。 */
@Composable
fun OilAppShell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OilTheme.colors.canvas),
    ) {
        content()
    }
}
