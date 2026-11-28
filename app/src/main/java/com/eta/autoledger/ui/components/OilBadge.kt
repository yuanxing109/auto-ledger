package com.eta.autoledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.theme.OilTheme

@Composable
fun OilIconBadge(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    borderColor: Color = OilTheme.colors.line,
    backgroundColor: Color = OilTheme.colors.surfaceMuted,
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

@Composable
fun OilChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: String? = null,
) {
    val colors = OilTheme.colors
    val shape = RoundedCornerShape(50)
    val background = if (selected) colors.accent else colors.surfaceMuted
    val border = if (selected) colors.accentPressed else colors.line
    val foreground = if (selected) colors.onAccent else colors.inkMuted
    Row(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OilText(
            text = if (icon.isNullOrBlank()) text else "$icon $text",
            style = OilTheme.typography.caption,
            color = foreground,
            maxLines = 1,
        )
    }
}
