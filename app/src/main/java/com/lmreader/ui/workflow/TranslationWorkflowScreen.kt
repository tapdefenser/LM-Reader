package com.lmreader.ui.workflow

import com.lmreader.ui.i18n.showLocalizedSnackbar
import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationWorkflowScreen(container: AppContainer, onBack: () -> Unit) {
    val store = container.translationWorkflows
    val workflows by store.workflows.collectAsStateWithLifecycle()
    val profiles by container.apiProfiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<TranslationWorkflow?>(null) }
    var inspecting by remember { mutableStateOf<TranslationWorkflow?>(null) }
    var deleting by remember { mutableStateOf<TranslationWorkflow?>(null) }
    var references by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<TranslationWorkflow?>(null) }
    var bindings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val snackbar = remember { SnackbarHostState() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val document = exported; exported = null
        if(uri != null && document != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(document.toByteArray(Charsets.UTF_8)) }
            } }.onSuccess { snackbar.showLocalizedSnackbar(context, "工作流已导出") }.onFailure { error = it.message ?: "导出失败" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val bytes = ByteArrayOutputStream()
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val buffer = ByteArray(8192)
                    while(true) {
                        val count = input.read(buffer); if(count < 0) break
                        require(bytes.size() + count <= 1_000_000) { "工作流文件超过 1 MB" }
                        bytes.write(buffer, 0, count)
                    }
                }
                WorkflowFileCodec.decode(bytes.toString("UTF-8"))
            } }.onSuccess { value ->
                val ids = value.program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
                    .map { (it.inputs["profile"] as? WorkflowExpression.Text)?.value.orEmpty() }.distinct()
                if(ids.isEmpty()) {
                    val saved = store.import(value); snackbar.showLocalizedSnackbar(context, "已导入 " + saved.name)
                } else {
                    bindings = ids.filter { id -> profiles.any { it.id == id && it.kind == ApiProfileKind.LLM } }.associateWith { it }
                    imported = value
                }
            }.onFailure { error = it.message ?: "导入失败" }
        }
    }
    val editor = editing ?: inspecting
    if(editor != null) {
        WorkflowEditorScreen(container, editor, readOnly = inspecting != null || !editor.editable, onDismiss = { editing = null; inspecting = null }, onSave = { changed ->
            scope.launch { runCatching { store.update(changed) }.onSuccess { editing = null }.onFailure { error = it.message ?: "保存失败" } }
        }, onBindApi = { id -> scope.launch {
            runCatching { store.bindApi(editor.id, id) }.onSuccess { inspecting = it }.onFailure { error = it.message ?: "绑定失败" }
        } })
    } else {
        LaunchedEffect(deleting?.id) { references = deleting?.let { runCatching { container.database.mangaDao().workflowReferences(it.id) }.getOrDefault(emptyList()) } }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text("翻译工作流") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = { IconButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Default.FileUpload, "导入工作流") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { scope.launch { runCatching { store.create() }.onSuccess { editing = it }.onFailure { error = it.message ?: "新建失败" } } }) { Text("＋ 新建工作流") }
                workflows.forEach { workflow ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(workflow.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, localize = false)
                            IconButton(onClick = {
                                exported = store.export(workflow.id)
                                exporter.launch(workflow.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".lmworkflow.json")
                            }) { Icon(Icons.Default.FileDownload, "导出工作流") }
                        }
                        val validation = WorkflowValidator.validate(workflow.program)
                        Text((if(workflow.builtIn) "内置参考" else if(workflow.editable) "可编辑" else "固定") +
                            " · 版本 ${workflow.revision} · ${workflow.program.allNodes().size} 行", style = MaterialTheme.typography.labelSmall)
                        Text(if(validation.valid) "配置完整" else "草稿 · ${validation.issues.size} 处待填写", style = MaterialTheme.typography.labelSmall)
                        if(workflow.description.isNotBlank()) Text(workflow.description, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { inspecting = workflow }) { Text("查看") }
                            TextButton(onClick = { scope.launch {
                                runCatching { store.copy(workflow.id) }.onSuccess { if(it.editable) editing = it else inspecting = it }.onFailure { error = it.message ?: "复制失败" }
                            } }) { Text("复制") }
                            if(!workflow.builtIn) {
                                if(workflow.editable) TextButton(onClick = { editing = workflow }) { Text("修改") }
                                TextButton(onClick = { deleting = workflow }) { Text("删除") }
                            }
                        }
                    } }
                }
            }
        }
    }
    imported?.let { value ->
        val ids = value.program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
            .map { (it.inputs["profile"] as? WorkflowExpression.Text)?.value.orEmpty() }.distinct()
        var selecting by remember(value) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { imported = null }, title = { Text("导入 · 绑定本机 API") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value.name + if(value.editable) " · 可编辑" else " · 固定")
                ids.forEach { id ->
                    Text("文件引用：" + id.ifBlank { "未绑定" }, style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { selecting = id }, modifier = Modifier.fillMaxWidth()) {
                        Text(bindings[id]?.let { mapped -> profiles.firstOrNull { it.id == mapped }?.name ?: if(mapped.isEmpty()) "暂不绑定" else mapped } ?: "选择本机 API")
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = ids.all { it in bindings }, onClick = { scope.launch {
            fun remap(rows: List<WorkflowNode>): List<WorkflowNode> = rows.map { node ->
                val inputs = if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) node.inputs +
                    ("profile" to WorkflowExpression.Text(bindings[(node.inputs["profile"] as? WorkflowExpression.Text)?.value.orEmpty()].orEmpty())) else node.inputs
                node.copy(inputs = inputs, children = remap(node.children), otherwise = remap(node.otherwise))
            }
            runCatching { store.import(value.copy(program = value.program.copy(rows = remap(value.program.rows)))) }
                .onSuccess { imported = null; snackbar.showLocalizedSnackbar(context, "已导入 " + it.name) }.onFailure { error = it.message ?: "导入失败" }
        } }) { Text("导入") } }, dismissButton = { TextButton(onClick = { imported = null }) { Text("取消") } })
        selecting?.let { id -> ChoiceDialog("绑定本机 API", listOf("" to "暂不绑定（之后统一绑定）") + profiles.filter { it.kind == ApiProfileKind.LLM }.map { it.id to "${it.name} · ${it.model}" }, { selecting = null }) {
            bindings = bindings + (id to it); selecting = null
        } }
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除工作流？") }, text = {
        Text("正在引用的漫画需要重新选择工作流。" + if(references.isNullOrEmpty()) "" else "\n\n正在使用：" + references!!.joinToString("、"))
    }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { store.delete(item.id) }.onSuccess { deleting = null }.onFailure { error = it.message ?: "删除失败" } } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    error?.let { AlertDialog(onDismissRequest = { error = null }, title = { Text("工作流操作失败") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("关闭") } }) }
}
