package com.eta.autoledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.eta.autoledger.data.ImportDedup
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.ui.LedgerTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 导入预览：解析完先给用户看清楚「多少条新的、多少条疑似重复」，再决定怎么写库。
 * 疑似重复可以逐条切换 保留 / 跳过 / 合并，也可以用底部三个按钮一次性定全部。
 */
@Composable
fun ImportPreviewDialog(
    show: Boolean,
    plan: ImportDedup.Plan,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCommit: (List<ImportDedup.Action>) -> Unit,
) {
    // 默认「合并」：库里那条是既有账目，只把更完整的描述补进去，不会多出一条。
    val actions = remember(plan) {
        mutableStateListOf<ImportDedup.Action>().apply {
            repeat(plan.suspects.size) { add(ImportDedup.Action.MERGE) }
        }
    }
    var expanded by remember(plan) { mutableStateOf(false) }
    val pending = plan.fresh.size + actions.count { it != ImportDedup.Action.SKIP }

    AppDialog(
        show = show,
        title = "导入预览",
        summary = plan.summary,
        maxWidth = 560.dp,
        outsideMargin = DpSize(20.dp, 20.dp),
        panelColor = LedgerTheme.card,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        if (plan.suspects.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                // 全部是老数据时，「没有发现重复」会让人误以为文件是空的，
                // 这种情况要明确说是「这份账单之前已经导过了」。
                text = if (plan.fresh.isEmpty() && plan.skipped > 0) {
                    "这份账单之前已经导入过，没有新账目需要写入。"
                } else {
                    "没有发现重复，直接写入即可。"
                },
                style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary),
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.inset)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "收起疑似重复列表" else "展开查看疑似重复（" + plan.suspects.size + " 条）",
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.accent),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "︿" else "﹀",
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.height(2.dp))
                    plan.suspects.forEachIndexed { index, suspect ->
                        SuspectCard(
                            index = index,
                            suspect = suspect,
                            action = actions.getOrElse(index) { ImportDedup.Action.MERGE },
                            onCycle = {
                                val next = when (actions.getOrElse(index) { ImportDedup.Action.MERGE }) {
                                    ImportDedup.Action.MERGE -> ImportDedup.Action.SKIP
                                    ImportDedup.Action.SKIP -> ImportDedup.Action.KEEP
                                    ImportDedup.Action.KEEP -> ImportDedup.Action.MERGE
                                }
                                actions[index] = next
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 两个主按钮覆盖绝大多数选择：要么信文件全收，要么把疑似重复全滤掉。
            // 想逐条细调的人用上面的列表点选，不需要第三条「全部合并」——
            // 全合并等于什么都不做（库里那条原样留着），放个按钮只会误导。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction("全部保留", Modifier.weight(1f)) {
                    actions.indices.forEach { actions[it] = ImportDedup.Action.KEEP }
                }
                QuickAction("自动过滤疑似重复项", Modifier.weight(1.4f)) {
                    actions.indices.forEach { actions[it] = ImportDedup.Action.SKIP }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LedgerTheme.inset)
                    .clickable(enabled = !busy) { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary))
            }
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (busy) LedgerTheme.accentPressed else LedgerTheme.accent)
                    .clickable(enabled = !busy && pending > 0) { onCommit(actions.toList()) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (busy) "正在写入…" else "导入 " + pending + " 条",
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.onAccent),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SuspectCard(
    index: Int,
    suspect: ImportDedup.Suspect,
    action: ImportDedup.Action,
    onCycle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LedgerTheme.inset)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#" + (index + 1) + " · 相差 " + suspect.minutesApart + " 分钟",
                style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary),
                modifier = Modifier.weight(1f),
            )
            val label = when (action) {
                ImportDedup.Action.KEEP -> "保留"
                ImportDedup.Action.SKIP -> "跳过"
                ImportDedup.Action.MERGE -> "合并"
            }
            val tint = when (action) {
                ImportDedup.Action.KEEP -> LedgerTheme.danger
                ImportDedup.Action.SKIP -> LedgerTheme.textSecondary
                ImportDedup.Action.MERGE -> LedgerTheme.accent
            }
            Text(
                text = label + " · 点击切换",
                style = MiuixTheme.textStyles.footnote1.copy(color = tint),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCycle)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        CompareLine("现有", suspect.existing)
        CompareLine("待导入", suspect.incoming)
        val verdict = suspect.aiVerdict
        if (verdict != null) {
            Text(
                text = if (verdict) "AI 判断：同一笔消费" else "AI 判断：不是同一笔",
                style = MiuixTheme.textStyles.footnote1.copy(
                    color = if (verdict) LedgerTheme.accent else LedgerTheme.textSecondary,
                ),
            )
        }
    }
}

@Composable
private fun CompareLine(label: String, entry: LedgerEntry) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary),
            modifier = Modifier.width(48.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.note.ifBlank { entry.category },
                style = MiuixTheme.textStyles.body1.copy(color = LedgerTheme.text),
            )
            Text(
                text = entry.time + " · " + entry.category + " · " +
                    "%.2f".format(entry.amount) + " 元 · " + entry.source,
                style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textSecondary),
            )
        }
    }
}

@Composable
private fun QuickAction(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LedgerTheme.inset)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.text))
    }
}
