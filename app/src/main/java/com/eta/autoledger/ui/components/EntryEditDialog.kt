package com.eta.autoledger.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账目编辑弹窗（首页与搜索页共用）。
 *
 * 从 HomeScreen 的私有实现抽出来：搜索页点开一条结果后要弹「和首页一模一样」的编辑器，
 * 两处各写一份必然漂移（改金额校验、加字段时只改一处）。这里保持纯 UI，
 * 数据写入交给调用方的 [onSave] / [onDelete] —— 首页走 HomeViewModel（带撤销），
 * 搜索页直接走 LedgerRepository（Room Flow 自己会刷新结果）。
 *
 * 调用方负责「常驻组合、只切状态」，否则 [AppDialog] 的退场动画没机会播。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryEditDialog(
    show: Boolean,
    entry: LedgerEntry,
    onDismiss: () -> Unit,
    onSave: (Double, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // 以 show 为 key：关掉再打开另一条账目时按新数据重建，不残留上一次的输入与报错态。
    var amount by remember(show, entry.id) { mutableStateOf("%.2f".format(entry.amount)) }
    var amountError by remember(show, entry.id) { mutableStateOf(false) }
    var category by remember(show, entry.id) { mutableStateOf(entry.category) }
    var note by remember(show, entry.id) { mutableStateOf(entry.note) }
    val typeIdx = when (entry.type) {
        "income" -> 1
        "other" -> 2
        else -> 0
    }
    // 分类选项来自「设置 → 记账分类管理」，与那里共用同一份 CategoryStore 数据
    val options = remember(entry.type) {
        (CategoryStore.names(context, typeIdx) + entry.category).distinct()
    }

    AppDialog(
        show = show,
        title = "编辑账目",
        maxWidth = 560.dp,
        onDismissRequest = onDismiss,
    ) {
        Text(
            "金额",
            style = MiuixTheme.textStyles.footnote1,
            color = if (amountError) LedgerTheme.danger else LedgerTheme.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            value = amount,
            onValueChange = { input ->
                amount = input.filter { it.isDigit() || it == '.' }
                if (amountError) amountError = false
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        if (amountError) {
            Spacer(Modifier.height(4.dp))
            Text(
                "金额格式不正确，请输入大于 0 的数字，如 12.50",
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.danger,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("分类", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { name ->
                    // selected 用纯分类名比较（不用带 emoji 的展示串），图标与文字分开传
                    CategoryChip(
                        text = name,
                        icon = CategoryStore.emoji(name),
                        selected = name == category,
                        onClick = { category = name },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("备注", style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.textSecondary)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = "删除",
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    color = LedgerTheme.tint(LedgerTheme.danger, 0.14f),
                    textColor = LedgerTheme.danger,
                ),
            )
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            val saveInteraction = remember { MutableInteractionSource() }
            val savePressed by saveInteraction.collectIsPressedAsState()
            val buttonShape = RoundedCornerShape(LocalDimens.current.radiusCapsule)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(buttonShape)
                    .background(if (savePressed) LedgerTheme.accentPressed else LedgerTheme.accent)
                    .clickable(
                        interactionSource = saveInteraction,
                        indication = LocalIndication.current,
                        onClick = {
                            val parsed = amount.toDoubleOrNull()
                            if (parsed == null || parsed <= 0.0) {
                                amountError = true
                                return@clickable
                            }
                            onSave(parsed, category, note)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", color = LedgerTheme.onAccent)
            }
        }
    }
}
