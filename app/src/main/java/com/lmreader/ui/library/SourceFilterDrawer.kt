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

/**
 * 图源在界面上的名称。
 *
 * 规则只有一条：**用户起的名字优先，否则用路径**。这样既不会出现空标题，
 * 也不会在标题里重复显示同一份信息（路径重复出现在小标题是上一版的冗余）。
 */
private fun LibrarySource.displayLabel(): String =
    displayName?.takeIf { it.isNotBlank() } ?: displayPath

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
            // 无障碍朗读用"名称 + 数量"，与视觉层级一致（标题是名称，不是路径）。
            .semantics { contentDescription = "图源 ${source.displayLabel()}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 大标题就是图源名称；用户没起名字时用路径，绝不出现空标题。
                // （SAF 的系统路径常常是 `primary:Tachiyomi/downloads` 这种，
                // 所以给用户留了自定义名称的入口。）
                text = source.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 小标题**不再重复路径**（标题里已经有了），第一项改为条目数量——
                // 这是用户在筛选时真正需要判断的信息：这个图源里到底有多少东西。
                // 来源种类不再出现在这里：一次扫描同时识别图片与归档，种类只是身份标签。
                text = buildString {
                    append("共 ")
                    append(discovered ?: 0)
                    append(" 项")
                    append(" · ")
                    append(
                        when (source.mode) {
                            LayoutMode.MULTI_CHAPTER -> "多章节"
                            LayoutMode.SINGLE_CHAPTER -> "单章节"
                        },
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
