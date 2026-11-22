package com.eta.autoledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.LedgerTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分类 Chip：图标(emoji) 与文字分开传入，选中与否由纯数据（分类名）决定，
 * 不把 emoji 拼进任何比较逻辑里——避免「选中的是带 emoji 的字符串」这类状态错配。
 *
 * 选中态：实心 accent 底 + onAccent 文字 + accent 边（蓝底蓝边），一眼可辨。
 * 未选中：surface 底 + border 边 + 次级文字。
 */
@Composable
fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: String? = null,
) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) LedgerTheme.accent else LedgerTheme.surface
    val bd = if (selected) LedgerTheme.accent else LedgerTheme.border
    val fg = if (selected) LedgerTheme.onAccent else LedgerTheme.textSecondary
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, bd, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = if (icon.isNullOrBlank()) text else "$icon $text",
            style = MiuixTheme.textStyles.footnote1,
            color = fg,
        )
    }
}
