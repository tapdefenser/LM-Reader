package com.lmreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.SourceKind

/**
 * 图库的图源筛选右滑栏（用户要求）。
 *
 * 交互约定：
 * - 每条 = 复选框 + 路径名（优先用户起的名字）+ 副标题（系统路径 · 类型 · 已发现数量）；
 * - 左下角按钮在「全选 ↔ 清空」之间切换；
 * - 右下角「确认」才生效并关闭——选择期间改的是草稿，取消/返回不改变已生效筛选；
 * - 一条都不勾选按「显示全部」处理并在顶部说明，避免用户以为图库空了。
 *
 * 为什么不要求边缘手势：开发文档 8.2 对书架的右侧分类栏已经定下同一原则
 * （"不要求边缘手势"），图库的筛选栏保持一致，用顶栏按钮打开。
 */
@Composable
fun SourceFilterDrawer(
    sources: List<LibrarySource>,
    draftSelection: Set<String>,
    discoveredBySource: Map<String, Int>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allSelected = sources.isNotEmpty() && draftSelection.size == sources.size

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("筛选图源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (draftSelection.isEmpty()) {
                        "当前没有勾选任何图源，将显示全部 ${sources.size} 个图源的内容。"
                    } else {
                        "已勾选 ${draftSelection.size} / ${sources.size} 个图源。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (sources.isEmpty()) {
                    Text(
                        text = "还没有配置图源路径。请到「设置 → 图库与路径」添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                sources.forEach { source ->
                    SourceFilterRow(
                        source = source,
                        checked = source.sourceId in draftSelection,
                        discovered = discoveredBySource[source.sourceId],
                        onToggle = { onToggle(source.sourceId) },
                    )
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 抽屉在 edge-to-edge 下延伸到屏幕底部，必须显式让出导航栏高度，
                    // 否则「全选/取消/确认」会被手势条压住（路径页与书架同样处理）。
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 全选态下同一个按钮变成「清空」：用户在满选时想做的下一件事
                // 几乎总是取消全部，而不是再全选一次。
                TextButton(
                    onClick = { if (allSelected) onClearAll() else onSelectAll() },
                    enabled = sources.isNotEmpty(),
                ) {
                    Text(if (allSelected) "清空" else "全选")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onConfirm) { Text("确认") }
                }
            }
        }
    }
}

@Composable
private fun SourceFilterRow(
    source: LibrarySource,
    checked: Boolean,
    discovered: Int?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "图源 ${source.displayName ?: source.displayPath}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 路径名优先显示用户起的名字：SAF 的系统路径常常是
                // `primary:Tachiyomi/downloads` 这种，同名来源无法区分。
                text = source.displayName?.takeIf { it.isNotBlank() } ?: source.displayPath,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(source.displayPath)
                    append(" · ")
                    append(
                        when (source.kind) {
                            SourceKind.IMAGE_DIRECTORY -> "图片目录"
                            SourceKind.ARCHIVE_IMPORT -> "归档/PDF"
                        },
                    )
                    append(" · ")
                    append(
                        when (source.mode) {
                            LayoutMode.MULTI_CHAPTER -> "多章节"
                            LayoutMode.SINGLE_CHAPTER -> "单章节"
                        },
                    )
                    if (discovered != null) {
                        append(" · 已发现 ")
                        append(discovered)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
