package com.eta.autoledger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.eta.autoledger.ui.theme.OilTheme

enum class OilCardTone {
    Base,
    Raised,
    Hero,
}

/**
 * Oil UI 的基础卡片。
 *
 * Base 只有实色纸面和一条清晰边界。Raised 才允许出现柔和阴影。Hero 使用整块
 * accent 色承托首页核心金额。内容背景始终是不透明色。
 */
@Composable
fun OilCard(
    modifier: Modifier = Modifier,
    tone: OilCardTone = OilCardTone.Base,
    shape: Shape? = null,
    contentPadding: PaddingValues? = null,
    containerColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OilTheme.colors
    val dimens = OilTheme.dimens
    val resolvedShape = shape ?: OilTheme.shapes.card
    val resolvedPadding = contentPadding ?: PaddingValues(dimens.cardPadding)
    val resolvedFill = when {
        containerColor != Color.Unspecified -> containerColor
        tone == OilCardTone.Hero -> colors.heroSurface
        tone == OilCardTone.Raised -> colors.surfaceRaised
        else -> colors.surface
    }
    val borderColor = when (tone) {
        OilCardTone.Hero -> colors.heroMuted
        OilCardTone.Raised -> colors.line
        OilCardTone.Base -> colors.line
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        label = "oil-card-press",
    )
    val clickModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
    }
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (tone == OilCardTone.Raised) {
                    Modifier.shadow(
                        elevation = dimens.gapSmall,
                        shape = resolvedShape,
                        ambientColor = colors.shadow,
                        spotColor = colors.shadow,
                    )
                } else {
                    Modifier
                },
            )
            .clip(resolvedShape)
            .then(clickModifier)
            .drawBehind {
                drawOilRowSurface(
                    shape = resolvedShape,
                    fill = resolvedFill,
                    stroke = borderColor,
                    strokeWidthDp = 1f,
                )
            }
            .padding(resolvedPadding),
        content = content,
    )
}
