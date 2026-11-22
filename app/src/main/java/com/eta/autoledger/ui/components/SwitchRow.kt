package com.eta.autoledger.ui.components

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
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置项：标题（+可选说明）+ Miuix Switch。
 *
 * Switch 显式指定 accent 蓝——设计系统里绿色仅用于收入，交互强调色是 accent。
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
) {
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.switchColors(
                checkedThumbColor = LedgerTheme.surface,
                uncheckedThumbColor = LedgerTheme.textSecondary,
                checkedTrackColor = LedgerTheme.accent,
                uncheckedTrackColor = LedgerTheme.divider,
            ),
        )
    }
}
