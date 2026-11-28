package com.eta.autoledger.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.eta.autoledger.ui.theme.OilTheme

@Composable
fun OilText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    var resolvedStyle = (style ?: OilTheme.typography.body).copy(
        color = if (color == Color.Unspecified) OilTheme.colors.ink else color,
    )
    if (textAlign != null) {
        resolvedStyle = resolvedStyle.copy(textAlign = textAlign)
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolvedStyle,
        maxLines = maxLines,
        overflow = overflow,
    )
}
