package com.eta.autoledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.LedgerTheme

/** 设置与列表共用的圆形图标底盘：实色浅底 + 1dp 清晰描边。 */
@Composable
fun ThemeIconBadge(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    borderColor: Color = LedgerTheme.border,
    backgroundColor: Color = LedgerTheme.surfaceVariant,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
