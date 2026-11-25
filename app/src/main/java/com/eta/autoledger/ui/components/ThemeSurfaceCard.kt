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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens

/**
 * 全站普通卡片的统一表面。
 *
 * 填充和 1dp 边框在同一个 drawBehind 里完成，避免每张卡额外增加 border 绘制节点。
 * 当前视觉使用语义色，不引入实时模糊或逐帧背景采样。
 */
@Composable
fun ThemeSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentPadding: PaddingValues? = null,
    containerColor: Color = LedgerTheme.glassCard,
    shadowElevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    val resolvedShape = shape ?: RoundedCornerShape(dimens.radiusCard)
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
            .shadow(
                elevation = shadowElevation,
                shape = resolvedShape,
                ambientColor = LedgerTheme.cardShadow,
                spotColor = LedgerTheme.cardShadow,
            )
            .clip(resolvedShape)
            .then(clickModifier)
            .drawBehind {
                drawRowSurface(
                    shape = resolvedShape,
                    fill = containerColor,
                    stroke = LedgerTheme.borderSoft,
                    strokeWidthDp = 1f,
                )
            }
            .padding(resolvedPadding),
        content = content,
    )
}
