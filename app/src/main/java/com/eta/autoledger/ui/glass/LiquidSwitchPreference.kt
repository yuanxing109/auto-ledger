package com.eta.autoledger.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.LedgerTheme
import com.kyant.backdrop.catalog.components.LiquidToggle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置项 + 液态玻璃开关（对应 AndroidLiquidGlass 的 LiquidToggle）。
 */
@Composable
fun LiquidSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
) {
    val backdrop = LocalGlassBackdrop.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MiuixTheme.textStyles.title4, color = LedgerTheme.text)
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = summary, style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (backdrop != null) {
            LiquidToggle(
                selected = { checked },
                onSelect = onCheckedChange,
                backdrop = backdrop,
            )
        }
    }
}
