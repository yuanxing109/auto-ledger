package com.eta.autoledger.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.data.LedgerDatabase
import com.eta.autoledger.data.RuleEntity
import com.eta.autoledger.data.RuleEngine
import com.eta.autoledger.data.RuleSafety
import com.eta.autoledger.data.RuleSeed
import com.eta.autoledger.data.RuleType
import com.eta.autoledger.data.MoneyPatterns
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.theme.LedgerDimens
import com.eta.autoledger.ui.components.AppDialog
import com.eta.autoledger.ui.components.ThemeSurfaceCard
import com.eta.autoledger.ui.window.LocalSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 高级解析规则管理页（设置 → 自动记账 → 高级解析规则）。
 *
 * 分两个 Tab：系统规则（随版本播种，只读，仅允许启停）与我的规则（可新建、编辑、删除、测试）。
 *
 * 为什么规则页自己维护一份 JSON 组装与解析，而不复用 RuleSeed 的 buildJson：
 * 那个函数是 private 且只服务播种；这里是用户输入，需要校验、需要把拒绝理由回显给人，
 * 语义不同，硬复用会把两件事绑死。
 */

/** 新建规则时预填的金额正则：优先带千分位的写法，其次普通小数，末尾必须带「元」。 */
private const val DEFAULT_AMOUNT_REGEX =
    MoneyPatterns.EDITOR_DEFAULT

@Composable
fun RuleManagePage() {
    val context = LocalContext.current
    val dao = remember(context) { LedgerDatabase.get(context).ruleDao() }
    val prefs = remember(context) { AppPrefs.get(context) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val dimens = LocalDimens.current

    var rules by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }

    var editorShow by remember { mutableStateOf(false) }
    var editorSeed by remember { mutableStateOf<RuleEntity?>(null) }

    LaunchedEffect(reloadKey) {
        rules = withContext(Dispatchers.IO) { dao.getAll() }
        loaded = true
    }

    val systemRules = rules.filter { it.creator == RuleEntity.CREATOR_SYSTEM }
    val userRules = rules.filter { it.creator == RuleEntity.CREATOR_USER }
    val visible = if (tab == 0) systemRules else userRules
    val pageTitle = when (tab) {
        0 -> "系统规则"
        1 -> "我的规则"
        else -> "全局词库"
    }
    val pageSummary = when (tab) {
        0 -> "系统规则由内置规则库维护，只能启停。"
        1 -> "自定义解析规则，可新建、编辑、测试与删除。"
        else -> "维护所有短信与通知共用的拦截词和强制认账词。"
    }

    fun toggle(rule: RuleEntity, next: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                dao.upsert(rule.copy(enabled = next, updatedAt = System.currentTimeMillis()))
            }
            RuleEngine.invalidate()
            reloadKey++
        }
    }

    SettingsPageColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pageTitle, style = MiuixTheme.textStyles.title3, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    pageSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = TextSecondary,
                )
            }
            if (tab != 2) {
                IconButton(onClick = { editorSeed = null; editorShow = true }) {
                    Icon(
                        imageVector = MiuixIcons.Add,
                        contentDescription = "新建规则",
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        RuleTabStrip(
            tab = tab,
            systemCount = systemRules.size,
            userCount = userRules.size,
            onTab = { tab = it },
        )

        if (tab == 2) {
            GlobalWordLibraryContent(prefs)
        } else {
            if (!loaded) {
                HintText("正在加载规则…")
            } else if (visible.isEmpty()) {
                SettingsCard {
                    Column(Modifier.fillMaxWidth().padding(dimens.cardPadding)) {
                        Text(
                            if (tab == 0) "没有系统规则" else "还没有自定义规则",
                            style = MiuixTheme.textStyles.body1,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (tab == 0) {
                                "内置规则会在应用启动时自动播种。"
                            } else {
                                "点右上角「+」新建一条，例如把某条固定话术的短信判为收入。"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = TextSecondary,
                        )
                    }
                }
            } else {
                SettingsCard {
                    visible.forEachIndexed { index, rule ->
                        if (index > 0) RowDivider()
                        RuleRow(
                            rule = rule,
                            onToggle = { toggle(rule, it) },
                            onEdit = if (rule.creator == RuleEntity.CREATOR_USER) {
                                { editorSeed = rule; editorShow = true }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            HintText("匹配顺序：我的规则优先于系统规则；同一条规则内先判拦截词，再定收支方向，最后抠金额。")
        }
    }

    RuleEditorDialog(
        show = editorShow,
        seed = editorSeed,
        onDismiss = { editorShow = false },
        onSave = { rule ->
            editorShow = false
            scope.launch {
                withContext(Dispatchers.IO) { dao.upsert(rule) }
                RuleEngine.invalidate()
                reloadKey++
                snackbar.show("已保存「${rule.name}」")
            }
        },
        onDelete = { rule ->
            editorShow = false
            scope.launch {
                val removed = withContext(Dispatchers.IO) { dao.deleteUserRule(rule.id) }
                RuleEngine.invalidate()
                reloadKey++
                snackbar.show(if (removed > 0) "已删除「${rule.name}」" else "系统规则不可删除")
            }
        },
    )
}

/** 全局词库与具体规则解耦：所有短信和通知共用同一份拦截词/认账词。 */
@Composable
private fun GlobalWordLibraryContent(prefs: AppPrefs) {
    val dimens = LocalDimens.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var ignoreInput by remember { mutableStateOf("") }
    var forceInput by remember { mutableStateOf("") }

    HintText(
        "当一条短信同时包含拦截词和认账词时，以认账词为准。\n" +
            "例：「已支付」在认账词里，所以「花呗已支付 46.94 元」仍会入账。",
    )

    SettingsCard {
        Column(modifier = Modifier.padding(dimens.cardPadding)) {
            Text(
                "忽略（拦截）关键词",
                style = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
            )
            Spacer(Modifier.height(dimens.gapSmall / 2))
            Text(
                "短信里出现这些词就不记账。「下单」「先用后付」这类只是占额度、钱还没扣。",
                style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary),
            )
            Spacer(Modifier.height(dimens.gap))
            KeywordChips(
                words = prefs.ignoreKeywords,
                onRemove = { word -> prefs.ignoreKeywords = prefs.ignoreKeywords - word },
            )
            Spacer(Modifier.height(dimens.gap))
            KeywordInput(
                value = ignoreInput,
                placeholder = "输入词语后回车添加，如「抖音月付」",
                onValueChange = { ignoreInput = it },
                onSubmit = {
                    addKeyword(
                        raw = ignoreInput,
                        current = prefs.ignoreKeywords,
                        label = "拦截词",
                        onSet = { prefs.ignoreKeywords = it },
                        onDuplicate = { message ->
                            scope.launch { snackbar.show(message) }
                        },
                    )
                    ignoreInput = ""
                },
            )
        }
    }

    SettingsCard {
        Column(modifier = Modifier.padding(dimens.cardPadding)) {
            Text(
                "强制认账关键词",
                style = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
            )
            Spacer(Modifier.height(dimens.gapSmall / 2))
            Text(
                "出现这些词就认定钱真的动了，即使同时命中拦截词也会入账。",
                style = MiuixTheme.textStyles.footnote1.copy(color = TextSecondary),
            )
            Spacer(Modifier.height(dimens.gap))
            KeywordChips(
                words = prefs.forceKeywords,
                onRemove = { word -> prefs.forceKeywords = prefs.forceKeywords - word },
            )
            Spacer(Modifier.height(dimens.gap))
            KeywordInput(
                value = forceInput,
                placeholder = "输入词语后回车添加，如「扣费成功」",
                onValueChange = { forceInput = it },
                onSubmit = {
                    addKeyword(
                        raw = forceInput,
                        current = prefs.forceKeywords,
                        label = "认账词",
                        onSet = { prefs.forceKeywords = it },
                        onDuplicate = { message ->
                            scope.launch { snackbar.show(message) }
                        },
                    )
                    forceInput = ""
                },
            )
        }
    }

    SettingsCard {
        ArrowPreference(
            title = "恢复默认词库",
            summary = "把两组词都还原成内置预设，你手动加的会被清掉",
            onClick = {
                prefs.ignoreKeywords = AppPrefs.DEFAULT_IGNORE_KEYWORDS
                prefs.forceKeywords = AppPrefs.DEFAULT_FORCE_KEYWORDS
                scope.launch { snackbar.show("已恢复默认词库") }
            },
        )
    }

    HintText("修改即时生效，下一条短信或通知就会按新词库判断。")
}

private fun addKeyword(
    raw: String,
    current: Set<String>,
    label: String,
    onSet: (Set<String>) -> Unit,
    onDuplicate: (String) -> Unit,
) {
    val word = raw.trim()
    if (word.isEmpty()) return
    if (word in current) {
        onDuplicate("「$word」已在${label}里")
        return
    }
    onSet(current + word)
}

/** 词条以可删除 Chip 展示，FlowRow 保证长词和大量词自动换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordChips(words: Set<String>, onRemove: (String) -> Unit) {
    val dimens = LocalDimens.current
    if (words.isEmpty()) {
        Text(
            "还没有任何词，短信将完全走内置规则。",
            style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textTertiary),
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapSmall),
        verticalArrangement = Arrangement.spacedBy(dimens.gapSmall),
    ) {
        words.forEach { word ->
            val shape = RoundedCornerShape(dimens.radiusCapsule)
            Row(
                modifier = Modifier
                    .heightIn(min = LedgerDimens.minTouch)
                    .clip(shape)
                    .background(LedgerTheme.inset)
                    .border(1.dp, LedgerTheme.borderSoft, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClickLabel = "删除「$word」",
                    ) { onRemove(word) }
                    .padding(horizontal = dimens.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    word,
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.text),
                )
                Spacer(Modifier.width(dimens.gapSmall / 2))
                Text(
                    "×",
                    style = MiuixTheme.textStyles.footnote1.copy(color = LedgerTheme.textTertiary),
                )
            }
        }
    }
}

/** 单行输入，回车或右侧「添加」提交。 */
@Composable
private fun KeywordInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.radiusSmall)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LedgerDimens.minTouch)
            .clip(shape)
            .background(LedgerTheme.inset)
            .border(1.dp, LedgerTheme.borderSoft, shape)
            .padding(start = dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.textTertiary),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.text),
                cursorBrush = SolidColor(LedgerTheme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
        }
        Spacer(Modifier.width(dimens.gapSmall))
        val enabled = value.isNotBlank()
        Box(
            modifier = Modifier
                .height(LedgerDimens.minTouch)
                .clip(RoundedCornerShape(dimens.radiusSmall))
                .background(if (enabled) LedgerTheme.accent else LedgerTheme.surfaceVariant)
                .clickable(enabled = enabled, onClickLabel = "添加词条", onClick = onSubmit)
                .padding(horizontal = dimens.cardPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "添加",
                style = MiuixTheme.textStyles.footnote1.copy(
                    color = if (enabled) LedgerTheme.onAccent else LedgerTheme.textTertiary,
                ),
            )
        }
    }
}

@Composable
private fun RuleTabStrip(tab: Int, systemCount: Int, userCount: Int, onTab: (Int) -> Unit) {
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.radiusCard)
    ThemeSurfaceCard(
        shape = shape,
        contentPadding = PaddingValues(4.dp),
    ) {
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf<Pair<String, Int?>>(
            "系统规则" to systemCount,
            "我的规则" to userCount,
            "全局词库" to null,
        ).forEachIndexed { index, item ->
            val selected = tab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.radiusSmall))
                    .background(if (selected) LedgerTheme.tint(AccentBlue) else LedgerTheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                    ) { onTab(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.second?.let { "${item.first} $it" } ?: item.first,
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) AccentBlue else TextSecondary,
                )
            }
        }
    }
    }
}

@Composable
private fun RuleRow(rule: RuleEntity, onToggle: (Boolean) -> Unit, onEdit: (() -> Unit)?) {
    val dimens = LocalDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onEdit == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                    ) { onEdit() }
                }
            )
            .padding(horizontal = dimens.cardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MiuixTheme.textStyles.title4, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(ruleSubtitle(rule), style = MiuixTheme.textStyles.footnote1, color = TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.switchColors(
                checkedThumbColor = LedgerTheme.surface,
                uncheckedThumbColor = LedgerTheme.textSecondary,
                checkedTrackColor = LedgerTheme.accent,
                uncheckedTrackColor = LedgerTheme.divider,
            ),
        )
    }
}

/** 列表副标题：类型 · 目标应用 · 是否自动入账。 */
private fun ruleSubtitle(rule: RuleEntity): String {
    val type = when (rule.type) {
        RuleType.NOTICE -> "通知"
        RuleType.SMS -> "短信"
        RuleType.OCR -> "识图"
        else -> rule.type
    }
    val target = rule.app ?: "全部应用"
    val mode = if (rule.autoRecord) "自动入账" else "仅提示"
    return "$type · $target · $mode"
}

@Composable
private fun RuleEditorDialog(
    show: Boolean,
    seed: RuleEntity?,
    onDismiss: () -> Unit,
    onSave: (RuleEntity) -> Unit,
    onDelete: (RuleEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initial = remember(show, seed) { readRuleFields(seed) }

    var name by remember(show, seed) { mutableStateOf(seed?.name ?: "") }
    var type by remember(show, seed) { mutableStateOf(seed?.type ?: RuleType.SMS) }
    var app by remember(show, seed) { mutableStateOf(seed?.app ?: "") }
    var autoRecord by remember(show, seed) { mutableStateOf(seed?.autoRecord ?: true) }
    var amountText by remember(show, seed) {
        mutableStateOf(initial.amount.ifEmpty { listOf(DEFAULT_AMOUNT_REGEX) }.joinToString("\n"))
    }
    var expenseText by remember(show, seed) { mutableStateOf(initial.expense.joinToString("，")) }
    var incomeText by remember(show, seed) { mutableStateOf(initial.income.joinToString("，")) }
    var blockText by remember(show, seed) { mutableStateOf(initial.block.joinToString("，")) }
    var catsText by remember(show, seed) { mutableStateOf(formatCategories(initial.categories)) }
    var direction by remember(show, seed) { mutableStateOf(initial.direction ?: "") }
    var testBody by remember(show) { mutableStateOf("") }
    var testResult by remember(show) { mutableStateOf<String?>(null) }
    var error by remember(show) { mutableStateOf<String?>(null) }

    AppDialog(
        show = show,
        maxWidth = 520.dp,
        outsideMargin = DpSize(20.dp, 24.dp),
        panelColor = LedgerTheme.card,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (seed == null) "新建规则" else "编辑规则",
                style = MiuixTheme.textStyles.body1,
                color = TextPrimary,
            )

            EditorField(
                label = "规则名称",
                value = name,
                onValueChange = { name = it },
                placeholder = "例如：招商银行入账",
            )

            EditorField(
                label = "目标应用包名",
                value = app,
                onValueChange = { app = it },
                placeholder = "留空表示全部应用，如 com.tencent.mm",
            )

            Text("规则类型", style = MiuixTheme.textStyles.footnote1, color = TextSecondary)
            ChoiceRow(
                options = listOf(
                    "短信" to RuleType.SMS,
                    "通知" to RuleType.NOTICE,
                    "识图" to RuleType.OCR,
                ),
                selected = type,
                onSelect = { type = it },
            )

            Text("收支方向", style = MiuixTheme.textStyles.footnote1, color = TextSecondary)
            ChoiceRow(
                options = listOf(
                    "不指定" to "",
                    "收入" to "income",
                    "支出" to "expense",
                    "其他" to "other",
                ),
                selected = direction,
                onSelect = { direction = it },
            )

            EditorField(
                label = "金额正则（每行一条，第 1 个分组即金额）",
                value = amountText,
                onValueChange = { amountText = it },
                placeholder = DEFAULT_AMOUNT_REGEX,
                minLines = 3,
            )

            EditorField(
                label = "支出词（逗号或换行分隔）",
                value = expenseText,
                onValueChange = { expenseText = it },
                placeholder = "支付，消费，扣款",
                minLines = 2,
            )

            EditorField(
                label = "收入词",
                value = incomeText,
                onValueChange = { incomeText = it },
                placeholder = "向您支付，您收到，入账",
                minLines = 2,
            )

            EditorField(
                label = "拦截词（命中即整条不匹配）",
                value = blockText,
                onValueChange = { blockText = it },
                placeholder = "验证码，广告，账单已出",
                minLines = 2,
            )

            EditorField(
                label = "分类（每行「分类名: 词1,词2」）",
                value = catsText,
                onValueChange = { catsText = it },
                placeholder = "其他: 服务费,项目\n退款: 退款,退回",
                minLines = 2,
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("命中后自动入账", style = MiuixTheme.textStyles.body2, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "关闭则只提示，不写入账单",
                        style = MiuixTheme.textStyles.footnote1,
                        color = TextSecondary,
                    )
                }
                Switch(
                    checked = autoRecord,
                    onCheckedChange = { autoRecord = it },
                    colors = SwitchDefaults.switchColors(
                        checkedThumbColor = LedgerTheme.surface,
                        uncheckedThumbColor = LedgerTheme.textSecondary,
                        checkedTrackColor = LedgerTheme.accent,
                        uncheckedTrackColor = LedgerTheme.divider,
                    ),
                )
            }

            RowDivider()

            Text("测试此规则", style = MiuixTheme.textStyles.body2, color = TextPrimary)
            EditorField(
                label = "粘贴一条短信或通知原文",
                value = testBody,
                onValueChange = { testBody = it; testResult = null },
                placeholder = "【招商银行】您账户…向您支付项目服务费19.40元",
                minLines = 3,
            )
            DialogButton(
                text = "测试匹配",
                primary = false,
                onClick = {
                    val patternJson = buildRulePatternJson(
                        type = type,
                        app = app.trim().ifBlank { null },
                        amount = lines(amountText),
                        expense = tokens(expenseText),
                        income = tokens(incomeText),
                        block = tokens(blockText),
                        categories = parseCategories(catsText),
                        direction = direction.ifBlank { null },
                    )
                    scope.launch {
                        val hit = withContext(Dispatchers.IO) {
                            RuleEngine.previewRule(patternJson, testBody)
                        }
                        testResult = if (hit == null) {
                            "未命中：拦截词、方向证据或金额至少有一项不满足"
                        } else {
                            val directionText = when (hit.type) {
                                "income" -> "收入"
                                "expense" -> "支出"
                                else -> "其他"
                            }
                            "命中：金额 ${String.format(java.util.Locale.ROOT, "%.2f", hit.amount)} 元 · " +
                                "方向 $directionText · 分类 ${hit.category ?: "未指定"}"
                        }
                    }
                },
            )
            testResult?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = AccentBlue)
            }

            error?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = LedgerTheme.danger)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton(text = "取消", primary = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                if (seed != null) {
                    DialogButton(
                        text = "删除",
                        primary = false,
                        danger = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onDelete(seed) },
                    )
                }
                DialogButton(
                    text = "保存",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val built = buildUserRule(
                            seed = seed,
                            name = name,
                            type = type,
                            app = app,
                            autoRecord = autoRecord,
                            amount = lines(amountText),
                            expense = tokens(expenseText),
                            income = tokens(incomeText),
                            block = tokens(blockText),
                            categories = parseCategories(catsText),
                            direction = direction.ifBlank { null },
                        )
                        val rule = built.first
                        if (rule == null) {
                            error = built.second
                        } else {
                            error = null
                            onSave(rule)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
) {
    val dimens = LocalDimens.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.radiusSmall))
                .background(LedgerTheme.inset)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .heightIn(min = (minLines * 20).dp),
            textStyle = MiuixTheme.textStyles.body2.copy(color = TextPrimary),
            cursorBrush = SolidColor(AccentBlue),
            singleLine = minLines == 1,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MiuixTheme.textStyles.body2, color = TextSecondary)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val dimens = LocalDimens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { pair ->
            val label = pair.first
            val value = pair.second
            val active = selected == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.radiusSmall))
                    .background(if (active) LedgerTheme.tint(AccentBlue) else LedgerTheme.inset)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                    ) { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (active) AccentBlue else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    val shape = RoundedCornerShape(dimens.radiusSmall)
    val bg = when {
        danger -> LedgerTheme.tint(LedgerTheme.danger)
        primary -> AccentBlue
        else -> LedgerTheme.inset
    }
    val fg = when {
        danger -> LedgerTheme.danger
        primary -> LedgerTheme.onAccent
        else -> TextPrimary
    }
    Box(
        modifier = modifier
            .height(LedgerDimens.minTouch)
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MiuixTheme.textStyles.button, color = fg)
    }
}

// ── 纯函数区：JSON 组装/解析、测试匹配 ─────────────────────────────

/** 编辑弹窗读到的初始字段。 */
private class RuleFields(
    val amount: List<String>,
    val expense: List<String>,
    val income: List<String>,
    val block: List<String>,
    val categories: Map<String, List<String>>,
    val direction: String?,
)

private fun emptyFields(): RuleFields =
    RuleFields(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), null)

private fun readRuleFields(seed: RuleEntity?): RuleFields {
    if (seed == null) return emptyFields()
    val json = runCatching { JSONObject(seed.patternJson) }.getOrNull() ?: return emptyFields()
    return RuleFields(
        amount = json.stringList("amount"),
        expense = json.stringList("expense"),
        income = json.stringList("income"),
        block = json.stringList("block"),
        categories = json.stringMap("cats"),
        direction = json.optString("direction").takeIf { it in setOf("income", "expense", "other") },
    )
}

private fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val value = array.optString(i, "")
        if (value.isNotBlank()) out.add(value)
    }
    return out
}

private fun JSONObject.stringMap(name: String): Map<String, List<String>> {
    val obj = optJSONObject(name) ?: return emptyMap()
    val out = LinkedHashMap<String, List<String>>()
    for (key in obj.keys()) {
        out[key] = obj.stringList(key)
    }
    return out
}

/** 与 RuleSeed 的播种 JSON 同构；direction 为空时不写该键。 */
internal fun buildRulePatternJson(
    type: String,
    app: String?,
    amount: List<String>,
    expense: List<String>,
    income: List<String>,
    block: List<String>,
    categories: Map<String, List<String>>,
    direction: String?,
): String {
    val json = JSONObject()
    json.put("v", RuleSeed.PATTERN_VERSION)
    json.put("type", type)
    json.put("app", app ?: JSONObject.NULL)
    json.put("amount", JSONArray(amount))
    json.put("expense", JSONArray(expense))
    json.put("income", JSONArray(income))
    json.put("block", JSONArray(block))
    json.put("necessaryFields", JSONArray(RuleSeed.DEFAULT_NECESSARY_FIELDS))
    val cats = JSONObject()
    categories.forEach { entry -> cats.put(entry.key, JSONArray(entry.value)) }
    json.put("cats", cats)
    if (direction != null) json.put("direction", direction)
    return json.toString()
}

/**
 * 表单转实体。返回 rule to null 表示通过，null to 理由 表示被拒。
 *
 * 正则在保存前逐条过 RuleSafety，把拒绝理由原样回显——否则用户只会看到「保存失败」。
 */
private fun buildUserRule(
    seed: RuleEntity?,
    name: String,
    type: String,
    app: String,
    autoRecord: Boolean,
    amount: List<String>,
    expense: List<String>,
    income: List<String>,
    block: List<String>,
    categories: Map<String, List<String>>,
    direction: String?,
): Pair<RuleEntity?, String?> {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null to "规则名称不能为空"
    if (amount.isEmpty()) return null to "至少写一条金额正则"
    for (raw in amount) {
        val reason = RuleSafety.unsafeReason(raw)
        if (reason != null) return null to "正则被拒：$reason"
        if (RuleSafety.compile(raw) == null) return null to "正则无法编译：$raw"
    }
    if (direction == null && expense.isEmpty() && income.isEmpty()) {
        return null to "请指定收支方向，或至少填写一个支出词/收入词"
    }
    val cleanApp = app.trim().ifBlank { null }
    val id = seed?.id ?: RuleEntity.makeId(type, cleanApp, trimmed)
    return RuleEntity(
        id = id,
        app = cleanApp,
        type = type,
        name = trimmed,
        creator = RuleEntity.CREATOR_USER,
        patternJson = buildRulePatternJson(type, cleanApp, amount, expense, income, block, categories, direction),
        enabled = seed?.enabled ?: true,
        autoRecord = autoRecord,
        updatedAt = System.currentTimeMillis(),
    ) to null
}

/** 按行切（正则里允许出现空格，所以金额正则只能按行分）。 */
private fun lines(raw: String): List<String> =
    raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** 按行/逗号/顿号切词。 */
private fun tokens(raw: String): List<String> =
    raw.split('\n', ',', '，', '、', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }

private fun parseCategories(raw: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>()
    lines(raw).forEach { line ->
        val index = line.indexOfFirst { it == ':' || it == '：' }
        if (index > 0) {
            val key = line.substring(0, index).trim()
            val words = line.substring(index + 1).split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
            if (key.isNotEmpty()) out[key] = words
        }
    }
    return out
}

private fun formatCategories(map: Map<String, List<String>>): String =
    map.entries.joinToString("\n") { entry -> "${entry.key}: ${entry.value.joinToString("，")}" }
