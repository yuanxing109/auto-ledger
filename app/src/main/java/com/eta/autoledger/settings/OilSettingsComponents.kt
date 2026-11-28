package com.eta.autoledger.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.components.OilCard
import com.eta.autoledger.ui.components.OilKeyboardInsetContainer
import com.eta.autoledger.ui.components.OilSwitch
import com.eta.autoledger.ui.components.OilText
import com.eta.autoledger.ui.theme.OilTheme
import com.eta.autoledger.util.AppDateFormats

internal val AccentBlue: Color get() = LedgerTheme.accent
internal val TextPrimary: Color get() = LedgerTheme.text
internal val TextSecondary: Color get() = LedgerTheme.textSecondary
internal val CardBorder: Color get() = LedgerTheme.borderSoft

@Composable
internal fun OilSettingsPageColumn(content: @Composable () -> Unit) {
    OilKeyboardInsetContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OilTheme.dimens.pagePadding, vertical = OilTheme.dimens.gap),
            verticalArrangement = Arrangement.spacedBy(OilTheme.dimens.gapLarge),
        ) {
            content()
            Spacer(Modifier.height(82.dp))
        }
    }
}

@Composable
internal fun OilSettingsGroup(content: @Composable () -> Unit) {
    OilCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        content()
    }
}

@Composable
internal fun OilSettingsDivider(startPadding: Int = 16) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding.dp)
            .height(1.dp)
            .background(OilTheme.colors.divider),
    )
}

@Composable
internal fun OilHintText(text: String) {
    OilText(
        text = text,
        style = OilTheme.typography.caption,
        color = OilTheme.colors.inkMuted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
internal fun OilInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OilText(
            text = title,
            style = OilTheme.typography.body,
            color = OilTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        OilText(
            text = value,
            style = OilTheme.typography.caption,
            color = OilTheme.colors.inkMuted,
        )
    }
}

@Composable
internal fun OilSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = OilTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            OilText(
                text = title,
                style = OilTheme.typography.bodyStrong,
                color = colors.ink,
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                OilText(
                    text = summary,
                    style = OilTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.size(12.dp))
            OilText(
                text = value,
                style = OilTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            trailing()
        }
    }
}

@Composable
internal fun OilSwitchSettingsRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    OilSettingsRow(
        title = title,
        summary = summary,
        modifier = modifier,
        trailing = {
            OilSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

internal fun formatStamp(value: Long): String =
    if (value <= 0L) "从未" else AppDateFormats.format(value, AppDateFormats.dashMonthDayTime)
