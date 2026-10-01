package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.serialization.json.*

/** The editable flag is file metadata; the application has no flag setter. */
object WorkflowFileCodec {
    fun encode(value: TranslationWorkflow): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", "lmreader.workflow"); put("version", 1); put("editable", value.editable)
        put("name", value.name); put("description", value.description); put("parallelLimit", value.parallelLimit); put("retries", value.retries)
        put("program", WorkflowProgramCodec.json(value.program))
    })
    fun decode(text: String): TranslationWorkflow {
        require(text.toByteArray().size <= 1_000_000) { "工作流文件超过 1 MB" }
        val doc = Json.parseToJsonElement(text).jsonObject
        require(doc["format"]?.jsonPrimitive?.content == "lmreader.workflow" && doc["version"]?.jsonPrimitive?.intOrNull == 1) { "不是受支持的 LM-Reader 工作流文件" }
        val editable = doc["editable"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull ?: error("文件需要 editable 布尔标识")
        return TranslationWorkflow("import", 1, doc.getValue("name").jsonPrimitive.content,
            doc.getValue("description").jsonPrimitive.content, TranslationPageMode.BUBBLE,
            doc.getValue("parallelLimit").jsonPrimitive.int, doc.getValue("retries").jsonPrimitive.int,
            program = WorkflowModernization.apply(WorkflowProgramCodec.decode(doc.getValue("program").toString())), editable = editable)
    }
}
