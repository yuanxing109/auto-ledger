package com.eta.autoledger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.theme.OilDimens
import com.eta.autoledger.ui.theme.OilTheme

enum class OilButtonVariant {
    Primary,
    Secondary,
    Danger,
}

@Composable
fun OilButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: OilButtonVariant = OilButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val colors = OilTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        label = "oil-button-press",
    )
    val background = when {
        !enabled -> colors.surfaceMuted
        variant == OilButtonVariant.Primary && pressed -> colors.accentPressed
        variant == OilButtonVariant.Primary -> colors.accent
        variant == OilButtonVariant.Danger && pressed -> colors.danger
        variant == OilButtonVariant.Danger -> colors.expense
        pressed -> colors.surfacePressed
        else -> colors.surfaceMuted
    }
    val contentColor = when {
        !enabled -> colors.inkFaint
        variant == OilButtonVariant.Primary || variant == OilButtonVariant.Danger -> colors.onAccent
        else -> colors.ink
    }
    val shape = OilTheme.shapes.capsule
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = OilDimens.minTouch)
            .clip(shape)
            .background(background)
            .then(
                if (variant == OilButtonVariant.Secondary) {
                    Modifier.border(1.dp, colors.line, shape)
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            OilLoadingGlyph(color = contentColor)
        } else if (leadingIcon != null) {
            Image(
                painter = rememberVectorPainter(leadingIcon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(contentColor),
            )
        }
        if (loading || leadingIcon != null) Box(Modifier.size(8.dp))
        OilText(
            text = text,
            style = OilTheme.typography.bodyStrong,
            color = contentColor,
            maxLines = 1,
        )
    }
}

enum class OilIconButtonTone {
    Plain,
    Primary,
    Danger,
}

@Composable
fun OilIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: OilIconButtonTone = OilIconButtonTone.Plain,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    val colors = OilTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        label = "oil-icon-button-press",
    )
    val restingBackground = when (tone) {
        OilIconButtonTone.Plain -> colors.surfaceMuted
        OilIconButtonTone.Primary -> colors.accent
        OilIconButtonTone.Danger -> colors.expense
    }
    val background = when {
        !pressed -> restingBackground
        tone == OilIconButtonTone.Primary -> colors.accentPressed
        tone == OilIconButtonTone.Danger -> colors.danger
        else -> colors.surfacePressed
    }
    val tint = when (tone) {
        OilIconButtonTone.Plain -> if (enabled) colors.ink else colors.inkFaint
        OilIconButtonTone.Primary, OilIconButtonTone.Danger -> colors.onAccent
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberVectorPainter(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(21.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** 内容槽版本，供页面在同一个触控底座里放置自定义极小图标。 */
@Composable
fun OilIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(OilTheme.colors.surfaceMuted)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun OilLoadingGlyph(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}
