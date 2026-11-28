package com.eta.autoledger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.eta.autoledger.ui.theme.OilDimens
import com.eta.autoledger.ui.theme.OilTheme
import kotlinx.coroutines.delay

/**
 * Oil UI 输入框。
 *
 * 页面根节点应使用 [OilKeyboardInsetContainer] 处理 IME inset，让滚动区域在软键盘
 * 弹出时整体缩短。输入框获得焦点后会请求自身滚入可视区域，避免多字段页面中
 * 当前输入项停留在键盘下方。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OilTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    labelColor: Color = Color.Unspecified,
    placeholderColor: Color = Color.Unspecified,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = OilTheme.colors
    val typography = OilTheme.typography
    val shape = RoundedCornerShape(OilDimens.radiusMd)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> colors.danger
            focused -> colors.accent
            else -> colors.line
        },
        label = "oil-field-border",
    )

    LaunchedEffect(focused) {
        if (focused) {
            // 等 IME resize 和滚动布局完成后再请求滚入，避免使用旧视口尺寸。
            delay(120)
            bringIntoViewRequester.bringIntoView()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            OilText(
                text = label,
                style = typography.label,
                color = if (labelColor == Color.Unspecified) colors.inkMuted else labelColor,
                modifier = Modifier.padding(start = 2.dp, bottom = 7.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusChanged { focused = it.isFocused }
                .heightIn(min = 56.dp)
                .clip(shape)
                .background(colors.surfaceMuted)
                .border(1.dp, borderColor, shape),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = typography.body.copy(
                color = if (enabled) colors.ink else colors.inkFaint,
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 15.dp, end = if (trailingContent == null) 15.dp else 4.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leadingContent != null) {
                        leadingContent()
                        Spacer(Modifier.size(10.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 14.dp),
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            OilText(
                                text = placeholder,
                                style = typography.body,
                                color = if (placeholderColor == Color.Unspecified) {
                                    colors.inkMuted
                                } else {
                                    placeholderColor
                                },
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Spacer(Modifier.size(4.dp))
                        trailingContent()
                    }
                }
            },
        )
        if (supportingText != null) {
            OilText(
                text = supportingText,
                style = typography.caption,
                color = if (isError) colors.danger else colors.inkMuted,
                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            )
        }
    }
}

/** 页面级 IME 避让容器。软键盘出现时只缩短可用区域，不覆盖当前输入项。 */
@Composable
fun OilKeyboardInsetContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().imePadding()) {
        content()
    }
}
