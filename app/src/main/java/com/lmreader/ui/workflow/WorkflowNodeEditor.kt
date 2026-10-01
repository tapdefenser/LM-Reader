package com.lmreader.ui.workflow

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkflowNodeEditor(value: WorkflowNode, scope: List<WorkflowAvailableVariable>, profiles: List<ApiProfile>,
    dismiss: () -> Unit, readOnly: Boolean = false, collectScope: List<WorkflowAvailableVariable> = emptyList(), save: (WorkflowNode) -> Unit) {
    var node by remember(value.id) { mutableStateOf(value) }
    var leaving by remember { mutableStateOf(false) }
    val dirty = !readOnly && node != value
    val latestDirty by rememberUpdatedState(dirty)
    fun leave() { if(dirty) leaving = true else dismiss() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if(it == SheetValue.Hidden && latestDirty) { leaving = true; false } else true
    })
    val refs = WorkflowEditing.references(scope)
    val enabled = !readOnly
    fun parameter(key: String, expression: WorkflowExpression?) {
        node = node.copy(inputs = if(expression == null) node.inputs - key else node.inputs + (key to expression))
    }
    fun targetType() = refs.firstOrNull { it.ref == node.target }?.type
    @Composable fun input(key: String, label: String, type: WorkflowType?, optional: Boolean = false) {
        ExpressionEditor(label, type, node.inputs[key], refs, optional = optional, enabled = enabled) { parameter(key, it) }
    }
    ModalBottomSheet(onDismissRequest = { leave() }, sheetState = sheet) {
        BackHandler { leave() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 16.dp).imePadding()) {
            Row {
                Text(WorkflowLabels.kind(node.kind), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { leave() }) { Text(if(readOnly) "关闭" else "取消") }
                if(enabled) TextButton(onClick = { save(node) }) { Text("完成") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("输入")
                when(node.kind) {
                    WorkflowKind.DECLARE -> input("value", "初始值", node.variable?.type, optional = true)
                    WorkflowKind.EACH -> ExpressionEditor("遍历列表／字典", null, node.inputs["items"], refs.filter { it.type.kind in setOf(WorkflowDataKind.LIST, WorkflowDataKind.DICTIONARY) }, variableOnly = true, enabled = enabled) { e ->
                        parameter("items", e)
                        val collection = (e as? WorkflowExpression.Ref)?.let { refs.firstOrNull { r -> r.ref == it.value }?.type }
                        val element = if(collection?.kind == WorkflowDataKind.DICTIONARY) WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("key" to WorkflowType.TEXT, "value" to WorkflowType.TEXT)) else collection?.element ?: WorkflowType.TEXT
                        node = node.copy(variable = node.variable?.let { variable -> variable.copy(id = if(element == WorkflowSystem.pageType) WorkflowSystem.PAGE else if(variable.id == WorkflowSystem.PAGE) java.util.UUID.randomUUID().toString() else variable.id, name = if(element == WorkflowSystem.pageType) "本页" else variable.name, type = element) })
                    }
                    WorkflowKind.SEG -> input("image", "图片 · 输入图片", WorkflowType.IMAGE)
                    WorkflowKind.OCR -> { input("image", "图片 · 气泡图片", WorkflowType.IMAGE); input("language", "文本 · 原文语言", WorkflowType.TEXT) }
                    WorkflowKind.TRANSLATE -> { input("text", "文本 · 原文", WorkflowType.TEXT); input("source", "文本 · 源语言", WorkflowType.TEXT); input("target", "文本 · 目标语言", WorkflowType.TEXT) }
                    WorkflowKind.API, WorkflowKind.API_STREAM -> {
                        input("context", "上下文 · 请求上下文", WorkflowType.CONTEXT, optional = true)
                        TemplateEditor("文本 · 请求提示词", node.inputs["prompt"], refs, enabled) { parameter("prompt", it) }
                        ExpressionEditor("图片 · 附件（可选）", null, node.inputs["images"], refs.filter { it.type == WorkflowType.IMAGE || it.type == WorkflowType.list(WorkflowType.IMAGE) }, optional = true, variableOnly = true, enabled = enabled) { parameter("images", it) }
                        ExpressionEditor("列表 · 校验输入气泡（可选）", null, node.inputs["expectedBubbles"], refs.filter { it.type in setOf(WorkflowType.list(WorkflowType.BUBBLE), WorkflowType.list(WorkflowType.TRANSLATION), WorkflowType.list(WorkflowType.PAGE_RECORD)) }, optional = true, variableOnly = true, enabled = enabled) { parameter("expectedBubbles", it) }
                    }
                    WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER -> {
                        val choices = refs.filter { r ->
                            val item = if(r.type.kind == WorkflowDataKind.LIST) r.type.element else r.type
                            if(node.kind == WorkflowKind.APPLY_ORDER) item?.kind == WorkflowDataKind.RECORD && item.fields["translation"] == WorkflowType.TEXT
                            else item in setOf(WorkflowType.TRANSLATION, WorkflowType.PAGE_RECORD)
                        }
                        ExpressionEditor("记录／列表 · 译文", null, node.inputs["items"], choices, variableOnly = true, enabled = enabled) { parameter("items", it) }
                        if(node.kind == WorkflowKind.APPLY_ORDER) input("index", "数字 · 起始气泡序号（从 1 开始）", WorkflowType.NUMBER)
                    }
                    WorkflowKind.SET -> input("value", "赋值", targetType())
                    WorkflowKind.APPEND -> input("value", "新增值", targetType()?.let { if(it.kind == WorkflowDataKind.LIST) it.element else it })
                    WorkflowKind.MERGE_LIST -> input("value", "合并列表", targetType())
                    WorkflowKind.REPLACE -> { input("text", "文本 · 匹配文本", WorkflowType.TEXT); input("dictionary", "字典 · 译名字典", WorkflowType.DICTIONARY) }
                    WorkflowKind.MESSAGE -> {
                        TemplateEditor("文本 · 用户上下文", node.inputs["user"], refs, enabled) { parameter("user", it) }
                        TemplateEditor("文本 · 助手示例（可选）", node.inputs["assistant"], refs, enabled, optional = true) { parameter("assistant", it) }
                    }
                    WorkflowKind.MERGE_GLOSSARY -> input("items", "列表 · 原词／译名", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
                    WorkflowKind.IF -> input("condition", "布尔 · 条件", WorkflowType.BOOLEAN)
                    WorkflowKind.RETURN -> input("value", "旧版返回值", node.resultType)
                    else -> Text("由漫画与当前作用域提供。", style = MaterialTheme.typography.bodySmall)
                }
                Section("输出")
                val filter: (WorkflowReferenceChoice) -> Boolean = when(node.kind) {
                    WorkflowKind.SEG -> { r -> r.type == WorkflowType.list(WorkflowType.BUBBLE) }
                    WorkflowKind.OCR, WorkflowKind.TRANSLATE, WorkflowKind.REPLACE -> { r -> r.type == WorkflowType.TEXT }
                    WorkflowKind.MESSAGE -> { r -> r.type == WorkflowType.CONTEXT }
                    WorkflowKind.APPEND -> { r -> r.type.kind in setOf(WorkflowDataKind.TEXT, WorkflowDataKind.LIST) }
                    WorkflowKind.MERGE_LIST -> { r -> r.type.kind == WorkflowDataKind.LIST }
                    WorkflowKind.API -> { r -> apiOutput(r.type) }
                    WorkflowKind.API_STREAM -> { r -> r.type.kind == WorkflowDataKind.LIST && r.type.element?.kind in setOf(WorkflowDataKind.RECORD, WorkflowDataKind.DICTIONARY) && apiOutput(r.type) }
                    else -> { _ -> true }
                }
                if(node.kind in setOf(WorkflowKind.SEG, WorkflowKind.OCR, WorkflowKind.TRANSLATE, WorkflowKind.API, WorkflowKind.API_STREAM, WorkflowKind.SET, WorkflowKind.REPLACE, WorkflowKind.APPEND, WorkflowKind.MERGE_LIST, WorkflowKind.MESSAGE)) {
                    ReferencePicker("输出到", node.target, refs.filter { !it.readOnly && filter(it) }, enabled = enabled) { ref ->
                        val type = refs.firstOrNull { it.ref == ref }?.type ?: node.resultType
                        node = node.copy(target = ref, resultType = type, variable = if(node.kind == WorkflowKind.API_STREAM) node.variable?.copy(type = type.element ?: WorkflowType.TRANSLATION) else node.variable)
                    }
                }
                if(node.variable != null) {
                    val variable = requireNotNull(node.variable)
                    OutlinedTextField(variable.name, { if(it.length <= 80) node = node.copy(variable = variable.copy(name = it)) }, enabled = enabled && variable.id != WorkflowSystem.PAGE, label = { Text("变量名称") }, modifier = Modifier.fillMaxWidth())
                    if(node.kind == WorkflowKind.DECLARE) TypeEditor("变量类型", variable.type, enabled = enabled) { node = node.copy(variable = variable.copy(type = it), inputs = emptyMap()) }
                    else TypedVariable(WorkflowReferenceChoice(WorkflowRef(variable.id), variable.name, variable.type, false))
                }
                if(node.kind == WorkflowKind.EACH) {
                    ReferencePicker("收集到列表（可选）", node.collectTo, refs.filter { !it.readOnly && it.type.kind == WorkflowDataKind.LIST }, optional = true, enabled = enabled) {
                        node = node.copy(collectTo = it, inputs = if(it == null) node.inputs - "collectValue" - "flatten" else node.inputs)
                    }
                    node.collectTo?.let { target ->
                        val type = refs.firstOrNull { it.ref == target }?.type
                        val flatten = node.inputs["flatten"] == WorkflowExpression.Boolean(true)
                        Row { Text("合并每项列表", Modifier.weight(1f)); Switch(flatten, { parameter("flatten", WorkflowExpression.Boolean(it)); parameter("collectValue", null) }, enabled = enabled) }
                        val childScope = collectScope.filterNot { it.variable.id == node.variable?.id } + listOfNotNull(node.variable?.let { WorkflowAvailableVariable(it) })
                        ExpressionEditor("每项完成时收集值", if(flatten) type else type?.element, node.inputs["collectValue"], WorkflowEditing.references(childScope), enabled = enabled) { parameter("collectValue", it) }
                    }
                    if(node.variable?.type == WorkflowSystem.pageType) Row {
                        Text("循环结束保存本页译文", Modifier.weight(1f))
                        Switch(node.inputs["publish"] != WorkflowExpression.Boolean(false), { parameter("publish", WorkflowExpression.Boolean(it)) }, enabled = enabled)
                    }
                }
                if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
                    Text("输出：${WorkflowLabels.type(node.resultType)}", color = variableColor(node.resultType))
                    if(apiOutput(node.resultType)) Text("JSON 示例：${AndroidWorkflowHost.schemaExample(node.resultType)}", style = MaterialTheme.typography.bodySmall)
                    if(node.kind == WorkflowKind.API_STREAM) Text("每收到一个完整 JSON 条目，按顺序执行内部行。当前条目序号从 1 开始；后续行等待 API 完整结束。", style = MaterialTheme.typography.bodySmall)
                }
                if(node.kind in setOf(WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER)) Text("本页译文气泡列表。单条记录可在流式循环中回填；列表可以一次回填。", style = MaterialTheme.typography.bodySmall)
                Section("参数")
                OutlinedTextField(node.label, { if(it.length <= 80) node = node.copy(label = it) }, enabled = enabled, label = { Text("步骤名称（可选）") }, modifier = Modifier.fillMaxWidth())
                if(node.kind in setOf(WorkflowKind.CHAPTERS, WorkflowKind.PAGES, WorkflowKind.EACH)) Row {
                    Text("异步", Modifier.weight(1f)); Switch(node.mode == WorkflowMode.ASYNC, { node = node.copy(mode = if(it) WorkflowMode.ASYNC else WorkflowMode.SYNC) }, enabled = enabled)
                }
                if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
                    var choosing by remember { mutableStateOf(false) }
                    val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value
                    OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("API 配置：${profiles.firstOrNull { it.id == id }?.name ?: "未绑定"}") }
                    if(choosing) ChoiceDialog("选择 API 配置", profiles.filter { it.kind == ApiProfileKind.LLM }.map { it.id to "${it.name} · ${it.model} · 并行 ${it.parallelLimit}" }, { choosing = false }) { parameter("profile", WorkflowExpression.Text(it)); choosing = false }
                    if(scope.none { it.variable.id in setOf(WorkflowSystem.CHAPTER, WorkflowSystem.PAGE) }) Row {
                        Text("整漫画单请求", Modifier.weight(1f)); Switch(node.inputs["wholeManga"] == WorkflowExpression.Boolean(true), { parameter("wholeManga", WorkflowExpression.Boolean(it)) }, enabled = enabled)
                    }
                }
                if(node.kind == WorkflowKind.MERGE_GLOSSARY) Text("已有原词保持原译名；后来条目不能覆盖。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("保存步骤修改？") }, text = { Text("当前步骤有未保存的修改。") },
        confirmButton = { TextButton(onClick = { save(node) }) { Text("保存") } }, dismissButton = { Row {
            TextButton(onClick = dismiss) { Text("放弃") }; TextButton(onClick = { leaving = false }) { Text("继续编辑") }
        } })
}

@Composable private fun Section(label: String) { HorizontalDivider(); Text(label, style = MaterialTheme.typography.titleSmall) }
private fun apiOutput(type: WorkflowType): Boolean = when(type.kind) {
    WorkflowDataKind.TEXT, WorkflowDataKind.NUMBER, WorkflowDataKind.BOOLEAN, WorkflowDataKind.DICTIONARY -> true
    WorkflowDataKind.RECORD -> type.fields.isNotEmpty() && type.fields.values.all(::apiOutput)
    WorkflowDataKind.LIST -> type.element?.let(::apiOutput) == true
    else -> false
}

@Composable
private fun ReferencePicker(label: String, value: WorkflowRef?, choices: List<WorkflowReferenceChoice>, optional: Boolean = false,
    enabled: Boolean = true, changed: (WorkflowRef?) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if(optional) item { FilterChip(value == null, { changed(null) }, label = { Text("不使用") }, enabled = enabled) }
            items(choices.sortedBy { it.ref != value }) { choice -> FilterChip(choice.ref == value, { changed(choice.ref) }, label = { TypedVariable(choice) }, enabled = enabled) }
        }
        if(value != null && choices.none { it.ref == value }) Text("引用 · 此处变量不可用", color = MaterialTheme.colorScheme.error)
        if(choices.isEmpty()) Text("无匹配变量", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ExpressionEditor(label: String, type: WorkflowType?, value: WorkflowExpression?, references: List<WorkflowReferenceChoice>,
    optional: Boolean = false, variableOnly: Boolean = false, depth: Int = 0, enabled: Boolean = true, changed: (WorkflowExpression?) -> Unit) {
    val refs = references.filter { type == null || it.type == type }.sortedBy { it.ref != (value as? WorkflowExpression.Ref)?.value }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if(optional) item { FilterChip(value == null, { changed(null) }, label = { Text("不使用") }, enabled = enabled) }
            items(refs) { choice -> FilterChip((value as? WorkflowExpression.Ref)?.value == choice.ref, { changed(WorkflowExpression.Ref(choice.ref)) }, label = { TypedVariable(choice) }, enabled = enabled) }
            if(!variableOnly) {
                if(type == null || type == WorkflowType.TEXT) {
                    item { FilterChip(value is WorkflowExpression.Text, { changed(WorkflowExpression.Text("")) }, label = { Text("文本值", color = variableColor(WorkflowType.TEXT)) }, enabled = enabled) }
                    item { FilterChip(value is WorkflowExpression.Template, { changed(WorkflowExpression.Template((value as? WorkflowExpression.Text)?.value.orEmpty())) }, label = { Text("提示词模板") }, enabled = enabled) }
                }
                if(type == WorkflowType.NUMBER) item { FilterChip(value is WorkflowExpression.Number, { changed(WorkflowExpression.Number(0.0)) }, label = { Text("数字值", color = variableColor(type)) }, enabled = enabled) }
                if(type == WorkflowType.BOOLEAN) item { FilterChip(value is WorkflowExpression.Boolean, { changed(WorkflowExpression.Boolean(false)) }, label = { Text("布尔值", color = variableColor(type)) }, enabled = enabled) }
                if(type?.kind == WorkflowDataKind.RECORD) item { FilterChip(value is WorkflowExpression.Record, { changed(WorkflowExpression.Record(type.fields.mapValues { WorkflowExpression.Empty(it.value) })) }, label = { Text("构造记录", color = variableColor(type)) }, enabled = enabled) }
                if(type?.kind in setOf(WorkflowDataKind.LIST, WorkflowDataKind.DICTIONARY, WorkflowDataKind.CONTEXT)) item { FilterChip(value is WorkflowExpression.Empty, { changed(WorkflowExpression.Empty(type!!)) }, label = { Text("空${WorkflowLabels.type(type!!)}", color = variableColor(type)) }, enabled = enabled) }
            }
        }
        when(value) {
            is WorkflowExpression.Ref -> refs.firstOrNull { it.ref == value.value }?.let { TypedVariable(it) } ?: Text("引用 · 不可用变量")
            is WorkflowExpression.Template -> TemplateEditor("文本 · 模板", value, references, enabled, changed = changed)
            is WorkflowExpression.Text -> OutlinedTextField(value.value, { if(it.length <= 100_000) changed(WorkflowExpression.Text(it)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            is WorkflowExpression.Number -> {
                var raw by remember(value) { mutableStateOf(value.value.toString()) }
                OutlinedTextField(raw, { raw = it; it.toDoubleOrNull()?.takeIf { n -> n.isFinite() }?.let { n -> changed(WorkflowExpression.Number(n)) } }, enabled = enabled, isError = raw.toDoubleOrNull()?.isFinite() != true, modifier = Modifier.fillMaxWidth())
            }
            is WorkflowExpression.Boolean -> Switch(value.value, { changed(WorkflowExpression.Boolean(it)) }, enabled = enabled)
            is WorkflowExpression.Record -> if(depth < 8) type?.fields?.forEach { (key, fieldType) ->
                ExpressionEditor("${WorkflowLabels.type(fieldType)} · ${WorkflowLabels.field(key)}", fieldType, value.fields[key], references, depth = depth + 1, enabled = enabled) { e ->
                    changed(value.copy(fields = if(e == null) value.fields - key else value.fields + (key to e)))
                }
            }
            is WorkflowExpression.Empty -> Text("空${WorkflowLabels.type(value.type)}", style = MaterialTheme.typography.bodySmall)
            null -> Unit
        }
    }
}

@Composable
private fun TemplateEditor(label: String, expression: WorkflowExpression?, refs: List<WorkflowReferenceChoice>,
    enabled: Boolean, optional: Boolean = false, changed: (WorkflowExpression?) -> Unit) {
    val value = expression as? WorkflowExpression.Template ?: WorkflowExpression.Template((expression as? WorkflowExpression.Text)?.value.orEmpty())
    var field by remember { mutableStateOf(TextFieldValue(value.text, TextRange(value.text.length))) }
    if(field.text != value.text) field = field.copy(text = value.text, selection = TextRange(value.text.length))
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if(optional) TextButton(onClick = { changed(null) }, enabled = enabled && expression != null) { Text("不使用") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(refs.filter { serializable(it.type) }) { choice -> AssistChip(onClick = {
                var key = choice.label; var index = 2
                while(value.bindings[key]?.let { it != choice.ref } == true) key = choice.label + index++
                val token = "$" + "{" + key + "}"
                val start = field.selection.min; val end = field.selection.max
                val text = field.text.replaceRange(start, end, token)
                if(text.length <= 100_000) {
                    field = TextFieldValue(text, TextRange(start + token.length))
                    changed(value.copy(text = text, bindings = value.bindings + (key to choice.ref)))
                }
            }, enabled = enabled, label = { TypedVariable(choice) }) }
        }
        OutlinedTextField(field, { next -> if(next.text.length <= 100_000) { field = next; if(next.text != value.text) changed(value.copy(text = next.text)) } },
            enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("提示词模板") })
        val selected = (expression as? WorkflowExpression.Ref)?.let { e -> refs.firstOrNull { it.ref == e.value } }
        if(selected != null) {
            TypedVariable(selected)
            Text("当前为变量引用。输入模板会替换该引用。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
private fun serializable(type: WorkflowType): Boolean = when(type.kind) {
    WorkflowDataKind.IMAGE, WorkflowDataKind.CONTEXT -> false
    WorkflowDataKind.LIST -> type.element?.let(::serializable) == true
    else -> type.fields.values.all(::serializable)
}

@Composable
internal fun TypeEditor(label: String, value: WorkflowType, depth: Int = 0, enabled: Boolean = true, changed: (WorkflowType) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(WorkflowLabels.type(value), color = variableColor(value)) }
        if(value.kind == WorkflowDataKind.LIST && depth < 4) TypeEditor("列表条目类型", value.element!!, depth + 1, enabled) { changed(value.copy(element = it)) }
        if(value.kind == WorkflowDataKind.RECORD && depth < 4) {
            value.fields.entries.forEachIndexed { index, (key, type) ->
                var fieldName by remember(value, index) { mutableStateOf(key) }
                OutlinedTextField(fieldName, { text ->
                    fieldName = text
                    if(text.isNotBlank() && text.length <= 80 && (text == key || text !in value.fields)) changed(value.copy(fields = value.fields.entries.associate { (k, v) -> (if(k == key) text else k) to v }))
                }, enabled = enabled, label = { Text("字段名称") }, isError = fieldName.isBlank() || (fieldName != key && fieldName in value.fields))
                TypeEditor("字段类型", type, depth + 1, enabled) { changed(value.copy(fields = value.fields + (key to it))) }
                if(enabled) TextButton(onClick = { changed(value.copy(fields = value.fields - key)) }) { Text("删除字段") }
            }
            if(enabled) TextButton(enabled = value.fields.size < 64, onClick = {
                var key = "字段${value.fields.size + 1}"; while(key in value.fields) key += "新"
                changed(value.copy(fields = value.fields + (key to WorkflowType.TEXT)))
            }) { Text("＋ 新增字段") }
        }
    }
    if(choosing) ChoiceDialog(label, buildList {
        add(WorkflowType.TEXT to "文本"); add(WorkflowType.NUMBER to "数字"); add(WorkflowType.BOOLEAN to "布尔"); add(WorkflowType.IMAGE to "图片")
        add(WorkflowType.BUBBLE to "气泡（来自 SEG）"); add(WorkflowType.CONTEXT to "上下文"); add(WorkflowType.DICTIONARY to "字典")
        if(depth < 4) { add(WorkflowType.list(WorkflowType.TEXT) to "自定义列表"); add(WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("字段1" to WorkflowType.TEXT)) to "自定义记录") }
        add(WorkflowType.TRANSLATION to "气泡对照：bubbleId／source／translation")
        add(WorkflowType.GLOSSARY_ENTRY to "译名条目：source／translation")
        add(WorkflowType.PAGE_RECORD to "页翻译记录：页ID／页码／气泡ID／原文／译文")
    }, { choosing = false }) { changed(it); choosing = false }
}
