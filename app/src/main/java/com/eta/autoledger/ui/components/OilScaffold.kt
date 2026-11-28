package com.eta.autoledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.eta.autoledger.ui.theme.OilTheme

/** 轻量页面骨架，只负责固定顶栏、底栏和主内容区。 */
@Composable
fun OilScaffold(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (containerColor == Color.Unspecified) OilTheme.colors.canvas else containerColor),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) { topBar() }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            content(PaddingValues())
        }
        Box(modifier = Modifier.fillMaxWidth()) { bottomBar() }
    }
}
