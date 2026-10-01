package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class WorkflowReferenceTest {
    @Test fun orderBackfillFiltersWholeMangaRecordsToCurrentPageAndKeepsReplyOrder() = runTest {
        val host = object : Host() {
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue =
                WorkflowValue.ListValue(call.expectedItems!!.items.map { row ->
                    val record = row as WorkflowValue.Record
                    record.copy(fields = record.fields + ("translation" to text("按序译文")))
                })
        }
        val program = WorkflowEditing.update(WorkflowReferenceTemplates.fullManga("fixture"), "apply-manga-page") { it.copy(kind = WorkflowKind.APPLY_ORDER, inputs = it.inputs + ("index" to WorkflowExpression.Number(1.0))) }
        WorkflowRuntime().execute(program, host)
        assertEquals(4, host.saved.size)
        assertTrue(host.saved.all { page -> page.items.size == 2 && page.items.all { ((it as WorkflowValue.Record).fields["translation"] as WorkflowValue.Text).value == "按序译文" } })
    }
    @Test fun `all references are editable programs with one fixed scaffold`() {
        val programs = listOf(WorkflowReferenceTemplates.standard("fixture"), WorkflowReferenceTemplates.fullManga("fixture"), WorkflowReferenceTemplates.vision("fixture"))
        programs.forEach { p ->
            assertTrue(WorkflowValidator.validate(p).valid, WorkflowValidator.validate(p).issues.toString())
            assertEquals(p, WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(p)))
            WorkflowValidator.fixedKinds.forEach { kind -> assertEquals(1, p.allNodes().count { it.kind == kind }) }
            assertFalse(p.uses(WorkflowKind.TRANSLATE))
            assertTrue(WorkflowSource.render(p).contains("请求："))
        }
        val full = programs[1]
        assertEquals(1, full.allNodes().count { it.kind == WorkflowKind.API })
        assertFalse(full.uses(WorkflowKind.MERGE_GLOSSARY))
        assertTrue(WorkflowSource.render(full).contains("整漫画一次请求"))
        assertTrue(WorkflowEditing.scope(full, WorkflowPosition("prepare-manga", 0)).any { it.variable.id == WorkflowSystem.PAGE })
        val rebound = WorkflowReferenceTemplates.bindApi(programs[2], "other")
        assertTrue(rebound.allNodes().filter { it.kind == WorkflowKind.API }.all { it.inputs["profile"] == WorkflowExpression.Text("other") })
    }
    @Test fun `standard collects bubble OCR before one API per page and remaps reversed replies`() = runTest {
        val host = Host()
        WorkflowRuntime(3).execute(WorkflowReferenceTemplates.standard("fixture"), host)
        assertEquals(4, host.calls.size)
        assertTrue(host.calls.all { !it.wholeManga && it.images.isEmpty() && it.expectedItems!!.items.size == 2 })
        assertEquals(4, host.saved.size)
        assertTrue(host.saved.flatMap { it.items }.all { (it as WorkflowValue.Record).fields["translation"] == text("译文-" + (it.fields["id"] as WorkflowValue.Text).value) })
    }
    @Test fun `full manga joins preprocessing then performs one API and publishes without repeating OCR`() = runTest {
        val host = Host()
        WorkflowRuntime(3).execute(WorkflowReferenceTemplates.fullManga("fixture"), host)
        assertEquals(1, host.calls.size)
        assertTrue(host.calls.single().wholeManga)
        assertEquals(8, host.calls.single().expectedItems!!.items.size)
        assertEquals(4, host.prepared.size)
        assertEquals(4, host.saved.size)
        assertEquals(8, host.ocrCount)
        assertTrue(host.saved.flatMap { it.items }.all { (it as WorkflowValue.Record).fields["translation"] == text("译文-" + (it.fields["id"] as WorkflowValue.Text).value) })
    }
    @Test fun `full failure cannot publish partial results or silently split the request`() = runTest {
        val host = object : Host() {
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                super.api(call, frame)
                return WorkflowValue.ListValue(emptyList())
            }
        }
        assertFailsWith<WorkflowExecutionFailure> { WorkflowRuntime(3).execute(WorkflowReferenceTemplates.fullManga("fixture"), host) }
        assertEquals(1, host.calls.size); assertTrue(host.saved.isEmpty())
    }
    @Test fun `empty manga bubble list needs no API but still publishes blank pages`() = runTest {
        val host = object : Host() {
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue =
                if(kind == WorkflowKind.SEG) WorkflowValue.ListValue(emptyList()) else super.request(kind, inputs, frame)
        }
        WorkflowRuntime(3).execute(WorkflowReferenceTemplates.fullManga("fixture"), host)
        assertTrue(host.calls.isEmpty()); assertEquals(4, host.saved.size)
    }
    @Test fun `batch response rejects changed sources duplicate missing extra and wrong page identities`() {
        val originals = WorkflowValue.ListValue(listOf(record("bubbleId" to text("p:b"), "source" to text("AKIRA"), "translation" to text(""), "pageId" to text("p"), "pageNumber" to WorkflowValue.Number(1.0))))
        val correct = (originals.items.single() as WorkflowValue.Record).copy(fields = (originals.items.single() as WorkflowValue.Record).fields + ("translation" to text("阿基拉")))
        WorkflowValueCodec.requireMatchingTranslations(WorkflowValue.ListValue(listOf(correct)), originals)
        listOf("source" to text("Akira"), "pageId" to text("other"), "pageNumber" to WorkflowValue.Number(2.0), "bubbleId" to text("other:b")).forEach { (key, value) ->
            assertFails { WorkflowValueCodec.requireMatchingTranslations(WorkflowValue.ListValue(listOf(correct.copy(fields = correct.fields + (key to value)))), originals) }
        }
        assertFails { WorkflowValueCodec.requireMatchingTranslations(WorkflowValue.ListValue(listOf(correct, correct)), originals) }
        assertFails { WorkflowValueCodec.requireMatchingTranslations(WorkflowValue.ListValue(emptyList()), originals) }
    }
    private open class Host : WorkflowRuntimeHost {
        override val manga = record("id" to text("m"), "name" to text("generated"))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        val prepared = mutableMapOf<String, WorkflowValue.Record>()
        val saved = mutableListOf<WorkflowValue.ListValue>(); val calls = mutableListOf<WorkflowApiCall>(); var ocrCount = 0
        override suspend fun chapters() = listOf("7", "39").map { record("id" to text(it), "name" to text(it), "index" to WorkflowValue.Number(it.toDouble()), "records" to WorkflowValue.ListValue(emptyList())) }
        override suspend fun pages(chapter: WorkflowValue.Record) = (1..2).map { i ->
            val id = (chapter.fields["id"] as WorkflowValue.Text).value + ":$i"
            record("id" to text(id), "name" to text(id), "number" to WorkflowValue.Number(i.toDouble()), "image" to WorkflowValue.Image(id), "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList()))
        }
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = error("Full template must not extract glossary")
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue = when(kind) {
            WorkflowKind.SEG -> WorkflowValue.ListValue((1..2).map { i ->
                val id = frame.identity(WorkflowSystem.PAGE) + ":$i"
                record("id" to text(id), "index" to WorkflowValue.Number(i.toDouble()), "image" to WorkflowValue.Image(id), "source" to text(""), "translation" to text(""), "confidence" to WorkflowValue.Number(1.0), "kind" to text("BUBBLE"))
            })
            WorkflowKind.OCR -> { delay(1); ocrCount++; text("original-" + (inputs.getValue("image") as WorkflowValue.Image).key) }
            else -> error("Unexpected engine")
        }
        override suspend fun admitPage(frame: WorkflowFrame, job: Job): WorkflowPageAdmission {
            prepared[frame.identity(WorkflowSystem.PAGE)]?.let { frame.define(WorkflowSystem.PAGE, it, WorkflowSystem.pageType) }
            return WorkflowPageAdmission.RUN
        }
        override suspend fun completePreparationPage(frame: WorkflowFrame): WorkflowValue.ListValue {
            val bubbles = frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue
            val records = WorkflowValue.ListValue(bubbles.items.map { row ->
                val b = row as WorkflowValue.Record
                record("bubbleId" to b.fields.getValue("id"), "source" to b.fields.getValue("source"), "translation" to text(""), "pageId" to text(frame.identity(WorkflowSystem.PAGE)!!), "pageNumber" to frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("number"))))
            })
            frame.write(WorkflowRef(WorkflowSystem.PAGE, listOf("records")), records)
            prepared[frame.identity(WorkflowSystem.PAGE)!!] = frame.read(WorkflowRef(WorkflowSystem.PAGE)) as WorkflowValue.Record
            return records
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
            if(call.wholeManga) { assertTrue(saved.isEmpty()); assertEquals(4, prepared.size) }
            calls += call
            return WorkflowValue.ListValue(call.expectedItems!!.items.reversed().map { row ->
                val r = row as WorkflowValue.Record
                r.copy(fields = r.fields + ("translation" to text("译文-" + (r.fields["bubbleId"] as WorkflowValue.Text).value)))
            })
        }
        override suspend fun publishPage(frame: WorkflowFrame) { saved += frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue }
    }
    companion object {
        private fun text(s: String) = WorkflowValue.Text(s)
        private fun record(vararg values: Pair<String, WorkflowValue>) = WorkflowValue.Record(values.toMap())
    }
}
