package com.lmreader.ui.settings.api

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.AiParameters

@Composable
internal fun AiParametersDialog(initial: AiParameters, onDismiss: () -> Unit, onApply: (AiParameters) -> Unit) {
    val labels = listOf("temperature（0～2）", "top_p（0～1）", "top_k（正整数）", "最大输出 token（正整数）", "frequency_penalty（-2～2）", "presence_penalty（-2～2）")
    var values by remember { mutableStateOf(listOf(initial.temperature, initial.topP, initial.topK, initial.maxTokens, initial.frequencyPenalty, initial.presencePenalty).map { it?.toString().orEmpty() }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 参数设置")
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("留空时不发送该参数，使用服务端默认。部分模型不支持所有参数。", style = MaterialTheme.typography.bodySmall)
            labels.forEachIndexed { index, label ->
                OutlinedTextField(values[index], { value -> values = values.toMutableList().apply { this[index] = value }; error = null }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (index >= 4) KeyboardType.Text else KeyboardType.Decimal, autoCorrectEnabled = false))
            }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            fun decimal(i: Int): Double? = values[i].trim().let { if (it.isEmpty()) null else it.toDoubleOrNull() ?: throw IllegalArgumentException("${labels[i]} 格式错误") }
            fun integer(i: Int): Int? = values[i].trim().let { if (it.isEmpty()) null else it.toIntOrNull() ?: throw IllegalArgumentException("${labels[i]} 格式错误") }
            val parameters = AiParameters(decimal(0), decimal(1), integer(2), integer(3), decimal(4), decimal(5))
            ApiProtocol.validateParameters(parameters); onApply(parameters)
        } catch (e: Exception) { error = e.message ?: "参数无效" }
    }) { Text("应用") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun CustomParametersDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自定义请求参数")
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("填写 JSON 对象，覆盖同名 AI 参数。model、messages、input、contents、stream 由调用组件管理。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 12, label = { Text("JSON 参数") }, isError = error != null)
        }
    }, confirmButton = { TextButton(onClick = {
        try { onApply(ApiProtocol.customParameters(value).toString()) } catch (e: Exception) { error = e.message ?: "JSON 无效" }
    }) { Text("应用") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ModelsDialog(state: ApiEditorState, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择模型") }, text = {
        Column(Modifier.fillMaxWidth()) {
            if (state.modelsLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在获取…", Modifier.padding(top = 12.dp)) }
            state.modelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.modelsLoading && state.modelsError == null && state.models.isEmpty()) Text("服务端没有返回可用模型，请手工填写名称")
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(state.models, key = { it }) { model ->
                    Text(model, Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 16.dp)); HorizontalDivider()
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun ModelResponseDialog(state: ApiEditorState, onStop: () -> Unit, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    var thinkingOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.response, state.thinking, follow, thinkingOpen) { if (follow) scroll.scrollTo(scroll.maxValue) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("模型返回", style = MaterialTheme.typography.headlineSmall)
                Text(state.testStatus, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(follow, { follow = !follow }, label = { Text("跟随输出") })
                    if (state.thinking.isNotEmpty()) FilterChip(thinkingOpen, { thinkingOpen = !thinkingOpen }, label = { Text("思考内容") })
                }
                SelectionContainer(Modifier.weight(1f, fill = false).heightIn(min = 120.dp).verticalScroll(scroll)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (thinkingOpen && state.thinking.isNotEmpty()) Text(state.thinking, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.response.ifEmpty { if (state.running) "等待模型输出…" else "暂无正文" })
                        state.testError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.running) TextButton(onClick = onStop) { Text("停止") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
