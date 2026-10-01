package com.lmreader.ui.settings.api

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.di.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private fun logStatus(status: String) = when(status) { "REQUESTING" -> "请求中"; "SUCCESS" -> "成功"; "FAILED" -> "失败"; "CANCELLED" -> "已取消"; else -> "已中断" }
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiLogScreen(container: AppContainer, onBack: () -> Unit) {
    val store = container.apiLogs
    val records by store.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<ApiLogRecord?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Scaffold(topBar = { TopAppBar(title = { Text("API 日志") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("api-logs"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("记录请求、回复和失败原因；图片保留附件大小信息。最近 500 条，最多 64 MB。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp)) }
            if(records.isEmpty()) item { Text("暂无 API 请求") }
            records.groupBy { it.info.context.mangaId }.forEach { (id, group) ->
                item("group:$id") { OutlinedButton(onClick = { expanded[id] = !(expanded[id] ?: true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${group.first().info.context.mangaName} · ${group.size} 条 ${if(expanded[id] != false) "⌃" else "⌄"}")
                } }
                if(expanded[id] != false) items(group, key = { it.id }) { r ->
                    Card(onClick = { scope.launch { detail = store.detail(r.id) } }, modifier = Modifier.fillMaxWidth().testTag("api-log:" + r.id)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${formatter.format(Date(r.started))} · ${logStatus(r.outcome.status)} · 尝试 ${r.info.attempt}")
                            Text(listOf(r.info.context.chapterName, r.info.context.pageName, r.info.context.stepName).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            Text("${r.info.profileName} · ${r.info.model}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    detail?.let { r -> ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f).padding(16.dp)) {
            Row { Text("请求记录 · ${logStatus(r.outcome.status)}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = { detail = null }) { Text("关闭") } }
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(listOf(r.info.context.mangaName, r.info.context.chapterName, r.info.context.pageName, r.info.context.stepName).filter { it.isNotBlank() }.joinToString(" / "))
                    Text("${r.info.method} ${r.info.url}\n${r.info.format} · ${r.info.model}\nHTTP ${r.outcome.httpCode ?: "—"} · ${r.ended?.let { (it - r.started).toString() + " ms" } ?: "请求中"}")
                    HorizontalDivider(); Text("输入", style = MaterialTheme.typography.titleSmall); Text(r.info.request.ifBlank { "无请求体" }, style = MaterialTheme.typography.bodySmall, localize = r.info.request.isBlank())
                    HorizontalDivider(); Text("输出", style = MaterialTheme.typography.titleSmall); Text(r.outcome.response.ifBlank { "暂无正文" }, style = MaterialTheme.typography.bodySmall, localize = r.outcome.response.isBlank())
                    if(r.outcome.thinking.isNotEmpty()) { HorizontalDivider(); Text("思考", style = MaterialTheme.typography.titleSmall); Text(r.outcome.thinking, style = MaterialTheme.typography.bodySmall, localize = false) }
                    if(r.outcome.error.isNotEmpty()) { HorizontalDivider(); Text("错误", style = MaterialTheme.typography.titleSmall); Text(r.outcome.error, localize = false) }
                }
            }
        }
    } }
}
