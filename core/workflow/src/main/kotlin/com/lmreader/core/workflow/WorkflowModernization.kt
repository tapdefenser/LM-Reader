package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** Saved queue snapshots remain executable; editable files use the smaller current vocabulary. */
object WorkflowModernization {
    fun apply(program: WorkflowProgram): WorkflowProgram {
        fun row(n: WorkflowNode): WorkflowNode {
            var changed = n.copy(children = n.children.map(::row), otherwise = n.otherwise.map(::row))
            if(n.kind == WorkflowKind.PREPARE_MANGA) changed = changed.copy(kind = WorkflowKind.EACH,
                label = "识别漫画各页", variable = WorkflowVariable(WorkflowSystem.PAGE, "本页", WorkflowSystem.pageType), target = null,
                collectTo = n.target, inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.ALL_PAGES),
                    "publish" to WorkflowExpression.Boolean(false), "flatten" to WorkflowExpression.Boolean(true), "collectValue" to WorkflowSystem.ref(WorkflowSystem.PAGE, "records")))
            if(changed.kind == WorkflowKind.EACH && changed.children.lastOrNull()?.kind == WorkflowKind.RETURN) {
                val value = changed.children.last().inputs.getValue("value")
                changed = changed.copy(children = changed.children.dropLast(1), inputs = changed.inputs + ("collectValue" to value))
            }
            return changed
        }
        return program.copy(rows = program.rows.map(::row))
    }
}
