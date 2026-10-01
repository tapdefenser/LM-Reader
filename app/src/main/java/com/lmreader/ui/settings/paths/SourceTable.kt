package com.lmreader.ui.settings.paths

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.i18n.localizedContentDescription
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.storage.scan.ScanState
import com.lmreader.ui.common.description
import com.lmreader.ui.common.displayName
import com.lmreader.ui.common.shortName

/**
 * 一张路径表（开发文档 4.1，按用户要求修订交互）。
 *
 * 四列语义固定为路径 / 子目录 / 类型 / 删除；表头展示顺序按可读性排列为
 * 「路径、子目录、类型、删除」。原始需求列举顺序是"第二项删除、第三项子目录"，
 * 列数与语义一致，只是排列不同（框架实现说明 7.2 记录了该差异）。
 *
 * 所有列都是**改动即生效**：复选框与类型开关直接写库，没有行内保存/取消。
 * 点路径列打开编辑弹窗（改名或重新选择目录）。拖动排序同样即时持久化。
 */
@Composable
fun SourceTable(
    table: SourceTableState,
    scanStates: Map<String, ScanState>,
    onAdd: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onToggleRecursive: (String, Boolean) -> Unit,
    onChangeMode: (String, LayoutMode) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onShowDiagnostics: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = table.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${table.savedCount} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            // 每表一个 +：打开系统目录选择器，选完立即加入列表（用户要求）。
            FilledTonalIconButton(
                onClick = onAdd,
                modifier = Modifier.localizedContentDescription("增加${table.title}路径"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }

        SourceTableHeader(table.headers)

        if (table.rows.isEmpty()) {
            Text(
                text = table.emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }

        table.rows.forEachIndexed { index, row ->
            val dragging = dragIndex == index
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .background(
                        if (dragging) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
            ) {
                SourceTableRow(
                    row = row,
                    scanState = row.source?.sourceId?.let { scanStates[it] },
                    canMoveUp = index > 0,
                    canMoveDown = index < table.rows.lastIndex,
                    onOpenEditor = { row.source?.let { onOpenEditor(it.sourceId) } },
                    onToggleRecursive = { value ->
                        row.source?.let { onToggleRecursive(it.sourceId, value) }
                    },
                    onChangeMode = { mode -> row.source?.let { onChangeMode(it.sourceId, mode) } },
                    onDelete = { row.source?.let { onDelete(it.sourceId) } },
                    onShowDiagnostics = { row.source?.let { onShowDiagnostics(it.sourceId) } },
                    onMoveUp = { onMove(index, index - 1) },
                    onMoveDown = { onMove(index, index + 1) },
                    dragHandleModifier = Modifier.pointerInput(row.key, table.rows.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                // 松手即持久化（开发文档 4.1）：用行高把位移换算成目标下标。
                                val target = (index + (dragOffset / rowHeight).toInt())
                                    .coerceIn(0, table.rows.lastIndex)
                                if (target != index) onMove(index, target)
                                dragIndex = -1
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                dragIndex = -1
                                dragOffset = 0f
                            },
                        )
                    },
                )

                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SourceTableHeader(headers: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(DRAG_HANDLE_WIDTH + MOVE_COLUMN_WIDTH))
        Text(
            text = headers[0],
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = headers[1],
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(CHECKBOX_COLUMN_WIDTH),
        )
        Text(
            text = headers[2],
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(MODE_COLUMN_WIDTH),
        )
        Text(
            text = headers[3],
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(DELETE_COLUMN_WIDTH),
        )
    }
}

@Composable
private fun SourceTableRow(
    row: SourceRow,
    scanState: ScanState?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpenEditor: () -> Unit,
    onToggleRecursive: (Boolean) -> Unit,
    onChangeMode: (LayoutMode) -> Unit,
    onDelete: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    dragHandleModifier: Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖动手柄属于路径列（开发文档 4.1）。上移/下移放在同一行：
        // 长按拖动对无障碍用户不可用，必须有等价操作。
        Row(
            modifier = Modifier.width(DRAG_HANDLE_WIDTH + MOVE_COLUMN_WIDTH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = dragHandleModifier
                    .size(DRAG_HANDLE_WIDTH)
                    .localizedContentDescription("拖动排序"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "上移",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "下移",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenEditor)
                .padding(end = 4.dp),
        ) {
            Text(
                // 有自定义名称时主标题是名称，系统路径作为副标题；没有名称时只显示路径。
                text = row.title,
                localize = false,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val rawPath = row.source?.displayPath.orEmpty()
            val subtitle = buildString {
                if (rawPath.isNotBlank() && rawPath != row.title) append(rawPath)
                row.source?.providerLabel?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
                scanState?.let { state ->
                    if (isNotEmpty()) append(" · ")
                    append(state.phaseLabel)
                    // 访问目录数直接显示：用户和排障都需要知道"扫到多深"，
                    // 而不只是一个结果数字（真机上曾出现"0 部漫画但你不知道它到底走没走进去"）。
                    if (state.visited > 0) append("，访问 ${state.visited} 个目录")
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.permission == SourcePermissionState.LOST) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "授权失效，点击此行重新选择目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (row.source?.lastScanStatus == ScanRunStatus.FAILED) {
                // 只给一行提示，"查看原因"进入诊断弹窗：失败原因可能很长（异常类型 +
                // 原始 message），塞进 64dp 高的行里只会被截断，反而看不出是什么问题。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "扫描失败（${row.source.lastScanError?.take(24) ?: "未知原因"}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "查看原因",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onShowDiagnostics)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }

        Box(modifier = Modifier.width(CHECKBOX_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = row.recursive,
                onCheckedChange = onToggleRecursive,
                modifier = Modifier.localizedContentDescription("迭代搜索该路径下的漫画"),
            )
        }

        Box(modifier = Modifier.width(MODE_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            LayoutModeDropdown(
                selected = row.mode,
                onSelect = onChangeMode,
                enabled = row.source != null,
            )
        }

        Box(modifier = Modifier.width(DELETE_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.localizedContentDescription("删除该路径"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private val ROW_HEIGHT = 64.dp
private val DRAG_HANDLE_WIDTH = 36.dp
private val MOVE_COLUMN_WIDTH = 26.dp
private val CHECKBOX_COLUMN_WIDTH = 52.dp
private val MODE_COLUMN_WIDTH = 112.dp
private val DELETE_COLUMN_WIDTH = 52.dp

/**
 * 解释方式下拉（用户要求：从分段切换改成下拉，将来要加"混合"这类选项）。
 *
 * 为什么用下拉而不是分段：分段切换的宽度随选项数线性增长，第三个选项就会把这四列的
 * 行挤坏；下拉的按钮宽度固定，选项放在菜单里还能带一句说明。
 *
 * 选项列表直接遍历 [LayoutMode.entries]、文案取自 [displayName]/[description]，
 * 因此新增枚举值时这里一行都不用改。
 */
@Composable
private fun LayoutModeDropdown(
    selected: LayoutMode,
    onSelect: (LayoutMode) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            modifier = Modifier.localizedContentDescription("解释方式：${selected.displayName()}，点击切换"),
        ) {
            Text(
                text = selected.shortName(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LayoutMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text = mode.displayName(), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = mode.description(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    // 当前值给一个勾：下拉里必须能一眼看出"现在选的是哪个"。
                    leadingIcon = if (mode == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        if (mode != selected) onSelect(mode)
                    },
                )
            }
        }
    }
}
