package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkflowTest {
    @Test fun `typed destinations reject an engine returning the wrong value`() = runTest {
        val host = object : Host(pageCount = 1) {
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue =
                if(kind == WorkflowKind.OCR) WorkflowValue.Number(1.0) else super.request(kind, inputs, frame)
        }
        val failure = assertFailsWith<WorkflowExecutionFailure> { WorkflowRuntime().execute(WorkflowTemplates.localMachine(), host) }
        assertEquals("ocr", failure.rowId)
        assertTrue(host.saved.isEmpty())
    }
    @Test fun `context keeps departed loop values while glossary remains live`() = runTest {
        val context = WorkflowVariable("context", "上下文", WorkflowType.CONTEXT)
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("new-context", WorkflowKind.DECLARE, variable = context))
        program = WorkflowEditing.update(program, "bubbles") { it.copy(mode = WorkflowMode.SYNC, children = listOf(
            WorkflowNode("example-message", WorkflowKind.MESSAGE, target = WorkflowRef(context.id), inputs = mapOf("user" to WorkflowExpression.Template("\${ID}：\${字典}",
                mapOf("ID" to WorkflowRef(WorkflowSystem.BUBBLE, listOf("id")), "字典" to WorkflowRef(WorkflowSystem.GLOSSARY))))))) }
        val variable = WorkflowVariable("api-result", "结果", WorkflowType.TEXT)
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 3), WorkflowNode("new-result", WorkflowKind.DECLARE, variable = variable))
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 4), WorkflowNode("request", WorkflowKind.API, target = WorkflowRef(variable.id), inputs = mapOf(
            "profile" to WorkflowExpression.Text("fixture"), "context" to WorkflowSystem.ref(context.id), "prompt" to WorkflowExpression.Text("summary"))))
        val calls = mutableListOf<WorkflowApiCall>()
        val host = object : Host(pageCount = 1) {
            override fun step(node: WorkflowNode, frame: WorkflowFrame) { if(node.id == "new-result") dictionary["Akira"] = "阿基拉" }
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue { calls += call; return text("ok") }
        }
        WorkflowRuntime().execute(program, host)
        assertEquals(4, calls.single().messages.size)
        assertTrue(calls.single().messages.take(3).all { it.content.contains("阿基拉") })
        assertTrue(calls.single().messages[0].content.startsWith("7:1:1"))
    }
    @Test fun `priority admission waits before consuming the shared page permit`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val host = object : Host(listOf("7", "39"), 2) {
            override suspend fun beforePage(frame: WorkflowFrame) {
                if(frame.identity(WorkflowSystem.PAGE) == "7:1") delay(100) else ready.await()
            }
            override suspend fun closePage(frame: WorkflowFrame) { if(frame.identity(WorkflowSystem.PAGE) == "7:1") ready.complete(Unit) }
        }
        WorkflowRuntime(2).execute(WorkflowTemplates.localMachine(), host)
        assertEquals(4, host.saved.size)
        assertEquals("7:1", host.saved.first())
    }
    private fun text(value: String) = WorkflowValue.Text(value)
    private fun record(vararg fields: Pair<String, WorkflowValue>) = WorkflowValue.Record(fields.toMap())
    private open inner class Host(private val chapterIds: List<String> = listOf("7"), private val pageCount: Int = 3) : WorkflowRuntimeHost {
        override val manga = record("id" to text("manga"), "name" to text("fixture"))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        val dictionary = linkedMapOf<String, String>(); val dictionaryLock = Mutex()
        val requests = mutableListOf<Pair<String, String>>()
        val active = AtomicInteger(); val peak = AtomicInteger(); val saved = mutableListOf<String>()
        override suspend fun chapters() = chapterIds.map { record("id" to text(it), "name" to text(it), "index" to WorkflowValue.Number(it.toDouble()), "records" to WorkflowValue.ListValue(emptyList())) }
        override suspend fun pages(chapter: WorkflowValue.Record) = (1..pageCount).map { index ->
            val id = (chapter.fields.getValue("id") as WorkflowValue.Text).value + ":" + index
            record("id" to text(id), "name" to text(id), "number" to WorkflowValue.Number(index.toDouble()),
                "image" to WorkflowValue.Image(id), "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList()))
        }
        override suspend fun glossary() = dictionaryLock.withLock { dictionary.toMap() }
        override suspend fun mergeGlossary(entries: Map<String, String>) { dictionaryLock.withLock { entries.forEach { (k,v) -> dictionary.putIfAbsent(k,v) } } }
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
            val page = frame.identity(WorkflowSystem.PAGE)!!
            return when(kind) {
                WorkflowKind.SEG -> WorkflowValue.ListValue((1..3).map { index -> record("id" to text("$page:$index"), "index" to WorkflowValue.Number(index.toDouble()),
                    "image" to WorkflowValue.Image("$page:$index"), "source" to text("source-$index"), "translation" to text(""), "confidence" to WorkflowValue.Number(1.0), "kind" to text("BUBBLE")) })
                WorkflowKind.OCR -> {
                    val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                    try { delay(if(frame.identity(WorkflowSystem.CHAPTER) == "7") 50 else 1); text("Akira") }
                    finally { active.decrementAndGet() }
                }
                WorkflowKind.TRANSLATE -> { val input = (inputs.getValue("text") as WorkflowValue.Text).value; requests += frame.identity(WorkflowSystem.CHAPTER)!! to input; text("译文") }
                else -> error("unused")
            }
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = error("unused")
        override suspend fun publishPage(frame: WorkflowFrame) { saved += frame.identity(WorkflowSystem.PAGE)!! }
    }
    @Test fun `templates and codec retain types scopes prompts and stable identities`() {
        for(program in listOf(WorkflowTemplates.blank(), WorkflowTemplates.localMachine(), WorkflowTemplates.visionApi("fixture"), WorkflowTemplates.visionWithGlossary("fixture"))) {
            assertEquals(program, WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program)))
            if(program.uses(WorkflowKind.SEG)) assertEquals(emptyList(), WorkflowValidator.validate(program).issues)
        }
        assertFalse(WorkflowValidator.validate(WorkflowTemplates.visionApi()).valid)
    }
    @Test fun `dragging engine outside its page is rejected and legal outer moves preserve references`() {
        val local = WorkflowTemplates.localMachine()
        assertFailsWith<IllegalArgumentException> { WorkflowEditing.move(local, "seg", WorkflowPosition("chapters", 0)) }
        val variable = WorkflowVariable("v", "文本", WorkflowType.TEXT)
        val declared = WorkflowNode("declare", WorkflowKind.DECLARE, variable = variable)
        val withVariable = WorkflowEditing.insert(local, WorkflowPosition("pages", 0), declared)
        val moved = WorkflowEditing.move(withVariable, "declare", WorkflowPosition("chapters", 0))
        assertTrue(WorkflowValidator.validate(moved).valid)
        assertEquals(variable.id, moved.allNodes().first { it.id == declared.id }.variable?.id)
        assertFailsWith<IllegalArgumentException> { WorkflowEditing.move(local, "pages", WorkflowPosition("manga", 0)) }
    }
    @Test fun `async parent mutation is rejected while bubble field updates remain legal`() {
        val variable = WorkflowVariable("shared", "共享文本", WorkflowType.TEXT)
        var program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), WorkflowNode("shared-declare", WorkflowKind.DECLARE, variable = variable))
        program = WorkflowEditing.insert(program, WorkflowPosition("bubbles", 0), WorkflowNode("bad-append", WorkflowKind.APPEND, target = WorkflowRef(variable.id), inputs = mapOf("value" to WorkflowExpression.Text("x"))))
        assertTrue(WorkflowValidator.validate(program).issues.any { it.nodeId == "bad-append" && it.message.contains("父层") })
        assertTrue(WorkflowValidator.validate(WorkflowTemplates.localMachine()).valid)
    }
    @Test fun `sync loop is sequential and async pages are bounded`() = runTest {
        var sync = WorkflowTemplates.localMachine()
        for(id in listOf("chapters", "pages", "bubbles")) sync = WorkflowEditing.update(sync, id) { it.copy(mode = WorkflowMode.SYNC) }
        val serial = Host(); WorkflowRuntime(2).execute(sync, serial)
        assertEquals(1, serial.peak.get()); assertEquals(listOf("7:1", "7:2", "7:3"), serial.saved)
        val concurrent = Host(); val async = WorkflowEditing.update(sync, "pages") { it.copy(mode = WorkflowMode.ASYNC) }
        WorkflowRuntime(2).execute(async, concurrent)
        assertEquals(2, concurrent.peak.get()); assertEquals(3, concurrent.saved.size)
    }
    @Test fun `chapter 39 addition is immediately visible to chapter 7 and later additions never replace it`() = runTest {
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.update(program, "translate") { it.copy(inputs = it.inputs + ("text" to WorkflowExpression.Template("\${字典}", mapOf("字典" to WorkflowRef(WorkflowSystem.GLOSSARY))))) }
        program = WorkflowEditing.insert(program, WorkflowPosition("chapters", 1), WorkflowNode("add-name", WorkflowKind.MERGE_GLOSSARY,
            inputs = mapOf("items" to WorkflowExpression.Ref(WorkflowRef("names")))))
        program = WorkflowEditing.insert(program, WorkflowPosition("chapters", 1), WorkflowNode("declare-names", WorkflowKind.DECLARE,
            variable = WorkflowVariable("names", "提取结果", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))))
        val host = object : Host(listOf("7", "39"), 1) {
            override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) { mergeGlossary(mapOf("Akira" to if(frame.identity(WorkflowSystem.CHAPTER) == "39") "阿基拉" else "后来的译名")) }
        }
        WorkflowRuntime(8).execute(program, host)
        assertEquals("阿基拉", host.dictionary["Akira"])
        assertTrue(host.requests.filter { it.first == "7" }.all { it.second.contains("阿基拉") })
        assertTrue(host.requests.filter { it.first == "39" }.all { it.second == "{}" })
    }
    @Test fun `collecting async returns preserves input order without shared append`() = runTest {
        val output = WorkflowVariable("results", "结果", WorkflowType.list(WorkflowType.TEXT))
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 1), WorkflowNode("new-results", WorkflowKind.DECLARE, variable = output))
        program = WorkflowEditing.update(program, "bubbles") { it.copy(collectTo = WorkflowRef(output.id), children = listOf(
            WorkflowNode("return", WorkflowKind.RETURN, inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.BUBBLE, "source"))))) }
        val seen = mutableListOf<List<String>>()
        val host = object : Host(pageCount = 1) {
            override fun step(node: WorkflowNode, frame: WorkflowFrame) { }
            override suspend fun publishPage(frame: WorkflowFrame) { seen += (frame.read(WorkflowRef(output.id)) as WorkflowValue.ListValue).items.map { (it as WorkflowValue.Text).value } }
        }
        WorkflowRuntime(3).execute(program, host)
        assertEquals(listOf(listOf("source-1", "source-2", "source-3")), seen)
    }
    @Test fun `structured API data requires exact fields and bubble identity`() {
        val type = WorkflowType.list(WorkflowType.TRANSLATION)
        val valid = "[{\"bubbleId\":\"b\",\"source\":\"Akira\",\"translation\":\"阿基拉\"}]"
        assertEquals(WorkflowValueCodec.parseResponse(valid, type, "b"), WorkflowValueCodec.parseResponse("```json\n$valid\n```", type, "b"))
        for(raw in listOf(valid.replace("\"b\"", "\"other\""), "[]", valid.replace("\"source\":\"Akira\",", ""), valid.replace("\"source\":", "\"extra\":true,\"source\":"), valid.dropLast(1))) {
            assertFails { WorkflowValueCodec.parseResponse(raw, type, "b") }
        }
        assertFails { WorkflowValueCodec.display(WorkflowValue.Image("image")) }
    }
    @Test fun `copy remaps local declarations and all their references`() {
        val variable = WorkflowVariable("v", "文本", WorkflowType.TEXT)
        val node = WorkflowNode("each", WorkflowKind.EACH, variable = WorkflowVariable("item", "项", WorkflowType.TEXT),
            children = listOf(WorkflowNode("new", WorkflowKind.DECLARE, variable = variable), WorkflowNode("set", WorkflowKind.SET, target = WorkflowRef(variable.id), inputs = mapOf("value" to WorkflowSystem.ref(variable.id)))))
        val copy = WorkflowEditing.copyNode(node)
        val id = copy.children.first().variable!!.id
        assertNotEquals(variable.id, id); assertEquals(id, copy.children.last().target!!.variableId)
        assertEquals(id, (copy.children.last().inputs.getValue("value") as WorkflowExpression.Ref).value.variableId)
    }
}
