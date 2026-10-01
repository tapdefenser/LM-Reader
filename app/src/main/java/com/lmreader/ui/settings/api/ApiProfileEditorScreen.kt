package com.lmreader.ui.settings.api

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer

@Composable
fun ApiProfileEditorScreen(container: AppContainer, kind: ApiProfileKind, id: String?, onBack: () -> Unit) {
    val vm: ApiProfileEditorViewModel = viewModel(factory = viewModelFactory {
        initializer { ApiProfileEditorViewModel(container.apiProfiles, container.apiClient, kind, id) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val profile = draft.profile
    var discard by remember { mutableStateOf(false) }
    var formatOpen by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var parametersOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var promptOpen by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val back = { if (state.dirty) discard = true else onBack() }
    BackHandler(onBack = back)
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    Scaffold(bottomBar = {
        state.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }, topBar = {
        ApiTopBar(if (id == null) "新增${if (kind == ApiProfileKind.LLM) " LLM" else " OCR"} 配置" else "修改配置", back) {
            TextButton(onClick = vm::save, enabled = !state.loading && !state.saving && !state.loadFailed) { Text(if (state.saving) "保存中…" else "保存") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val editable = !state.loading && !state.saving && !state.loadFailed
            OutlinedTextField(profile.name, { vm.changeProfile { p -> p.copy(name = it) } }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true, label = { Text("配置名称（可选）") })
            OutlinedTextField(profile.url, { vm.changeProfile { p -> p.copy(url = it) } }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text("API 地址") }, placeholder = { Text("http://host:port/v1") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Box {
                OutlinedButton(onClick = { formatOpen = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("API 格式：${profile.format.label}") }
                DropdownMenu(expanded = formatOpen, onDismissRequest = { formatOpen = false }) {
                    ApiFormat.entries.forEach { format -> DropdownMenuItem(text = { Text(format.label) }, onClick = { vm.changeProfile { it.copy(format = format) }; formatOpen = false }) }
                }
            }
            OutlinedTextField(profile.apiKey, { vm.changeProfile { p -> p.copy(apiKey = it) } }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text("API Key") }, visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (keyVisible) "隐藏 Key" else "显示 Key") } },
                supportingText = { Text("可留空，凭据加密保存在本机") })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(profile.model, { vm.changeProfile { p -> p.copy(model = it) } }, Modifier.weight(1f), enabled = editable, singleLine = true, label = { Text("模型名称") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                OutlinedButton(onClick = vm::fetchModels, enabled = editable && !state.modelsLoading) { Text("获取列表") }
            }
            NumberField("API 超时（秒）", draft.timeout, { vm.change { d -> d.copy(timeout = it) } }, "30～1200，单次请求的总时限", editable)
            NumberField("API 重试次数", draft.retries, { vm.change { d -> d.copy(retries = it) } }, "0～50；收到输出后发生中断不自动重发", editable)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("API 启用思考", Modifier.weight(1f))
                Switch(profile.thinkingEnabled, { vm.changeProfile { p -> p.copy(thinkingEnabled = it) } }, enabled = editable)
            }
            Text("开启后请求指定的思考长度；关闭时使用服务端默认。需要模型支持对应参数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("思考长度")
                ThinkingLevel.entries.forEach { level -> FilterChip(selected = profile.thinkingLevel == level, onClick = { vm.changeProfile { it.copy(thinkingLevel = level) } }, label = { Text(level.label) }, enabled = editable && profile.thinkingEnabled) }
            }
            OutlinedButton(onClick = { parametersOpen = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("AI 参数设置") }
            OutlinedButton(onClick = { customOpen = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("自定义请求参数") }
            NumberField("API 并行限制", draft.parallel, { vm.change { d -> d.copy(parallel = it) } }, "1～200；此配置同时进行的请求数", editable)
            Button(onClick = { promptOpen = true }, enabled = editable && !state.running, modifier = Modifier.fillMaxWidth()) { Text("模型测试") }
            if (kind == ApiProfileKind.OCR) Text("模型测试发送输入的文字。图片识别与本地 OCR 组件将独立接入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃修改？") }, text = { Text("当前修改尚未保存。") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("放弃") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (parametersOpen) AiParametersDialog(profile.parameters, onDismiss = { parametersOpen = false }, onApply = { vm.changeProfile { p -> p.copy(parameters = it) }; parametersOpen = false })
    if (customOpen) CustomParametersDialog(profile.customParameters, onDismiss = { customOpen = false }, onApply = { vm.changeProfile { p -> p.copy(customParameters = it) }; customOpen = false })
    if (promptOpen) AlertDialog(onDismissRequest = { promptOpen = false }, title = { Text("模型测试") }, text = {
        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("输入请求") }, minLines = 3, maxLines = 8)
    }, confirmButton = { TextButton(onClick = { promptOpen = false; vm.startTest(prompt) }, enabled = prompt.isNotBlank()) { Text("发送") } }, dismissButton = { TextButton(onClick = { promptOpen = false }) { Text("取消") } })
    if (state.modelsOpen) ModelsDialog(state, vm::chooseModel, vm::closeModels)
    if (state.responseOpen) ModelResponseDialog(state, vm::stopTest, vm::closeResponse)
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, hint: String, enabled: Boolean) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) }, supportingText = { Text(hint) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
