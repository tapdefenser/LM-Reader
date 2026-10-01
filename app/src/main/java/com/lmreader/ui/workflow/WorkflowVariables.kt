package com.lmreader.ui.workflow

import androidx.compose.material3.MaterialTheme
import com.lmreader.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*

internal fun typedLabel(choice: WorkflowReferenceChoice) = "${WorkflowLabels.type(choice.type)} · ${choice.label}"

@Composable
internal fun variableColor(type: WorkflowType): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when(type.kind) {
        WorkflowDataKind.TEXT -> if(dark) Color(0xFF92C5FF) else Color(0xFF165CA8)
        WorkflowDataKind.NUMBER -> if(dark) Color(0xFFFFBF82) else Color(0xFF934700)
        WorkflowDataKind.BOOLEAN -> if(dark) Color(0xFFFF99AC) else Color(0xFFA32B48)
        WorkflowDataKind.IMAGE -> if(dark) Color(0xFFB9AAFF) else Color(0xFF6742A8)
        WorkflowDataKind.BUBBLE -> if(dark) Color(0xFFF0A5FA) else Color(0xFF8C368D)
        WorkflowDataKind.CONTEXT -> if(dark) Color(0xFF9ADDD4) else Color(0xFF006C61)
        WorkflowDataKind.LIST -> if(dark) Color(0xFFA6D98F) else Color(0xFF396B22)
        WorkflowDataKind.RECORD -> if(dark) Color(0xFFE4CE89) else Color(0xFF79600E)
        WorkflowDataKind.DICTIONARY -> if(dark) Color(0xFF8DDBEA) else Color(0xFF006A7C)
    }
}

@Composable
internal fun TypedVariable(choice: WorkflowReferenceChoice) {
    Text(typedLabel(choice), color = variableColor(choice.type), style = MaterialTheme.typography.labelSmall)
}

internal fun expressionLabel(expression: WorkflowExpression, refs: List<WorkflowReferenceChoice>): String = when(expression) {
    is WorkflowExpression.Ref -> refs.firstOrNull { it.ref == expression.value }?.let(::typedLabel) ?: "引用 · 不可用变量"
    is WorkflowExpression.Text -> "文本 · ${expression.value.take(40).ifBlank { "空文本" }}"
    is WorkflowExpression.Template -> "文本 · 提示词模板"
    is WorkflowExpression.Number -> "数字 · ${expression.value}"
    is WorkflowExpression.Boolean -> "布尔 · ${expression.value}"
    is WorkflowExpression.Empty -> "${WorkflowLabels.type(expression.type)} · 初始值"
    is WorkflowExpression.Record -> "记录 · ${expression.fields.keys.joinToString()}"
}
