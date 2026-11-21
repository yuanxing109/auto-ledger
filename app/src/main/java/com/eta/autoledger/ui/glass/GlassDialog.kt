package com.eta.autoledger.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eta.autoledger.ui.LedgerTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃对话框：模糊更重、折射更深（参考 AndroidLiquidGlass DialogContent）。
 */
@Composable
fun GlassDialog(
    show: Boolean = true,
    title: String,
    maxWidth: Dp = 560.dp,
    summary: String? = null,
    outsideMargin: DpSize = DpSize(20.dp, 20.dp),
    insideMargin: DpSize = DpSize(20.dp, 20.dp),
    onDismissRequest: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(insideMargin.width, insideMargin.height),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = outsideMargin.width, vertical = outsideMargin.height),
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth),
                shape = RoundedCornerShape(28.dp),
                blurRadius = 16.dp,
                refractionHeight = 26.dp,
                refractionAmount = 44.dp,
                veil = LedgerTheme.surface.copy(alpha = 0.62f),
                contentPadding = contentPadding,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = title, style = MiuixTheme.textStyles.title3, color = LedgerTheme.text)
                    if (summary != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(text = summary, style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
                    }
                    Spacer(Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}
