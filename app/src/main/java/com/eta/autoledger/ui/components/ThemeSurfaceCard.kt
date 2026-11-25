package com.eta.autoledger.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens

/**
 * 全站普通卡片的统一表面。
 *
 * 统一使用纯色 surface、1dp 清晰边框与 16dp 圆角，无渐变、无模糊、无默认阴影。
 * 填充和边框在同一个 drawBehind 里完成，避免每张卡额外增加绘制节点。
 */
@Composable
fun ThemeSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentPadding: PaddingValues? = null,
    containerColor: Color = LedgerTheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    val resolvedShape = shape ?: remember(dimens.radiusCard) { RoundedCornerShape(dimens.radiusCard) }
    val resolvedPadding = contentPadding ?: PaddingValues(dimens.cardPadding)
    val clickModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = LocalIndication.current,
            onClick = onClick,
        )
    }
    Column(
        modifier = modifier
            .clip(resolvedShape)
            .then(clickModifier)
            .drawBehind {
                drawRowSurface(
                    shape = resolvedShape,
                    fill = containerColor,
                    stroke = LedgerTheme.border,
                    strokeWidthDp = 1f,
                )
            }
            .padding(resolvedPadding),
        content = content,
    )
}
