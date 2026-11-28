package com.eta.autoledger.ui.components

import com.eta.autoledger.ui.theme.OilTheme
import com.eta.autoledger.ui.components.OilText
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.network.AiClient
import com.eta.autoledger.screenshot.AiQuickEntry
import com.eta.autoledger.screenshot.AiQuickEntryHelper
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.theme.LedgerDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自然语言记账输入框。模型解析成功后只把结果交给调用方，不直接写库。
 */
@Composable
fun AiQuickEntryDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onParsed: (AiQuickEntry) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dimens = LocalDimens.current
    var text by rememberSaveable(show) { mutableStateOf("") }
    // 请求本身不可跨 Activity 重建恢复；若把 loading 持久化，旋转取消协程后会永久锁死弹窗。
    var loading by remember(show) { mutableStateOf(false) }
    var inlineError by rememberSaveable(show) { mutableStateOf<String?>(null) }
    val requestJob = remember { mutableStateOf<Job?>(null) }
    val submitEnabled = text.isNotBlank() && !loading
    val inputShape = RoundedCornerShape(dimens.radiusCard)

    fun dismissAndCancel() {
        requestJob.value?.cancel()
        requestJob.value = null
        loading = false
        onDismiss()
    }

    OilDialog(
        show = show,
        title = "AI 帮我记",
        summary = "用自然语言告诉我你怎么花的钱，例如：昨天中午吃饭花了 35，或者 打车 18.5",
        maxWidth = 500.dp,
        outsideMargin = DpSize(dimens.cardPadding, dimens.cardPadding),
        insideMargin = DpSize(dimens.cardPadding, dimens.cardPadding),
        panelColor = LedgerTheme.surface,
        onDismissRequest = ::dismissAndCancel,
    ) {
        OilText(
            text = "描述这笔账",
            style = OilTheme.typography.caption,
            color = LedgerTheme.textSecondary,
        )
        Spacer(Modifier.height(dimens.gapSmall))
        BasicTextField(
            value = text,
            onValueChange = {
                if (!loading) {
                    text = it
                    inlineError = null
                }
            },
            enabled = !loading,
            textStyle = OilTheme.typography.body.copy(color = LedgerTheme.text),
            cursorBrush = SolidColor(LedgerTheme.accent),
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "自然语言账单描述" }
                .clip(inputShape)
                .background(LedgerTheme.inset)
                .border(
                    width = 1.dp,
                    color = if (inlineError == null) LedgerTheme.borderSoft else LedgerTheme.danger,
                    shape = inputShape,
                )
                .padding(dimens.gap),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        OilText(
                            text = "例如：昨天打车 18.5",
                            style = OilTheme.typography.body,
                            color = LedgerTheme.textTertiary,
                        )
                    }
                    inner()
                }
            },
        )
        inlineError?.let {
            Spacer(Modifier.height(dimens.gapSmall))
            OilText(
                text = it,
                style = OilTheme.typography.caption,
                color = LedgerTheme.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(dimens.gap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LedgerDimens.minTouch)
                .clip(RoundedCornerShape(dimens.radiusCapsule))
                .background(if (submitEnabled) LedgerTheme.accent else LedgerTheme.inset)
                .semantics { role = Role.Button }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    enabled = submitEnabled,
                    onClick = {
                        if (!submitEnabled) return@clickable
                        loading = true
                        inlineError = null
                        requestJob.value = scope.launch {
                            val job = coroutineContext[Job]
                            try {
                                val parsed = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val prefs = AppPrefs.get(context)
                                        AiQuickEntryHelper.parse(
                                            prefs = prefs,
                                            text = text,
                                            expenseCategories = CategoryStore.names(context, 0),
                                            incomeCategories = CategoryStore.names(context, 1),
                                        )
                                    }.getOrNull()
                                }
                                loading = false
                                if (parsed == null) {
                                    inlineError = AiClient.FORMAT_ERROR_MESSAGE
                                } else {
                                    text = ""
                                    onParsed(parsed)
                                    onDismiss()
                                }
                            } finally {
                                if (requestJob.value === job) {
                                    loading = false
                                    requestJob.value = null
                                }
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            OilText(
                text = if (loading) "正在解析…" else "填入表单",
                style = OilTheme.typography.bodyStrong,
                color = if (submitEnabled) LedgerTheme.onAccent else LedgerTheme.textTertiary,
            )
        }
    }
}
