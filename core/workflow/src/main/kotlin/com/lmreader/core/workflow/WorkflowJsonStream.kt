package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** Incremental JSON array reader. A child runs only after a complete item and its delimiter. */
class WorkflowJsonStream(private val type: WorkflowType, private val expected: WorkflowValue.ListValue? = null,
    private val expectedBubbleId: String? = null) {
    private val document = StringBuilder()
    private val item = StringBuilder()
    private var started = false
    private var ended = false
    private var waitingItem = true
    private var afterComma = false
    private var depth = 0
    private var quoted = false
    private var escaped = false
    private var pending = false
    private var readingHeader = false
    private var fenced = false
    private val header = StringBuilder()
    private val footer = StringBuilder()
    private val emitted = mutableListOf<WorkflowValue>()
    private val seen = mutableSetOf<String>()
    init { require(type.kind == WorkflowDataKind.LIST && type.element?.kind in setOf(WorkflowDataKind.RECORD, WorkflowDataKind.DICTIONARY)) }
    suspend fun append(text: String, accept: suspend (WorkflowValue, Int) -> Unit) {
        require(document.length + text.length <= 4_000_000) { "API 输出超过 4 MB" }
        document.append(text)
        for(c in text) {
            if(ended) {
                if(c.isWhitespace()) continue
                require(fenced) { "JSON 列表结束后有额外内容" }
                footer.append(c); require("```".startsWith(footer.toString())) { "JSON 围栏之后有额外内容" }; continue
            }
            if(!started) {
                if(readingHeader) {
                    header.append(c); require(header.length <= 32) { "JSON 围栏格式错误" }
                    if(c == '\n') {
                        require(Regex("^```(?:json)?[ \\t]*\\r?\\n$", RegexOption.IGNORE_CASE).matches(header)) { "JSON 围栏格式错误" }
                        readingHeader = false; fenced = true
                    }
                    continue
                }
                if(c.isWhitespace()) continue
                if(c == '`' && !fenced) { readingHeader = true; header.append(c); continue }
                require(c == '[') { "流式输出需要 JSON 列表" }; started = true; continue
            }
            if(pending) {
                if(c.isWhitespace()) continue
                require(c == ',' || c == ']') { "JSON 条目之后需要逗号或列表结束" }
                val value = WorkflowValueCodec.parseResponse(item.toString(), type.element!!)
                if(value is WorkflowValue.Record && "bubbleId" in value.fields) {
                    val id = (value.fields.getValue("bubbleId") as WorkflowValue.Text).value
                    require(seen.add(id)) { "API 气泡 ID 重复" }
                    expectedBubbleId?.let { require(id == it) { "API 气泡 ID 与请求不一致" } }
                    expected?.let { originals ->
                        val original = originals.items.firstOrNull { row ->
                            val fields = (row as WorkflowValue.Record).fields
                            ((fields["bubbleId"] ?: fields["id"]) as? WorkflowValue.Text)?.value == id
                        } ?: error("API 返回额外气泡 ID")
                        WorkflowValueCodec.requireMatchingTranslations(WorkflowValue.ListValue(listOf(value)), WorkflowValue.ListValue(listOf(original)))
                    }
                }
                require(emitted.size < 10000)
                emitted += value
                accept(value, emitted.size)
                item.clear(); pending = false; waitingItem = true; afterComma = c == ','
                if(c == ']') ended = true
                continue
            }
            if(waitingItem) {
                if(c.isWhitespace()) continue
                if(c == ']') { require(!afterComma) { "JSON 列表不能有尾随逗号" }; ended = true; continue }
                require(c == '{') { "流式条目必须是结构化对象" }
                waitingItem = false; depth = 1; item.append(c); continue
            }
            item.append(c)
            if(quoted) {
                if(escaped) escaped = false else if(c == '\\') escaped = true else if(c == '"') quoted = false
            } else when(c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if(depth == 0) pending = true; require(depth >= 0) }
            }
        }
    }
    fun finish(): WorkflowValue.ListValue {
        require(started && ended && !pending && waitingItem) { "流式 JSON 列表未完整结束" }
        require(!fenced || footer.toString() == "```") { "JSON 围栏未完整结束" }
        val parsed = WorkflowValueCodec.parseResponse(document.toString(), type, expectedBubbleId, expected) as WorkflowValue.ListValue
        require(parsed.items == emitted) { "流式条目与最终结果不一致" }
        return parsed
    }
}
