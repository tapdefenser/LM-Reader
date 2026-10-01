package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class WorkflowRefinementTest {
    private val listType = WorkflowType.list(WorkflowType.TRANSLATION)
    @Test fun fileRoundtripKeepsExternalFixedFlagAndModernizesOldRows() {
        val fixed = TranslationWorkflow.STANDARD_API.copy(editable = false)
        val text = WorkflowFileCodec.encode(fixed)
        val imported = WorkflowFileCodec.decode(text)
        assertFalse(imported.editable); assertFalse(imported.builtIn)
        assertEquals(fixed.program, imported.program)
        assertFails { WorkflowFileCodec.decode(text.replace("\"editable\": false", "\"editable\": \"false\"")) }
        assertFails { WorkflowFileCodec.decode(text.replace("lmreader.workflow", "other")) }
        val full = WorkflowReferenceTemplates.fullManga("fixture")
        assertFalse(full.uses(WorkflowKind.PREPARE_MANGA)); assertFalse(full.uses(WorkflowKind.RETURN))
        assertTrue(WorkflowValidator.validate(full).valid)
        assertTrue(WorkflowTemplates.blank().allNodes().single { it.kind == WorkflowKind.SEG }.inputs.containsKey("image"))
    }
    @Test fun completeJsonItemsRunBeforeTheArrayEndsAndEscapesSurviveArbitraryChunks() = runTest {
        val parser = WorkflowJsonStream(listType)
        val items = mutableListOf<WorkflowValue>()
        val first = """{"bubbleId":"p:1","source":"quote \" { }","translation":"一😀"}"""
        for(c in ("[" + first)) parser.append(c.toString()) { v, _ -> items += v }
        assertTrue(items.isEmpty())
        parser.append(",") { v, index -> assertEquals(1, index); items += v }
        assertEquals(1, items.size)
        val rest = """{"bubbleId":"p:2","source":"two","translation":"二"}]"""
        for(c in rest) parser.append(c.toString()) { v, _ -> items += v }
        assertEquals(items, parser.finish().items)
    }
    @Test fun invalidPartialOrDuplicateJsonIsRejected() = runTest {
        val item = """{"bubbleId":"p:1","source":"x","translation":"一"}"""
        val partial = WorkflowJsonStream(listType)
        partial.append("[$item,") { _, _ -> }
        assertFails { partial.finish() }
        val duplicate = WorkflowJsonStream(listType)
        assertFails { duplicate.append("[$item,$item]") { _, _ -> } }
        val trailing = WorkflowJsonStream(listType)
        assertFails { trailing.append("[$item,]") { _, _ -> } }
        val badType = WorkflowJsonStream(listType)
        assertFails { badType.append("[\"text\"]") { _, _ -> } }
        val missingField = WorkflowJsonStream(listType)
        assertFails { missingField.append("[{\"bubbleId\":\"p:1\"}]") { _, _ -> } }
    }
    @Test fun jsonFenceCanArriveInTinyFragmentsWithoutBecomingAnItem() = runTest {
        val parser = WorkflowJsonStream(listType)
        var items = 0
        val raw = "```json\n" + """[{"bubbleId":"p:1","source":"x","translation":"一"}]""" + "\n```"
        for(c in raw) parser.append(c.toString()) { _, _ -> items++ }
        assertEquals(1, items); assertEquals(1, parser.finish().items.size)
        val incomplete = WorkflowJsonStream(listType)
        incomplete.append(raw.dropLast(1)) { _, _ -> }
        assertFails { incomplete.finish() }
    }
    @Test fun streamValidatesEachSourceAndIdentityBeforeSideEffects() = runTest {
        val originals = WorkflowValue.ListValue(listOf(record("bubbleId" to text("p:1"), "source" to text("known"), "translation" to text(""))))
        val parser = WorkflowJsonStream(listType, originals)
        var called = false
        assertFails { parser.append("""[{"bubbleId":"p:1","source":"changed","translation":"一"}]""") { _, _ -> called = true } }
        assertFalse(called)
    }
    @Test fun streamScopeSupportsPerItemIdAndOrderBackfill() = runTest {
        for(order in listOf(false, true)) {
            val type = if(order) WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("translation" to WorkflowType.TEXT)) else WorkflowType.TRANSLATION
            val list = WorkflowVariable("out", "收到的译文", WorkflowType.list(type))
            val iterator = WorkflowVariable("item", "当前条目", type)
            val apply = WorkflowNode("apply", if(order) WorkflowKind.APPLY_ORDER else WorkflowKind.APPLY_TRANSLATIONS,
                inputs = mapOf("items" to WorkflowSystem.ref(iterator.id)) + if(order) mapOf("index" to WorkflowSystem.ref(WorkflowSystem.STREAM_INDEX)) else emptyMap())
            val stream = WorkflowNode("stream", WorkflowKind.API_STREAM, variable = iterator, target = WorkflowRef(list.id), resultType = list.type,
                inputs = mapOf("profile" to WorkflowExpression.Text("fixture"), "prompt" to WorkflowExpression.Template("translate")),
                children = listOf(apply, WorkflowNode("modify-item", WorkflowKind.SET, target = WorkflowRef(iterator.id, listOf("translation")), inputs = mapOf("value" to WorkflowExpression.Text("内层修改")))))
            val program = WorkflowEditing.update(WorkflowTemplates.blank(), "pages") { it.copy(children = it.children + listOf(WorkflowNode("declare", WorkflowKind.DECLARE, variable = list), stream)) }
            val validation = WorkflowValidator.validate(program)
            assertTrue(validation.valid, validation.issues.toString())
            assertTrue(validation.scopes["apply"]!!.any { it.variable.id == WorkflowSystem.STREAM_INDEX })
            val host = Host(order)
            WorkflowRuntime().execute(program, host)
            assertEquals(2, host.previews.size)
            assertEquals(listOf("一", "二"), host.saved)
            assertEquals(listOf("内层修改", "内层修改"), host.output)
            assertEquals(listOf("一", ""), host.previews.first())
            val roundtrip = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
            assertEquals(program, roundtrip)
            assertEquals(WorkflowExpression.Text("replacement"), WorkflowReferenceTemplates.bindApi(program, "replacement").allNodes().single { it.id == "stream" }.inputs["profile"])
            assertTrue(WorkflowSource.render(program).contains("API请求－流式输出"))
        }
    }
    @Test fun streamRequiresStructuredListAndStableItemType() {
        val base = WorkflowTemplates.localMachine()
        val bad = WorkflowNode("stream", WorkflowKind.API_STREAM, variable = WorkflowVariable("item", "条目", WorkflowType.TEXT),
            target = WorkflowRef(WorkflowSystem.BUBBLE, listOf("translation")), resultType = WorkflowType.TEXT,
            inputs = mapOf("profile" to WorkflowExpression.Text("fixture"), "prompt" to WorkflowExpression.Template("")))
        val program = WorkflowEditing.insert(base, WorkflowPosition("bubbles", 2), bad)
        assertFalse(WorkflowValidator.validate(program).valid)
    }
    private class Host(val order: Boolean) : WorkflowRuntimeHost {
        override val manga = record("id" to text("m"), "name" to text("fixture"))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        val previews = mutableListOf<List<String>>(); var saved = emptyList<String>(); var output = emptyList<String>()
        override suspend fun chapters() = listOf(record("id" to text("c"), "name" to text("c"), "index" to WorkflowValue.Number(1.0), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun pages(chapter: WorkflowValue.Record) = listOf(record("id" to text("p"), "name" to text("p"), "number" to WorkflowValue.Number(1.0), "image" to WorkflowValue.Image("p"), "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame) = WorkflowValue.ListValue((1..2).map { i ->
            record("id" to text("p:$i"), "index" to WorkflowValue.Number(i.toDouble()), "image" to WorkflowValue.Image("b$i"), "source" to text(""), "translation" to text(""), "confidence" to WorkflowValue.Number(1.0), "kind" to text("BUBBLE"))
        })
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = error("Must stream")
        override suspend fun apiStream(call: WorkflowApiCall, frame: WorkflowFrame, item: suspend (WorkflowValue, Int) -> Unit): WorkflowValue.ListValue {
            val parser = WorkflowJsonStream(call.resultType)
            val first = if(order) """{"translation":"一"}""" else """{"bubbleId":"p:1","source":"one","translation":"一"}"""
            val second = if(order) """{"translation":"二"}""" else """{"bubbleId":"p:2","source":"two","translation":"二"}"""
            parser.append("[$first,", item)
            assertEquals(1, previews.size)
            parser.append("$second]", item)
            return parser.finish()
        }
        private suspend fun translations(frame: WorkflowFrame) = (frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue).items.map { ((it as WorkflowValue.Record).fields.getValue("translation") as WorkflowValue.Text).value }
        override suspend fun previewPage(frame: WorkflowFrame) { previews += translations(frame) }
        override suspend fun publishPage(frame: WorkflowFrame) {
            saved = translations(frame)
            output = (frame.read(WorkflowRef("out")) as WorkflowValue.ListValue).items.map { ((it as WorkflowValue.Record).fields["translation"] as WorkflowValue.Text).value }
        }
    }
    companion object {
        private fun text(s: String) = WorkflowValue.Text(s)
        private fun record(vararg fields: Pair<String, WorkflowValue>) = WorkflowValue.Record(fields.toMap())
    }
}
