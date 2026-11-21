package com.eta.autoledger.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.eta.autoledger.R
import com.eta.autoledger.ui.theme.LedgerPalette
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Liquid Glass 设计系统（基于 io.github.kyant0:backdrop / AndroidLiquidGlass）。
 *
 * 背景使用项目同款壁纸并记录进 LayerBackdrop，玻璃从壁纸采样 → 模糊/折射/高光才肉眼可见。
 * [GlassAmbience.hue] 控制一层可拖动变色的氛围染色。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

object GlassAmbience {
    var hue by mutableFloatStateOf(212f)
    var energy by mutableFloatStateOf(0.45f)
}

/** 根容器：壁纸底图 + 氛围染色，记录进 LayerBackdrop 并下发给所有玻璃。 */
@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val hue = GlassAmbience.hue
    Box(modifier = Modifier.fillMaxSize().background(LedgerPalette.dark.page)) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Image(
                painter = painterResource(R.drawable.glass_wallpaper),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix().apply {
                        setToSaturation(1.3f)
                    }
                ),
                modifier = Modifier.fillMaxSize(),
            )
            // 压暗，保证浅色文字可读（玻璃仍能透出壁纸的色块与明暗过渡）
            Box(Modifier.fillMaxSize().background(LedgerPalette.dark.page.copy(alpha = 0.26f)))
            // 可拖动变色的氛围层
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.hsv(hue, 0.62f, 0.58f).copy(alpha = 0.30f * (0.4f + GlassAmbience.energy)))
            )
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            content()
        }
    }
}

/**
 * 一块液态玻璃。
 * @param tint 色相染色（Hue 混合 + 55% 叠色）
 * @param veil 表面色（不透明叠色，控制可读性的“奶玻璃”厚度）
 * @param interactive 按压果冻 + 跟随触点的镜面高光
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 20.dp,
    refractionHeight: Dp = 16.dp,
    refractionAmount: Dp = 28.dp,
    chromaticAberration: Boolean = true,
    tint: Color = Color.Unspecified,
    veil: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    interactive: Boolean = onClick != null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    val scope = rememberCoroutineScope()
    val interactiveHighlight = remember(scope) { InteractiveHighlight(scope) }

    val base: Modifier = if (backdrop != null) {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    depthEffect = true,
                    chromaticAberration = chromaticAberration,
                )
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            innerShadow = { InnerShadow.Default },
            layerBlock = if (interactive) {
                {
                    val w = size.width
                    val h = size.height
                    val progress = interactiveHighlight.pressProgress
                    val scale = lerp(1f, 1f + 4f.dp.toPx() / h, progress)
                    val maxOffset = size.minDimension
                    val offset = interactiveHighlight.offset
                    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
                    val maxDragScale = 4f.dp.toPx() / h
                    val angle = atan2(offset.y, offset.x)
                    scaleX = scale + maxDragScale * abs(cos(angle) * offset.x / size.maxDimension) * (w / h).fastCoerceAtMost(1f)
                    scaleY = scale + maxDragScale * abs(sin(angle) * offset.y / size.maxDimension) * (h / w).fastCoerceAtMost(1f)
                }
            } else {
                null
            },
            onDrawSurface = if (tint.isSpecified || veil.isSpecified) {
                {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint)
                    }
                    if (veil.isSpecified) drawRect(veil)
                }
            } else {
                null
            },
        )
    } else {
        modifier.clip(shape).background(if (veil.isSpecified) veil else LedgerPalette.dark.surface.copy(alpha = 0.35f))
    }

    val withClick = if (onClick != null) {
        base.clickable(
            interactionSource = null,
            indication = if (interactive) null else androidx.compose.foundation.LocalIndication.current,
            onClick = onClick,
        )
    } else base

    val withInteractive = if (interactive) {
        withClick
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
    } else withClick

    Box(modifier = withInteractive.padding(contentPadding), content = content)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Color.Unspecified,
    veil: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        tint = tint,
        veil = veil,
        onClick = onClick,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        blurRadius = 10.dp,
        refractionHeight = 12.dp,
        refractionAmount = 24.dp,
        tint = tint,
        veil = surfaceColor,
        onClick = onClick,
        contentPadding = contentPadding,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(44.dp),
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        blurRadius = 8.dp,
        refractionHeight = 8.dp,
        refractionAmount = 16.dp,
        tint = tint,
        veil = surfaceColor,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** 横向拖动改变全局氛围色相（"拖动变色"）。 */
fun Modifier.glassHueDrag(sensitivity: Float = 0.6f): Modifier = pointerInput(Unit) {
    detectHorizontalDragGestures { _, dragAmount ->
        val next = GlassAmbience.hue + dragAmount * sensitivity
        GlassAmbience.hue = ((next % 360f) + 360f) % 360f
    }
}

/** 把玻璃作为背景贴到任意容器上（弹窗/面板懒改造用）。 */
fun Modifier.glassBackground(
    shape: Shape,
    blurRadius: Dp = 20.dp,
    veil: Color = Color.Unspecified,
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null) {
        this.clip(shape).background(if (veil.isSpecified) veil else LedgerPalette.dark.surface.copy(alpha = 0.35f))
    } else {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(16.dp.toPx(), 26.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            innerShadow = { InnerShadow.Default },
            onDrawSurface = if (veil.isSpecified) {
                { drawRect(veil) }
            } else null,
        )
    }
}
