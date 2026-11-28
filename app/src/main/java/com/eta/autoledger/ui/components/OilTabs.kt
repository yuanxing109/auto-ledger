package com.eta.autoledger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.theme.OilDimens
import com.eta.autoledger.ui.theme.OilTheme

@Composable
fun OilTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OilTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceMuted)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = selectedIndex == index
            val background by animateColorAsState(
                targetValue = if (selected) colors.accent else colors.surfaceMuted,
                label = "oil-tab-background",
            )
            val foreground by animateColorAsState(
                targetValue = if (selected) colors.onAccent else colors.inkMuted,
                label = "oil-tab-foreground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OilDimens.minTouch)
                    .clip(RoundedCornerShape(50))
                    .background(background)
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                        role = Role.Tab,
                        onClick = { onSelected(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                OilText(
                    text = label,
                    style = OilTheme.typography.bodyStrong,
                    color = foreground,
                    maxLines = 1,
                )
            }
        }
    }
}
