package com.eta.autoledger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.theme.OilTheme

data class OilNavItem(
    val label: String,
    val icon: ImageVector,
)

/** 贴边账页标题栏，不使用浮动胶囊和半透明背景。 */
@Composable
fun OilTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: ImageVector? = null,
    onNavigationClick: () -> Unit = {},
    applyStatusBarInset: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = OilTheme.colors
    val typography = OilTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .then(if (applyStatusBarInset) Modifier.statusBarsPadding() else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                OilIconButton(
                    icon = navigationIcon,
                    contentDescription = "返回",
                    onClick = onNavigationClick,
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                OilText(
                    text = title,
                    style = typography.pageTitle,
                    color = colors.ink,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    OilText(
                        text = subtitle,
                        style = typography.caption,
                        color = colors.inkMuted,
                        maxLines = 1,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
    }
}

/** 实色三栏导航。选中态同时使用顶部标记、字重和颜色表达。 */
@Composable
fun OilBottomBar(
    items: List<OilNavItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OilTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .selectableGroup(),
        ) {
            items.forEachIndexed { index, item ->
                OilBottomBarItem(
                    item = item,
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OilBottomBarItem(
    item: OilNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OilTheme.colors
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.ink else colors.inkMuted,
        label = "oil-nav-color",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 3.dp)
                .background(if (selected) colors.accent else colors.surface),
        )
        Spacer(Modifier.height(6.dp))
        Image(
            painter = rememberVectorPainter(item.icon),
            contentDescription = item.label,
            modifier = Modifier.size(21.dp),
            colorFilter = ColorFilter.tint(contentColor),
        )
        Spacer(Modifier.height(3.dp))
        OilText(
            text = item.label,
            style = OilTheme.typography.label,
            color = contentColor,
            maxLines = 1,
        )
    }
}
