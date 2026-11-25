package com.eta.autoledger.settings

import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.components.ThemeSurfaceCard
import androidx.compose.foundation.layout.PaddingValues
import com.eta.autoledger.util.AppDateFormats
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val AccentBlue: Color get() = LedgerTheme.accent
internal val TextPrimary: Color get() = LedgerTheme.text
internal val TextSecondary: Color get() = LedgerTheme.textSecondary
internal val CardBorder: Color get() = LedgerTheme.borderSoft

@Composable
internal fun SettingsPageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.height(82.dp))
    }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    ThemeSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        content = { content() },
    )
}

@Composable
internal fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(LedgerTheme.divider),
    )
}

@Composable
internal fun HintText(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 12.sp, color = TextSecondary),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}


@Composable
internal fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 15.sp, color = TextPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary))
    }
}

internal fun formatStamp(value: Long): String =
    if (value <= 0L) "从未" else AppDateFormats.format(value, AppDateFormats.dashMonthDayTime)

/**
 * 系统权限与保活体检页。
 *
 * 这些开关都没有 API 能直接申请（属于用户级设置），所以这里只做「读状态 + 一键跳转」，
 * 并把两条采集通道的最近入账时间摊开，用于回答「为什么没记账」。
 */
