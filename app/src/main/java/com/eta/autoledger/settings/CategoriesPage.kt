package com.eta.autoledger.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.eta.autoledger.data.CategoryStore
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.ui.LedgerTheme
import com.eta.autoledger.ui.LocalDimens
import com.eta.autoledger.ui.components.AppDialog
import com.eta.autoledger.ui.components.ThemeIconBadge
import com.eta.autoledger.ui.theme.LedgerDimens
import com.eta.autoledger.ui.window.LocalSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class ManagedCategory(
    val name: String,
    val color: Color,
    val emoji: String,
)

private data class SubCategoryTarget(
    val sessionId: Int,
    val typeIndex: Int,
    val category: String,
    val name: String?,
)

private data class SubCategoryDeleteRequest(
    val sessionId: Int,
    val typeIndex: Int,
    val category: String,
    val name: String,
    val billCount: Int,
    val targets: List<String>,
)

@Composable
internal fun LedgerCategoriesPage() {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    val expense = remember { CategoryStore.expense.map(::managedCategory) }
    val income = remember { CategoryStore.income.map(::managedCategory) }
    val other = remember { CategoryStore.other.map(::managedCategory) }

    var selectedCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var lastSelectedCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var detailRevision by remember { mutableIntStateOf(0) }

    var editorTarget by remember { mutableStateOf<SubCategoryTarget?>(null) }
    var lastEditorTarget by remember { mutableStateOf<SubCategoryTarget?>(null) }
    var editorSession by remember { mutableIntStateOf(0) }
    var editorBusy by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }

    var deleteRequest by remember { mutableStateOf<SubCategoryDeleteRequest?>(null) }
    var lastDeleteRequest by remember { mutableStateOf<SubCategoryDeleteRequest?>(null) }
    var deleteSession by remember { mutableIntStateOf(0) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory != null) lastSelectedCategory = selectedCategory
    }
    LaunchedEffect(editorTarget) {
        if (editorTarget != null) lastEditorTarget = editorTarget
    }
    LaunchedEffect(deleteRequest) {
        if (deleteRequest != null) lastDeleteRequest = deleteRequest
    }

    fun closeEditor() {
        editorTarget = null
        editorBusy = false
        editorError = null
    }

    fun closeDeleteRequest() {
        deleteRequest = null
        deleteBusy = false
        deleteError = null
    }

    SettingsPageColumn {
        HintText("一级分类已锁定。点击分类可管理二级子分类。")
        CategorySection("支出管理", expense, onManage = { selectedCategory = 0 to it })
        CategorySection("收入管理", income, onManage = { selectedCategory = 1 to it })
        CategorySection("其他管理", other, onManage = { selectedCategory = 2 to it })
    }

    lastSelectedCategory?.let { (typeIndex, category) ->
        CategoryDetailSheet(
            show = selectedCategory != null,
            typeIndex = typeIndex,
            category = category,
            revision = detailRevision,
            onDismiss = { selectedCategory = null },
            onAdd = {
                editorError = null
                editorSession++
                editorTarget = SubCategoryTarget(editorSession, typeIndex, category, null)
            },
            onEdit = { name ->
                editorError = null
                editorSession++
                editorTarget = SubCategoryTarget(editorSession, typeIndex, category, name)
            },
        )
    }

    lastEditorTarget?.let { target ->
        SubCategoryEditorDialog(
            show = editorTarget != null,
            target = target,
            busy = editorBusy,
            error = editorError,
            onClearError = { editorError = null },
            onDismiss = { if (!editorBusy) closeEditor() },
            onSave = { rawName ->
                val value = CategoryStore.normalizeSubCategoryName(rawName)
                when {
                    value == null -> editorError = "请输入分类名称"
                    target.name == value -> closeEditor()
                    else -> {
                        val validation = CategoryStore.subCategoryValidationError(
                            context,
                            target.typeIndex,
                            target.category,
                            value,
                        )
                        if (validation != null) {
                            editorError = validation
                        } else if (target.name == null) {
                            if (CategoryStore.addSubCategory(context, target.typeIndex, target.category, value)) {
                                detailRevision++
                                closeEditor()
                                scope.launch { snackbar.show("已添加子分类：$value") }
                            } else {
                                editorError = "添加失败，请稍后重试"
                            }
                        } else {
                            editorBusy = true
                            val renamed = CategoryStore.renameSubCategory(
                                context,
                                target.typeIndex,
                                target.category,
                                target.name,
                                value,
                            )
                            if (!renamed) {
                                editorBusy = false
                                editorError = "重命名失败，请稍后重试"
                            } else {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            LedgerRepository.moveSubCategoryBills(
                                                context = context,
                                                type = typeValue(target.typeIndex),
                                                category = target.category,
                                                source = target.name,
                                                target = value,
                                            )
                                        }
                                    }
                                    if (result.isSuccess) {
                                        detailRevision++
                                        closeEditor()
                                        snackbar.show("已重命名，并同步历史账单")
                                    } else {
                                        CategoryStore.renameSubCategory(
                                            context,
                                            target.typeIndex,
                                            target.category,
                                            value,
                                            target.name,
                                        )
                                        editorBusy = false
                                        editorError = "重命名失败，请稍后重试"
                                    }
                                }
                            }
                        }
                    }
                }
            },
            onDelete = target.name?.let { oldName ->
                {
                    if (!editorBusy) {
                        editorBusy = true
                        scope.launch {
                            val count = withContext(Dispatchers.IO) {
                                runCatching {
                                    LedgerRepository.countSubCategoryBills(
                                        context,
                                        typeValue(target.typeIndex),
                                        target.category,
                                        oldName,
                                    )
                                }
                            }
                            if (count.isFailure) {
                                editorBusy = false
                                editorError = "无法查询分类使用情况"
                                return@launch
                            }
                            val billCount = count.getOrDefault(0)
                            if (billCount == 0) {
                                val deleted = CategoryStore.deleteSubCategory(
                                    context,
                                    target.typeIndex,
                                    target.category,
                                    oldName,
                                )
                                val cleared = if (deleted) {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            LedgerRepository.moveSubCategoryBills(
                                                context = context,
                                                type = typeValue(target.typeIndex),
                                                category = target.category,
                                                source = oldName,
                                                target = null,
                                            )
                                        }
                                    }.isSuccess
                                } else {
                                    false
                                }
                                if (deleted && cleared) {
                                    detailRevision++
                                    closeEditor()
                                    snackbar.show("已删除子分类：$oldName")
                                } else {
                                    if (deleted) {
                                        CategoryStore.addSubCategory(
                                            context,
                                            target.typeIndex,
                                            target.category,
                                            oldName,
                                        )
                                    }
                                    editorBusy = false
                                    editorError = "删除失败，请稍后重试"
                                }
                            } else {
                                val targets = CategoryStore.subCategories(
                                    context,
                                    target.typeIndex,
                                    target.category,
                                )
                                    .filterNot { it == oldName }
                                editorBusy = false
                                closeEditor()
                                deleteError = null
                                deleteSession++
                                deleteRequest = SubCategoryDeleteRequest(
                                    sessionId = deleteSession,
                                    typeIndex = target.typeIndex,
                                    category = target.category,
                                    name = oldName,
                                    billCount = billCount,
                                    targets = targets,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    lastDeleteRequest?.let { request ->
        SubCategoryDeleteDialog(
            show = deleteRequest != null,
            request = request,
            busy = deleteBusy,
            error = deleteError,
            onDismiss = { if (!deleteBusy) closeDeleteRequest() },
            onConfirm = { targetName ->
                if (!deleteBusy) {
                    deleteBusy = true
                    deleteError = null
                    scope.launch {
                        val deleted = CategoryStore.deleteSubCategory(
                            context,
                            request.typeIndex,
                            request.category,
                            request.name,
                        )
                        val moved = if (deleted) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    LedgerRepository.moveSubCategoryBills(
                                        context = context,
                                        type = typeValue(request.typeIndex),
                                        category = request.category,
                                        source = request.name,
                                        target = targetName,
                                    )
                                }
                            }
                        } else {
                            Result.failure(IllegalStateException("分类树写入失败"))
                        }
                        if (deleted && moved.isSuccess) {
                            detailRevision++
                            closeDeleteRequest()
                            snackbar.show("已迁移 ${request.billCount} 笔账单并删除子分类")
                        } else {
                            if (deleted) {
                                CategoryStore.addSubCategory(
                                    context,
                                    request.typeIndex,
                                    request.category,
                                    request.name,
                                )
                            }
                            deleteBusy = false
                            deleteError = "迁移失败，请稍后重试"
                        }
                    }
                }
            },
        )
    }
}

private fun managedCategory(name: String) = ManagedCategory(
    name = name,
    color = CategoryStore.color(name),
    emoji = CategoryStore.emoji(name),
)

private fun typeValue(typeIndex: Int): String = when (typeIndex) {
    1 -> "income"
    2 -> "other"
    else -> "expense"
}

@Composable
private fun CategorySection(
    title: String,
    items: List<ManagedCategory>,
    onManage: (String) -> Unit,
) {
    Text(
        text = title,
        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
        color = LedgerTheme.text,
        modifier = Modifier.padding(start = LedgerDimens.xs, top = LedgerDimens.xs),
    )
    SettingsCard {
        Column(
            modifier = Modifier.padding(
                horizontal = LocalDimens.current.gap,
                vertical = LocalDimens.current.gap,
            ),
        ) {
            items.chunked(5).forEachIndexed { rowIndex, rowItems ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { item ->
                        PrimaryCategoryCell(
                            item = item,
                            onClick = { onManage(item.name) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                if (rowIndex != items.chunked(5).lastIndex) {
                    Spacer(Modifier.height(LocalDimens.current.gap))
                }
            }
        }
    }
}

@Composable
private fun PrimaryCategoryCell(
    item: ManagedCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(dimens.radiusSmall))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = dimens.gapSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThemeIconBadge(size = LedgerDimens.minTouch, borderColor = item.color) {
            Text(text = item.emoji, style = MiuixTheme.textStyles.title4)
        }
        Spacer(Modifier.height(LedgerDimens.xs))
        Text(
            text = item.name,
            style = MiuixTheme.textStyles.footnote2,
            color = LedgerTheme.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryDetailSheet(
    show: Boolean,
    typeIndex: Int,
    category: String,
    revision: Int,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    val dimens = LocalDimens.current
    val color = remember(category) { CategoryStore.color(category) }
    val emoji = remember(category) { CategoryStore.emoji(category) }
    val subCategories = remember(context, typeIndex, category, revision) {
        CategoryStore.subCategories(context, typeIndex, category)
    }
    val shape = RoundedCornerShape(topStart = dimens.radiusSheet, topEnd = dimens.radiusSheet)

    AppDialog(
        show = show,
        align = Alignment.BottomCenter,
        widthFraction = 1f,
        outsideMargin = DpSize(0.dp, 0.dp),
        insideMargin = DpSize(dimens.cardPadding, dimens.cardPadding),
        panelColor = LedgerTheme.surface,
        shape = shape,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ThemeIconBadge(size = 56.dp, borderColor = color) {
                        Text(text = emoji, style = MiuixTheme.textStyles.title2)
                    }
                    Spacer(Modifier.height(dimens.gapSmall))
                    Text(text = category, style = MiuixTheme.textStyles.title2, color = LedgerTheme.text)
                    Spacer(Modifier.height(dimens.gapSmall))
                    Text(
                        text = "温馨提示 | 默认一级分类无法编辑与删除",
                        style = MiuixTheme.textStyles.footnote1,
                        color = LedgerTheme.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .heightIn(min = LedgerDimens.minTouch)
                        .clip(RoundedCornerShape(dimens.radiusSmall))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = onDismiss,
                        )
                        .padding(horizontal = dimens.gap),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "关闭",
                        style = MiuixTheme.textStyles.footnote1,
                        color = LedgerTheme.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(dimens.cardPadding))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LedgerTheme.divider),
            )
            Spacer(Modifier.height(dimens.cardPadding))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "子分类",
                    style = MiuixTheme.textStyles.title4,
                    color = LedgerTheme.text,
                )
                Spacer(Modifier.width(dimens.gapSmall))
                Text(
                    text = "点击管理，可重命名或删除",
                    style = MiuixTheme.textStyles.footnote1,
                    color = LedgerTheme.textSecondary,
                )
            }
            Spacer(Modifier.height(dimens.cardPadding))

            if (subCategories.isEmpty()) {
                Text(
                    text = "暂无二级分类，点击 + 添加。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = LedgerTheme.textSecondary,
                )
                Spacer(Modifier.height(dimens.gap))
            }

            SubCategoryGrid(
                category = category,
                color = color,
                emoji = emoji,
                names = subCategories,
                onAdd = onAdd,
                onEdit = onEdit,
            )
        }
    }
}

@Composable
private fun SubCategoryGrid(
    category: String,
    color: Color,
    emoji: String,
    names: List<String>,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val dimens = LocalDimens.current
    val cells: List<String?> = names + listOf(null)
    Column(modifier = Modifier.fillMaxWidth()) {
        cells.chunked(5).forEachIndexed { rowIndex, rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { name ->
                    SubCategoryCell(
                        category = category,
                        name = name,
                        color = color,
                        emoji = emoji,
                        onClick = { if (name == null) onAdd() else onEdit(name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            if (rowIndex != cells.chunked(5).lastIndex) {
                Spacer(Modifier.height(dimens.gap))
            }
        }
    }
}

@Composable
private fun SubCategoryCell(
    category: String,
    name: String?,
    color: Color,
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val isAdd = name == null
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(dimens.radiusSmall))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = dimens.gapSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThemeIconBadge(
            size = LedgerDimens.minTouch,
            borderColor = if (isAdd) LedgerTheme.accent else color,
        ) {
            if (isAdd) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "添加$category 子分类",
                    tint = LedgerTheme.accent,
                    modifier = Modifier.size(LedgerDimens.lg),
                )
            } else {
                Text(text = emoji, style = MiuixTheme.textStyles.title4)
            }
        }
        Spacer(Modifier.height(LedgerDimens.xs))
        Text(
            text = name ?: "添加",
            style = MiuixTheme.textStyles.footnote2,
            color = LedgerTheme.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SubCategoryEditorDialog(
    show: Boolean,
    target: SubCategoryTarget,
    busy: Boolean,
    error: String?,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val dimens = LocalDimens.current
    var name by remember(target.sessionId) { mutableStateOf(target.name.orEmpty()) }
    var localError by remember(target.sessionId) { mutableStateOf<String?>(null) }
    val shownError = localError ?: error
    val shape = RoundedCornerShape(dimens.radiusSmall)

    AppDialog(
        show = show,
        dismissEnabled = !busy,
        title = if (target.name == null) "添加子分类" else "管理子分类",
        summary = "所属一级分类：${target.category}",
        outsideMargin = DpSize(dimens.pagePadding, dimens.cardPadding),
        panelColor = LedgerTheme.surface,
        onDismissRequest = onDismiss,
    ) {
        BasicTextField(
            value = name,
            onValueChange = {
                name = it
                localError = null
                onClearError()
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LedgerTheme.inset)
                .border(1.dp, LedgerTheme.borderSoft, shape)
                .heightIn(min = LedgerDimens.minTouch)
                .padding(horizontal = dimens.gap, vertical = dimens.gap),
            singleLine = true,
            enabled = !busy,
            textStyle = MiuixTheme.textStyles.body2.copy(color = LedgerTheme.text),
            cursorBrush = SolidColor(LedgerTheme.accent),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) {
                        Text(
                            text = "输入子分类名称",
                            style = MiuixTheme.textStyles.body2,
                            color = LedgerTheme.textTertiary,
                        )
                    }
                    inner()
                }
            },
        )
        if (shownError != null) {
            Spacer(Modifier.height(dimens.gapSmall))
            Text(
                text = shownError,
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.danger,
            )
        }
        Spacer(Modifier.height(dimens.cardPadding))
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
            if (onDelete != null) {
                DialogAction(
                    label = "删除",
                    color = LedgerTheme.danger,
                    background = LedgerTheme.inset,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = onDelete,
                )
            }
            DialogAction(
                label = "取消",
                color = LedgerTheme.textSecondary,
                background = LedgerTheme.inset,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            DialogAction(
                label = if (busy) "处理中…" else "保存",
                color = LedgerTheme.onAccent,
                background = LedgerTheme.accent,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (name.isBlank()) {
                        localError = "请输入分类名称"
                    } else {
                        onSave(name)
                    }
                },
            )
        }
    }
}

@Composable
private fun SubCategoryDeleteDialog(
    show: Boolean,
    request: SubCategoryDeleteRequest,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val dimens = LocalDimens.current
    var destination by remember(request.sessionId) {
        mutableStateOf<String?>(request.targets.firstOrNull())
    }
    val shape = RoundedCornerShape(dimens.radiusSmall)

    AppDialog(
        show = show,
        dismissEnabled = !busy,
        title = "迁移并删除",
        summary = "“${request.name}”下有 ${request.billCount} 笔账单引用，是否转移到其他分类？",
        outsideMargin = DpSize(dimens.pagePadding, dimens.cardPadding),
        panelColor = LedgerTheme.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(dimens.gapSmall),
        ) {
            listOf<String?>(null).plus(request.targets).forEach { target ->
                val selected = destination == target
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LedgerDimens.minTouch)
                        .clip(shape)
                        .background(if (selected) LedgerTheme.inset else LedgerTheme.surface)
                        .border(
                            1.dp,
                            if (selected) LedgerTheme.accent else LedgerTheme.borderSoft,
                            shape,
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            enabled = !busy,
                            onClick = { destination = target },
                        )
                        .padding(horizontal = dimens.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = target ?: "未分类",
                        style = MiuixTheme.textStyles.body2,
                        color = if (selected) LedgerTheme.accent else LedgerTheme.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text(
                            text = "已选",
                            style = MiuixTheme.textStyles.footnote2,
                            color = LedgerTheme.accent,
                        )
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(dimens.gapSmall))
            Text(
                text = error,
                style = MiuixTheme.textStyles.footnote1,
                color = LedgerTheme.danger,
            )
        }
        Spacer(Modifier.height(dimens.cardPadding))
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
            DialogAction(
                label = "取消",
                color = LedgerTheme.textSecondary,
                background = LedgerTheme.inset,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            DialogAction(
                label = if (busy) "迁移中…" else "迁移并删除",
                color = LedgerTheme.onAccent,
                background = LedgerTheme.accent,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { onConfirm(destination) },
            )
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    color: Color,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Box(
        modifier = modifier
            .heightIn(min = LedgerDimens.minTouch)
            .clip(RoundedCornerShape(dimens.radiusSmall))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
