package com.lmreader.core.workflow

import com.lmreader.core.model.*
import java.util.UUID

data class WorkflowPosition(val parentId: String? = null, val index: Int, val otherwise: Boolean = false)
data class WorkflowFlatRow(val node: WorkflowNode, val depth: Int, val position: WorkflowPosition)
data class WorkflowReferenceChoice(val ref: WorkflowRef, val label: String, val type: WorkflowType, val readOnly: Boolean)

object WorkflowEditing {
    fun flatten(program: WorkflowProgram): List<WorkflowFlatRow> = buildList {
        fun visit(rows: List<WorkflowNode>, parent: String?, depth: Int, otherwise: Boolean = false) {
            rows.forEachIndexed { index, node ->
                add(WorkflowFlatRow(node, depth, WorkflowPosition(parent, index, otherwise)))
                visit(node.children, node.id, depth + 1); visit(node.otherwise, node.id, depth + 1, true)
            }
        }
        visit(program.rows, null, 0)
    }
    fun rows(program: WorkflowProgram, position: WorkflowPosition): List<WorkflowNode> = if (position.parentId == null) program.rows else {
        val node = program.allNodes().first { it.id == position.parentId }; if (position.otherwise) node.otherwise else node.children
    }
    fun update(program: WorkflowProgram, id: String, change: (WorkflowNode) -> WorkflowNode): WorkflowProgram {
        fun apply(rows: List<WorkflowNode>): List<WorkflowNode> = rows.map { node ->
            if (node.id == id) change(node) else node.copy(children = apply(node.children), otherwise = apply(node.otherwise))
        }
        return program.copy(rows = apply(program.rows))
    }
    private fun changeRows(program: WorkflowProgram, position: WorkflowPosition, change: (List<WorkflowNode>) -> List<WorkflowNode>) =
        if (position.parentId == null) program.copy(rows = change(program.rows)) else update(program, position.parentId) {
            if (position.otherwise) it.copy(otherwise = change(it.otherwise)) else it.copy(children = change(it.children))
        }
    fun insert(program: WorkflowProgram, position: WorkflowPosition, node: WorkflowNode): WorkflowProgram = changeRows(program, position) { rows ->
        require(position.index in 0..rows.size); rows.toMutableList().apply { add(position.index, node) }
    }
    fun remove(program: WorkflowProgram, id: String): WorkflowProgram {
        val row = flatten(program).first { it.node.id == id }
        require(row.node.kind !in WorkflowValidator.fixedKinds) { "固定结构不能删除" }
        require(WorkflowProgram(rows = row.node.children + row.node.otherwise).allNodes().none { it.kind in WorkflowValidator.fixedKinds }) { "不能删除固定结构的父层" }
        return changeRows(program, row.position) { it.filterNot { node -> node.id == id } }
    }
    fun move(program: WorkflowProgram, id: String, target: WorkflowPosition): WorkflowProgram {
        val row = flatten(program).first { it.node.id == id }
        require(row.node.kind !in WorkflowValidator.fixedKinds) { "漫画、每章节、每页位置固定" }
        val subtree = WorkflowProgram(rows = listOf(row.node)).allNodes().map { it.id }
        require(target.parentId !in subtree) { "不能移入自己内部" }
        val destination = if (target.parentId == row.position.parentId && target.otherwise == row.position.otherwise && target.index > row.position.index)
            target.copy(index = target.index - 1) else target
        val changed = insert(remove(program, id), destination, row.node)
        val old = WorkflowValidator.validate(program).issues.map { it.nodeId to it.message }.toSet()
        val introduced = WorkflowValidator.validate(changed).issues.filter { it.nodeId to it.message !in old }
        require(introduced.isEmpty()) { introduced.joinToString("；") { it.message } }
        return changed
    }
    fun scope(program: WorkflowProgram, position: WorkflowPosition): List<WorkflowAvailableVariable> {
        val validation = WorkflowValidator.validate(program); val rows = rows(program, position)
        return if (position.index < rows.size) validation.scopes[rows[position.index].id].orEmpty()
        else if (rows.isNotEmpty()) validation.scopes[rows.last().id + ":end"].orEmpty()
        else validation.scopes[(position.parentId ?: "root") + (if (position.otherwise) ":otherwise" else "") + ":children"].orEmpty()
    }
    fun references(scope: List<WorkflowAvailableVariable>): List<WorkflowReferenceChoice> = buildList {
        fun walk(v: WorkflowAvailableVariable, type: WorkflowType, path: List<String>, label: String, depth: Int) {
            val builtinReadOnly = v.variable.id.startsWith("builtin.") && !(v.variable.id == WorkflowSystem.PAGE && path.firstOrNull() in setOf("bubbles", "records") ||
                v.variable.type.kind == WorkflowDataKind.BUBBLE && path.firstOrNull() in setOf("source", "translation"))
            val bubbleReadOnly = v.variable.type.kind == WorkflowDataKind.BUBBLE && path.firstOrNull() !in setOf("source", "translation") ||
                v.variable.id == WorkflowSystem.PAGE && path.firstOrNull() == "bubbles" && path.size > 2 && path[2] !in setOf("source", "translation")
            add(WorkflowReferenceChoice(WorkflowRef(v.variable.id, path), label, type, v.readOnly || builtinReadOnly || bubbleReadOnly))
            if (depth < 8) {
                type.fields.forEach { (key, field) -> walk(v, field, path + key, "$label · ${WorkflowLabels.field(key)}", depth + 1) }
                if (type.kind == WorkflowDataKind.LIST) type.element?.let { walk(v, it, path + "0", "$label · 第一项", depth + 1) }
            }
        }
        scope.forEach { walk(it, it.variable.type, emptyList(), it.variable.name, 0) }
    }
    fun copyNode(node: WorkflowNode): WorkflowNode {
        require(node.kind !in WorkflowValidator.fixedKinds)
        val nodes = WorkflowProgram(rows = listOf(node)).allNodes()
        val variables = nodes.mapNotNull { it.variable }.associate { it.id to if(it.id == WorkflowSystem.PAGE && it.type == WorkflowSystem.pageType) it.id else UUID.randomUUID().toString() }
        fun ref(r: WorkflowRef) = r.copy(variableId = variables[r.variableId] ?: r.variableId)
        fun expr(e: WorkflowExpression): WorkflowExpression = when(e) {
            is WorkflowExpression.Ref -> e.copy(value = ref(e.value))
            is WorkflowExpression.Template -> e.copy(bindings = e.bindings.mapValues { ref(it.value) })
            is WorkflowExpression.Record -> e.copy(fields = e.fields.mapValues { expr(it.value) })
            else -> e
        }
        fun clone(n: WorkflowNode): WorkflowNode = n.copy(id = UUID.randomUUID().toString(), variable = n.variable?.let { it.copy(id = variables.getValue(it.id), name = it.name + "副本") },
            target = n.target?.let(::ref), inputs = n.inputs.mapValues { expr(it.value) }, collectTo = n.collectTo?.let(::ref), children = n.children.map(::clone), otherwise = n.otherwise.map(::clone))
        return clone(node)
    }
}
