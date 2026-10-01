package com.lmreader.core.workflow

import com.lmreader.core.model.*

data class WorkflowIssue(val nodeId: String, val message: String)
data class WorkflowAvailableVariable(val variable: WorkflowVariable, val readOnly: Boolean = false)
data class WorkflowValidation(val issues: List<WorkflowIssue>, val scopes: Map<String, List<WorkflowAvailableVariable>>) {
    val valid get() = issues.isEmpty()
}

object WorkflowValidator {
    val fixedKinds = setOf(WorkflowKind.MANGA, WorkflowKind.CHAPTERS, WorkflowKind.PAGES)
    fun validate(program: WorkflowProgram): WorkflowValidation {
        val issues = mutableListOf<WorkflowIssue>(); val scopes = mutableMapOf<String, List<WorkflowAvailableVariable>>()
        val ids = mutableSetOf<String>(); val variables = mutableSetOf<String>(); val counts = mutableMapOf<WorkflowKind, Int>()
        var rowCount = 0
        fun issue(n: WorkflowNode, message: String) { issues += WorkflowIssue(n.id, message) }
        fun refType(ref: WorkflowRef, scope: List<WorkflowAvailableVariable>): WorkflowType? {
            var type = scope.lastOrNull { it.variable.id == ref.variableId }?.variable?.type ?: return null
            for (field in ref.path) type = if (type.kind == WorkflowDataKind.LIST && field.toIntOrNull()?.let { it >= 0 } == true)
                type.element ?: return null else type.fields[field] ?: return null
            return type
        }
        fun expressionType(e: WorkflowExpression, n: WorkflowNode, scope: List<WorkflowAvailableVariable>): WorkflowType? = when (e) {
            is WorkflowExpression.Text -> WorkflowType.TEXT
            is WorkflowExpression.Number -> WorkflowType.NUMBER.also { if (!e.value.isFinite()) issue(n, "数字必须有限") }
            is WorkflowExpression.Boolean -> WorkflowType.BOOLEAN
            is WorkflowExpression.Empty -> e.type
            is WorkflowExpression.Ref -> refType(e.value, scope).also { if (it == null) issue(n, "变量或字段在此处不可用：${e.value.variableId}") }
            is WorkflowExpression.Template -> WorkflowType.TEXT.also {
                val names = Regex("\\$\\{([^}]+)\\}").findAll(e.text).map { m -> m.groupValues[1] }.toSet()
                names.forEach { key -> if (e.bindings[key]?.let { refType(it, scope) } == null) issue(n, "提示词变量不可用：$key") }
            }
            is WorkflowExpression.Record -> WorkflowType(WorkflowDataKind.RECORD, fields = e.fields.mapValues { expressionType(it.value, n, scope) ?: WorkflowType.TEXT })
        }
        fun writable(ref: WorkflowRef, n: WorkflowNode, scope: List<WorkflowAvailableVariable>): Boolean {
            val definition = scope.lastOrNull { it.variable.id == ref.variableId }
            if (definition == null) { issue(n, "输出变量在此处不可用"); return false }
            if (definition.readOnly) { issue(n, "异步分支不能修改父层变量；请用收集结果"); return false }
            if (ref.variableId.startsWith("builtin.")) {
                val allowed = ref.variableId == WorkflowSystem.PAGE && ref.path.firstOrNull() in setOf("bubbles", "records") ||
                    definition.variable.type.kind == WorkflowDataKind.BUBBLE && ref.path.firstOrNull() in setOf("source", "translation")
                if (!allowed) { issue(n, "此系统字段只读"); return false }
            } else if (definition.variable.type.kind == WorkflowDataKind.BUBBLE && ref.path.firstOrNull() !in setOf("source", "translation")) {
                issue(n, "气泡身份、图片和几何只读"); return false
            }
            if(ref.variableId == WorkflowSystem.PAGE && ref.path.firstOrNull() == "bubbles" && ref.path.size > 2 && ref.path[2] !in setOf("source", "translation")) {
                issue(n, "气泡身份、图片和几何只读"); return false
            }
            return true
        }
        fun compatible(actual: WorkflowType?, expected: WorkflowType) = actual == null || actual == expected
        fun visit(rows: List<WorkflowNode>, parent: WorkflowKind?, initial: List<WorkflowAvailableVariable>, depth: Int, owner: String = "root") {
            scopes[owner + ":children"] = initial
            if (depth > 16) { issues += WorkflowIssue(rows.firstOrNull()?.id.orEmpty(), "最多嵌套 16 层"); return }
            var scope = initial.toList()
            for (n in rows) {
                if (++rowCount > 500) { issue(n, "最多 500 行"); return }
                if (!ids.add(n.id) || n.id.isBlank()) issue(n, "行 ID 不能为空或重复")
                if (n.label.length > 80) issue(n, "行名称过长")
                counts[n.kind] = (counts[n.kind] ?: 0) + 1
                scopes[n.id] = scope
                n.inputs.filterKeys { it != "collectValue" }.values.forEach { expressionType(it, n, scope) }
                fun input(key: String, type: WorkflowType? = null): WorkflowType? {
                    val e = n.inputs[key] ?: run { issue(n, "请设置参数：$key"); return null }
                    return expressionType(e, n, scope).also { if (type != null && !compatible(it, type)) issue(n, "参数 $key 的类型不匹配") }
                }
                fun output(type: WorkflowType) {
                    val ref = n.target ?: run { issue(n, "请选择输出变量"); return }
                    writable(ref, n, scope)
                    val destination = refType(ref, scope)
                    if (destination == null || !compatible(type, destination)) issue(n, "输出类型与变量不匹配")
                }
                fun define(variable: WorkflowVariable?, child: Boolean = false): List<WorkflowAvailableVariable> {
                    if (variable == null) { issue(n, "请新建变量"); return scope }
                    if (variable.name.isBlank() || variable.name.length > 80) issue(n, "变量名称应为 1～80 字")
                    if(variable.id.startsWith("builtin.") && !(n.kind == WorkflowKind.EACH && (variable.id == WorkflowSystem.PAGE && variable.type == WorkflowSystem.pageType || variable.id == WorkflowSystem.BUBBLE && variable.type == WorkflowType.BUBBLE))) issue(n, "系统变量 ID 不能用于自定义变量")
                    if ((variable.id != WorkflowSystem.PAGE && !variables.add(variable.id)) || scope.any { it.variable.id == variable.id }) issue(n, "变量 ID 重复")
                    if (scope.any { it.variable.name == variable.name && !child }) issue(n, "此名称已经使用")
                    return scope + WorkflowAvailableVariable(variable)
                }
                when (n.kind) {
                    WorkflowKind.MANGA -> {
                        if (parent != null) issue(n, "漫画必须位于最外层")
                        val system = listOf(WorkflowVariable(WorkflowSystem.MANGA, "漫画", WorkflowSystem.mangaType),
                            WorkflowVariable(WorkflowSystem.SOURCE, "源语言", WorkflowType.TEXT), WorkflowVariable(WorkflowSystem.TARGET, "目标语言", WorkflowType.TEXT),
                            WorkflowVariable(WorkflowSystem.STYLE, "文风", WorkflowType.TEXT), WorkflowVariable(WorkflowSystem.GLOSSARY, "译名字典", WorkflowType.DICTIONARY),
                            WorkflowVariable(WorkflowSystem.ALL_PAGES, "漫画页列表", WorkflowType.list(WorkflowSystem.pageType)))
                        visit(n.children, n.kind, scope + system.map { WorkflowAvailableVariable(it, true) }, depth + 1, n.id)
                    }
                    WorkflowKind.CHAPTERS -> {
                        if (parent != WorkflowKind.MANGA) issue(n, "每章节必须在漫画内")
                        val parents = if (n.mode == WorkflowMode.ASYNC) scope.map { it.copy(readOnly = true) } else scope
                        visit(n.children, n.kind, parents + WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.CHAPTER, "本章", WorkflowSystem.chapterType), true), depth + 1, n.id)
                    }
                    WorkflowKind.PAGES -> {
                        if (parent != WorkflowKind.CHAPTERS) issue(n, "每页必须在每章节内")
                        val parents = if (n.mode == WorkflowMode.ASYNC) scope.map { it.copy(readOnly = true) } else scope
                        visit(n.children, n.kind, parents + WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.PAGE, "本页", WorkflowSystem.pageType)), depth + 1, n.id)
                    }
                    WorkflowKind.PREPARE_MANGA -> {
                        if (parent != WorkflowKind.MANGA) issue(n, "整漫画预处理必须位于漫画内")
                        output(WorkflowType.list(WorkflowType.PAGE_RECORD))
                        val owned = setOf(WorkflowSystem.CHAPTER, WorkflowSystem.PAGE)
                        val inherited = scope.filterNot { it.variable.id in owned }.map { it.copy(readOnly = true) }
                        visit(n.children, n.kind, inherited + listOf(
                            WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.CHAPTER, "本章", WorkflowSystem.chapterType), true),
                            WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.PAGE, "本页", WorkflowSystem.pageType))), depth + 1, n.id)
                    }
                    WorkflowKind.EACH -> {
                        val collection = input("items")
                        if (collection?.kind !in setOf(null, WorkflowDataKind.LIST, WorkflowDataKind.DICTIONARY)) issue(n, "每项只能遍历列表或字典")
                        val element = if (collection?.kind == WorkflowDataKind.DICTIONARY) WorkflowType(WorkflowDataKind.RECORD,
                            fields = mapOf("key" to WorkflowType.TEXT, "value" to WorkflowType.TEXT)) else collection?.element
                        val iterator = n.variable
                        if (iterator != null && element != null && iterator.type != element) issue(n, "循环项类型与列表不一致")
                        var childScope = define(n.variable, true).map { if (n.mode == WorkflowMode.ASYNC && it.variable.id != n.variable?.id) it.copy(readOnly = true) else it }
                        if(element == WorkflowSystem.pageType) {
                            if(iterator?.id != WorkflowSystem.PAGE) issue(n, "页面循环的条目变量应为本页")
                            childScope = childScope + WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.CHAPTER, "本章", WorkflowSystem.chapterType), true)
                            n.inputs["publish"]?.let { if(it !is WorkflowExpression.Boolean) issue(n, "保存本页应为开关") }
                        }
                        visit(n.children, n.kind, childScope, depth + 1, n.id)
                        n.collectTo?.let {
                            writable(it, n, scope); val collected = refType(it, scope)
                            if (collected?.kind != WorkflowDataKind.LIST) issue(n, "收集结果必须输出到列表")
                            fun returns(rows: List<WorkflowNode>): List<WorkflowNode> = rows.flatMap { row -> when(row.kind) {
                                WorkflowKind.RETURN -> listOf(row); WorkflowKind.IF -> returns(row.children) + returns(row.otherwise); else -> emptyList()
                            } }
                            val chosen = n.inputs["collectValue"]
                            if(chosen != null) {
                                val endScope = n.children.lastOrNull()?.let { scopes[it.id + ":end"] } ?: childScope
                                val valueType = expressionType(chosen, n, endScope.orEmpty())
                                val expected = if(n.inputs["flatten"] == WorkflowExpression.Boolean(true)) collected else collected?.element
                                if(valueType != expected) issue(n, "收集值类型与目标列表不一致")
                            } else {
                                val branches = returns(n.children)
                                if(branches.isEmpty()) issue(n, "请选择收集值")
                                if(branches.any { row -> row.resultType != collected?.element }) issue(n, "返回值类型与收集列表的条目不一致")
                            }
                        }
                    }
                    WorkflowKind.IF -> { input("condition", WorkflowType.BOOLEAN); visit(n.children, n.kind, scope, depth + 1, n.id); visit(n.otherwise, n.kind, scope, depth + 1, n.id + ":otherwise") }
                    WorkflowKind.DECLARE -> {
                        val type = n.variable?.type
                        fun canInitialize(t: WorkflowType): Boolean = t.kind !in setOf(WorkflowDataKind.IMAGE, WorkflowDataKind.BUBBLE) && t.fields.values.all(::canInitialize)
                        if(type != null && n.inputs["value"] == null && !canInitialize(type)) issue(n, "图片、气泡或含图片的记录需要设置初始引用")
                        n.inputs["value"]?.let { if (type != null && !compatible(expressionType(it, n, scope), type)) issue(n, "初始值类型不匹配") }
                        scope = define(n.variable)
                    }
                    WorkflowKind.SET -> input("value")?.let(::output)
                    WorkflowKind.SEG -> { input("image", WorkflowType.IMAGE); output(WorkflowType.list(WorkflowType.BUBBLE)) }
                    WorkflowKind.OCR -> { input("image", WorkflowType.IMAGE); input("language", WorkflowType.TEXT); output(WorkflowType.TEXT) }
                    WorkflowKind.TRANSLATE -> { input("text", WorkflowType.TEXT); input("source", WorkflowType.TEXT); input("target", WorkflowType.TEXT); output(WorkflowType.TEXT) }
                    WorkflowKind.API, WorkflowKind.API_STREAM -> {
                        input("profile", WorkflowType.TEXT); input("prompt", WorkflowType.TEXT)
                        if ((n.inputs["profile"] as? WorkflowExpression.Text)?.value?.isBlank() == true) issue(n, "请选择 API 配置")
                        n.inputs["context"]?.let { if (expressionType(it, n, scope)?.kind != WorkflowDataKind.CONTEXT) issue(n, "上下文参数必须是上下文") }
                        n.inputs["images"]?.let { val t = expressionType(it, n, scope); if (t?.kind != WorkflowDataKind.IMAGE && t != WorkflowType.list(WorkflowType.IMAGE)) issue(n, "附件必须是图片或图片列表") }
                        n.inputs["expectedBubbles"]?.let {
                            val t = expressionType(it, n, scope)
                            if(t !in setOf(WorkflowType.list(WorkflowType.BUBBLE), WorkflowType.list(WorkflowType.TRANSLATION), WorkflowType.list(WorkflowType.PAGE_RECORD))) issue(n, "期望气泡必须是气泡／对照／页记录列表")
                            if(n.resultType !in setOf(WorkflowType.list(WorkflowType.TRANSLATION), WorkflowType.list(WorkflowType.PAGE_RECORD))) issue(n, "气泡校验需要对照或页记录输出")
                        }
                        n.inputs["wholeManga"]?.let {
                            if(it != WorkflowExpression.Boolean(true) && it != WorkflowExpression.Boolean(false)) issue(n, "整漫画单请求必须明确开启或关闭")
                            if(it == WorkflowExpression.Boolean(true) && parent != WorkflowKind.MANGA) issue(n, "整漫画单请求必须位于漫画内")
                        }
                        fun apiType(t: WorkflowType): Boolean = when (t.kind) {
                            WorkflowDataKind.TEXT, WorkflowDataKind.NUMBER, WorkflowDataKind.BOOLEAN, WorkflowDataKind.DICTIONARY -> true
                            WorkflowDataKind.LIST -> t.element?.let(::apiType) == true
                            WorkflowDataKind.RECORD -> t.fields.isNotEmpty() && t.fields.values.all(::apiType)
                            else -> false
                        }
                        if (!apiType(n.resultType)) issue(n, "API 不能构造图片或气泡几何")
                        output(n.resultType)
                        if(n.kind == WorkflowKind.API_STREAM) {
                            val element = n.resultType.element
                            if(n.resultType.kind != WorkflowDataKind.LIST || element?.kind !in setOf(WorkflowDataKind.RECORD, WorkflowDataKind.DICTIONARY)) issue(n, "流式输出必须是结构化记录列表")
                            if(n.variable?.type != element) issue(n, "当前条目类型应与输出列表一致")
                            val streamScope = define(n.variable, true) + WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.STREAM_INDEX, "当前条目序号", WorkflowType.NUMBER), true)
                            visit(n.children, n.kind, streamScope, depth + 1, n.id)
                        }
                    }
                    WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER -> {
                        val t = input("items")
                        if(n.kind == WorkflowKind.APPLY_TRANSLATIONS && t !in setOf(WorkflowType.TRANSLATION, WorkflowType.PAGE_RECORD, WorkflowType.list(WorkflowType.TRANSLATION), WorkflowType.list(WorkflowType.PAGE_RECORD))) issue(n, "回填需要气泡对照或页记录（列表）")
                        if(n.kind == WorkflowKind.APPLY_ORDER) {
                            val item = if(t?.kind == WorkflowDataKind.LIST) t.element else t
                            if(item?.kind != WorkflowDataKind.RECORD || item.fields["translation"] != WorkflowType.TEXT) issue(n, "顺序回填需要含 translation 文本字段的记录（列表）")
                            if(item?.fields?.get("source")?.let { it != WorkflowType.TEXT } == true) issue(n, "原文 source 应为文本")
                            input("index", WorkflowType.NUMBER)
                        }
                        if(refType(WorkflowRef(WorkflowSystem.PAGE), scope) == null) issue(n, "回填必须位于每页内")
                        else writable(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles")), n, scope)
                    }
                    WorkflowKind.APPEND, WorkflowKind.MERGE_LIST -> {
                        val target = n.target; val t = target?.let { refType(it, scope) }
                        if (target == null) issue(n, "请选择追加变量") else writable(target, n, scope)
                        val value = input("value")
                        if (t?.kind == WorkflowDataKind.TEXT && value != WorkflowType.TEXT) issue(n, "文本只能追加文本")
                        else if (t?.kind == WorkflowDataKind.LIST && !compatible(value, if (n.kind == WorkflowKind.APPEND) t.element!! else t)) issue(n, "追加条目的类型不匹配")
                        else if (t?.kind !in setOf(WorkflowDataKind.TEXT, WorkflowDataKind.LIST)) issue(n, "此变量不支持追加")
                    }
                    WorkflowKind.REPLACE -> { input("text", WorkflowType.TEXT); input("dictionary", WorkflowType.DICTIONARY); output(WorkflowType.TEXT) }
                    WorkflowKind.MESSAGE -> {
                        val ref = n.target; if (ref == null) issue(n, "请选择上下文") else { writable(ref, n, scope); if (refType(ref, scope) != WorkflowType.CONTEXT) issue(n, "消息应追加到上下文") }
                        input("user", WorkflowType.TEXT); n.inputs["assistant"]?.let { if (expressionType(it, n, scope) != WorkflowType.TEXT) issue(n, "助手消息必须是文本") }
                    }
                    WorkflowKind.MERGE_GLOSSARY -> input("items", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
                    WorkflowKind.RETURN -> input("value", n.resultType)
                }
                scopes[n.id + ":end"] = scope
            }
            scopes[(rows.lastOrNull()?.id ?: parent?.name.orEmpty()) + ":after"] = scope
        }
        if (program.version != 1) issues += WorkflowIssue("", "不支持的工作流版本")
        visit(program.rows, null, emptyList(), 0)
        fixedKinds.forEach { if (counts[it] != 1) issues += WorkflowIssue("", "漫画、每章节、每页结构各保留一个") }
        if (counts[WorkflowKind.SEG] == null) issues += WorkflowIssue(program.allNodes().firstOrNull { it.kind == WorkflowKind.PAGES }?.id.orEmpty(), "请添加 SEG 行生成译文气泡")
        return WorkflowValidation(issues.distinct(), scopes)
    }
}
