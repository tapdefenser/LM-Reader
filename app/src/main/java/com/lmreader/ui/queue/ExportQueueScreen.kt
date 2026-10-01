package com.lmreader.ui.queue

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportQueueScreen(container: AppContainer, onBack: () -> Unit, onSettings: () -> Unit) {
    val queue = container.exportQueue
    val tasks by queue.tasks.collectAsStateWithLifecycle()
    val paused by queue.paused.collectAsStateWithLifecycle()
    val serviceFailure by container.taskService.failure.collectAsStateWithLifecycle()
    val storageFailure by queue.storageFailure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val groups = tasks.groupBy { it.mangaId }
    Scaffold(topBar = { TopAppBar(title = { Text("导出队列") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "队列菜单") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (paused) "全部继续" else "全部暂停") }, onClick = {
                    menu = false; if (paused) queue.resumeAll() else queue.pauseAll()
                })
                DropdownMenuItem(text = { Text("导出设置") }, onClick = { menu = false; onSettings() })
                DropdownMenuItem(text = { Text("清除已取消") }, onClick = { menu = false; queue.clearFinished() })
            }
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            serviceFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            storageFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            Text("暂停和中断保留导出快照；恢复授权后可重试。未清理的临时文件会保留在任务记录中。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            val pendingCount = tasks.count { it.state == "PENDING" || it.state == "RUNNING" }
            Text(if (paused) "队列已暂停" else "按顺序导出 · $pendingCount 项待处理",
                modifier = Modifier.padding(16.dp))
            HorizontalDivider()
            if (tasks.isEmpty()) {
                Text("队列为空。请到漫画详情页选择全部或部分章节导出。", modifier = Modifier.padding(24.dp))
            } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (mangaId, rows) ->
                    item(key = "manga:$mangaId") {
                        var groupMenu by remember { mutableStateOf(false) }
                        ListItem(headlineContent = { Text(rows.first().mangaTitle, localize = false) },
                            supportingContent = { Text("队列中 ${rows.size} 章") },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = {
                                        collapsed = if (mangaId in collapsed) collapsed - mangaId else collapsed + mangaId
                                    }) { Text(if (mangaId in collapsed) "展开" else "收起") }
                                    Box {
                                        IconButton(onClick = { groupMenu = true }) { Icon(Icons.Filled.MoreVert, "漫画操作") }
                                        DropdownMenu(groupMenu, { groupMenu = false }) {
                                            DropdownMenuItem(text = { Text("暂停") }, onClick = { groupMenu = false; queue.pauseManga(mangaId) })
                                            DropdownMenuItem(text = { Text("继续") }, onClick = { groupMenu = false; queue.resumeManga(mangaId) })
                                            DropdownMenuItem(text = { Text("上移") }, onClick = { groupMenu = false; queue.moveManga(mangaId, -1) })
                                            DropdownMenuItem(text = { Text("下移") }, onClick = { groupMenu = false; queue.moveManga(mangaId, 1) })
                                            DropdownMenuItem(text = { Text("取消") }, onClick = { groupMenu = false; queue.cancelManga(mangaId) })
                                        }
                                    }
                                }
                            })
                    }
                    if (mangaId !in collapsed) items(rows, key = { it.id }) { task ->
                        var actions by remember { mutableStateOf(false) }
                        ElevatedCard(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(task.chapterTitle, style = MaterialTheme.typography.titleSmall, localize = false)
                                    val total = if (task.totalPages > 0) task.totalPages.toString() else "?"
                                    Text("${exportStateLabel(task.state)} · ${task.completedPages}/$total 页 · ${task.format.label}",
                                        style = MaterialTheme.typography.bodySmall)
                                    task.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                                Box {
                                    IconButton(onClick = { actions = true }) { Icon(Icons.Filled.MoreVert, "章节操作") }
                                    DropdownMenu(actions, { actions = false }) {
                                        when (task.state) {
                                            "PENDING", "RUNNING" -> DropdownMenuItem(text = { Text("暂停") }, onClick = { actions = false; queue.pause(task.id) })
                                            "PAUSED" -> DropdownMenuItem(text = { Text("继续") }, onClick = { actions = false; queue.resume(task.id) })
                                            "FAILED", "INTERRUPTED" -> DropdownMenuItem(text = { Text("重试") }, onClick = { actions = false; queue.retry(task.id) })
                                        }
                                        if (task.state == "DONE" && task.outputUri != null) DropdownMenuItem(text = { Text("查看输出") }, onClick = {
                                            actions = false
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(Uri.parse(task.outputUri), if (task.format == ExportFormat.CBZ) "application/zip" else "vnd.android.document/directory")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            runCatching { context.startActivity(intent) }
                                        })
                                        if (task.state !in listOf("RUNNING", "DONE")) {
                                            DropdownMenuItem(text = { Text("上移") }, onClick = { actions = false; queue.move(task.id, -1) })
                                            DropdownMenuItem(text = { Text("下移") }, onClick = { actions = false; queue.move(task.id, 1) })
                                        }
                                        if (task.state != "DONE" && task.state != "CANCELLED") DropdownMenuItem(
                                            text = { Text("取消") }, onClick = { actions = false; queue.cancel(task.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportStateLabel(state: String) = when (state) {
    "PENDING" -> "等待中"; "RUNNING" -> "导出中"; "PAUSED" -> "已暂停"
    "DONE" -> "已完成"; "FAILED" -> "失败"; "INTERRUPTED" -> "中断"; "CANCELLED" -> "已取消"
    else -> state
}
