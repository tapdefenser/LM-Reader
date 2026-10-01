package com.lmreader.ui.workflow

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkflowHostIntegrationTest {
    @Test fun realStructuredStreamBackfillsBeforeRequestFinishesAndRecordsMangaLog() = runBlocking<Unit> {
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Generated streaming test", "http://192.168.137.1:1234/v1",
            apiKey = "1", model = "gemma4-12b-qat-uncensored-hauhaucs-balanced@q4_k_m", parallelLimit = 2, retryCount = 0,
            parameters = AiParameters(temperature = .1, maxTokens = 4096))
        container.apiProfiles.save(profile)
        val chapters = generatedChapters(1, AtomicInteger()).take(1)
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, "", BubbleRenderSettings(), .35f, listOf(profile.copy(apiKey = "")))
        var streamEnded = false; var previews = 0
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated streaming fixture", chapters, settings, cache, reusePages = false) {
            override suspend fun apiStream(call: WorkflowApiCall, frame: WorkflowFrame, item: suspend (WorkflowValue, Int) -> Unit): WorkflowValue.ListValue =
                super.apiStream(call, frame, item).also { streamEnded = true }
            override suspend fun pagePreviewed(frame: WorkflowFrame, saved: com.lmreader.ui.reader.translation.ReaderPageTranslation) {
                assertFalse(streamEnded)
                assertFalse(container.localPageTranslator.artifacts.has(saved.pageId))
                assertTrue(saved.regions.isNotEmpty()); previews++
            }
        }
        var program = WorkflowReferenceTemplates.standard(profileId)
        program = WorkflowEditing.update(program, "pages") { page -> page.copy(children = page.children.filterNot { it.kind == WorkflowKind.APPLY_TRANSLATIONS }.map { node ->
            if(node.kind != WorkflowKind.API) node else node.copy(kind = WorkflowKind.API_STREAM, variable = WorkflowVariable("stream-entry", "当前条目", WorkflowType.TRANSLATION),
                children = listOf(WorkflowNode("stream-fill", WorkflowKind.APPLY_TRANSLATIONS, inputs = mapOf("items" to WorkflowSystem.ref("stream-entry")))))
        }) }
        try {
            withTimeout(240_000) { WorkflowRuntime().execute(program, host) }
            assertTrue(previews > 0); assertTrue(streamEnded); assertEquals(1, host.published.size)
            assertTrue(host.published.values.all { it.dataFile.extension == "json" })
            val log = container.apiLogs.records.value.first { it.info.context.mangaId == mangaId }
            val detail = requireNotNull(container.apiLogs.detail(log.id))
            assertEquals("Generated streaming fixture", detail.info.context.mangaName)
            assertEquals("SUCCESS", detail.outcome.status)
            assertTrue(detail.outcome.response.contains("bubbleId")); assertTrue(detail.info.request.contains("stream"))
            assertFalse(detail.info.request.contains("Authorization")); assertFalse(detail.info.request.contains("apiKey"))
            assertEquals(0L, cache.bytes.value)
        } finally { host.close() }
    }
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext<android.content.Context>())
    private val token = UUID.randomUUID().toString()
    private val mangaId = "workflow-manga-$token"
    private val sourceId = "workflow-source-$token"
    private val profileId = "workflow-api-$token"
    private val pageIds = listOf("workflow-page-7-$token", "workflow-page-39-$token")
    private val extraPageIds = mutableListOf<String>()
    private var pausedBefore = true
    private var visionBefore: VisionExecutionSettings? = null
    @Before fun setup() = runBlocking {
        pausedBefore = container.translationQueue.paused.value
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage(); container.translationQueue.awaitResourceRelease()
        assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING") })
        visionBefore = container.visionExecutionPreferences.settings.value
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 1, ocrConcurrency = 1, segGpu = false, ocrBackend = com.lmreader.core.model.OcrBackend.CPU) }
        val db = container.database.openHelper.writableDatabase
        db.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES (?, 'IMAGE_DIRECTORY', ?, 'Generated workflow fixture', 1, 'MULTI_CHAPTER', 0, 'OK', 1)", arrayOf(sourceId, "content://fixture/$token"))
        db.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'Generated workflow fixture', 'fixture', 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)", arrayOf(mangaId, token, sourceId))
    }
    @After fun cleanup() = runBlocking {
        container.apiProfiles.delete(profileId)
        (pageIds + extraPageIds).forEach { container.localPageTranslator.artifacts.delete(it) }
        val db = container.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM mangas WHERE mangaId = ?", arrayOf(mangaId))
        db.execSQL("DELETE FROM library_sources WHERE sourceId = ?", arrayOf(sourceId))
        container.localPageTranslator.releaseModels()
        visionBefore?.let { original -> container.visionExecutionPreferences.update { original } }
        if(pausedBefore) container.translationQueue.pause() else container.translationQueue.resume()
    }
    @Test fun additionsAreAtomicAndExistingAutomaticEntryAlsoBeatsLaterManualEntry() = runBlocking {
        val repository = container.translationRepository
        repository.upsertGlossary(GlossaryEntry(mangaId, "Akira", "先到的译名", false, 1))
        coroutineScope { repeat(16) { index -> launch(Dispatchers.IO) { repository.upsertGlossary(GlossaryEntry(mangaId, "Akira", "后来-$index", index % 2 == 0, index.toLong() + 2)) } } }
        assertEquals("先到的译名", repository.glossary(mangaId).single().target)
        repository.editGlossary("Akira", GlossaryEntry(mangaId, "Akira", "明确编辑后的译名", true, 50))
        assertEquals("明确编辑后的译名", repository.glossary(mangaId).single().target)
    }
    @Test fun realVisionApiRunsSavedProgramWithBilingualOutputAndChapterGlossary() = runBlocking<Unit> {
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Generated fixture test",
            "http://192.168.137.1:1234/v1", apiKey = "1", model = "gemma4-12b-qat-uncensored-hauhaucs-balanced@q4_k_m",
            parallelLimit = 2, retryCount = 0, parameters = AiParameters(temperature = 0.1, maxTokens = 2048))
        container.apiProfiles.save(profile)
        container.translationRepository.upsertGlossary(GlossaryEntry(mangaId, "Akira", "阿基拉", false, 1))
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(220, 220, 220))
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
            drawText("AKIRA, MEET ME AT", 125f, 270f, paint); drawText("MOON ACADEMY.", 145f, 345f, paint)
        }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val original = output.toByteArray(); val before = original.copyOf()
        val chapters = pageIds.mapIndexed { index, id ->
            val page = ReaderPage(id, 0, "generated.png", id)
            val source = object : PageSource {
                override suspend fun pages() = listOf(page)
                override suspend fun open(page: ReaderPage) = original.inputStream()
                override suspend fun probe(page: ReaderPage) = PageGeometry(800, 1000)
            }
            WorkflowChapterInput(if(index == 0) "7" else "39", "Synthetic chapter", source, listOf(page))
        }
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            "忠实翻译，中文自然", BubbleRenderSettings(), .35f, listOf(profile.copy(apiKey = "")))
        val readTexts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated fixture", chapters, settings, cache, reusePages = false) {
            override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) {
                if(complete) readTexts += (frame.read(WorkflowRef("reread-results")) as WorkflowValue.ListValue).items.map { (it as WorkflowValue.Text).value }
            }
        }
        val peak = AtomicInteger()
        val observer = launch { container.apiClient.activeRequests.collect { peak.updateAndGet { old -> maxOf(old, it) } } }
        var edited = WorkflowTemplates.visionWithGlossary(profileId)
        edited = WorkflowEditing.update(edited, "pages") { it.copy(mode = WorkflowMode.SYNC) }
        edited = WorkflowEditing.insert(edited, WorkflowPosition("chapters", 0), WorkflowNode("images", WorkflowKind.DECLARE,
            variable = WorkflowVariable("saved-images", "图片列表", WorkflowType.list(WorkflowType.IMAGE))))
        repeat(2) { index -> edited = WorkflowEditing.insert(edited, WorkflowPosition("pages", 2 + index), WorkflowNode("remember-image-$index", WorkflowKind.APPEND,
            target = WorkflowRef("saved-images"), inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image")))) }
        val end = edited.allNodes().first { it.kind == WorkflowKind.CHAPTERS }.children.size
        edited = WorkflowEditing.insert(edited, WorkflowPosition("chapters", end), WorkflowNode("results", WorkflowKind.DECLARE,
            variable = WorkflowVariable("reread-results", "重读结果", WorkflowType.list(WorkflowType.TEXT))))
        edited = WorkflowEditing.insert(edited, WorkflowPosition("chapters", end + 1), WorkflowNode("reread", WorkflowKind.EACH,
            variable = WorkflowVariable("reread-image", "已完成页图片", WorkflowType.IMAGE), collectTo = WorkflowRef("reread-results"),
            inputs = mapOf("items" to WorkflowSystem.ref("saved-images")), children = listOf(
                WorkflowNode("reread-text", WorkflowKind.DECLARE, variable = WorkflowVariable("reread-text-value", "读到的文字", WorkflowType.TEXT)),
                WorkflowNode("reread-ocr", WorkflowKind.OCR, target = WorkflowRef("reread-text-value"), inputs = mapOf("image" to WorkflowSystem.ref("reread-image"), "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE))),
                WorkflowNode("reread-return", WorkflowKind.RETURN, inputs = mapOf("value" to WorkflowSystem.ref("reread-text-value"))))))
        val program = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(edited))
        try {
            withTimeout(240_000) { WorkflowRuntime(8).execute(program, host) }
            assertEquals(2, host.published.size)
            assertEquals(4, readTexts.size)
            assertTrue(readTexts.all { it.contains("AKIRA", true) })
            assertTrue(host.published.values.all { it.regions.isNotEmpty() && it.regions.any { r -> r.region.sourceText.isNotBlank() && r.translatedText.isNotBlank() } })
            assertTrue("API requests exceeded 2: ${peak.get()}", peak.get() in 1..2)
            assertEquals("阿基拉", container.translationRepository.glossary(mangaId).first { it.source == "Akira" }.target)
            assertTrue(container.translationRepository.glossary(mangaId).any { it.source.contains("moon", true) })
            assertArrayEquals(before, original)
            assertTrue(host.published.values.all { it.dataFile.extension == "json" })
            assertEquals(0L, cache.bytes.value)
            assertEquals(0, container.apiClient.activeRequests.value)
        } finally { observer.cancelAndJoin(); host.close() }
    }
    private fun generatedChapters(perChapter: Int, opens: AtomicInteger): List<WorkflowChapterInput> {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(220, 220, 220))
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
            drawText("AKIRA, MEET ME AT", 125f, 270f, paint); drawText("MOON ACADEMY.", 145f, 345f, paint)
        }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val bytes = output.toByteArray()
        return pageIds.mapIndexed { chapterIndex, seed ->
            val pages = (1..perChapter).map { i ->
                val id = "$seed-$i"; extraPageIds += id
                ReaderPage(id, i - 1, "generated-$i.png", id)
            }
            val source = object : PageSource {
                override suspend fun pages() = pages
                override suspend fun open(page: ReaderPage): java.io.InputStream { opens.incrementAndGet(); return bytes.inputStream() }
                override suspend fun probe(page: ReaderPage) = PageGeometry(800, 1000)
            }
            WorkflowChapterInput(if(chapterIndex == 0) "7" else "39", "Synthetic chapter", source, pages)
        }
    }
    @Test fun realFullMangaTemplateRequestsApiOnceForTwoChapters() = runBlocking<Unit> {
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Generated full manga test", "http://192.168.137.1:1234/v1",
            apiKey = "1", model = "gemma4-12b-qat-uncensored-hauhaucs-balanced@q4_k_m", parallelLimit = 2, retryCount = 0,
            parameters = AiParameters(temperature = 0.1, maxTokens = 4096))
        container.apiProfiles.save(profile)
        val opens = AtomicInteger(); val calls = AtomicInteger()
        val chapters = generatedChapters(1, opens)
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            "忠实、自然", BubbleRenderSettings(), .35f, listOf(profile.copy(apiKey = "")))
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated full manga", chapters, settings, cache, reusePages = false) {
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                assertTrue(call.wholeManga); assertTrue(published.isEmpty())
                assertTrue(chapters.flatMap { it.pages }.none { container.localPageTranslator.artifacts.has(it.pageId) })
                assertEquals(2, opens.get()); calls.incrementAndGet()
                return super.api(call, frame)
            }
        }
        try {
            withTimeout(240_000) { WorkflowRuntime().execute(WorkflowReferenceTemplates.fullManga(profileId), host) }
            assertEquals(1, calls.get()); assertEquals(2, host.published.size)
            assertTrue(host.published.values.all { it.regions.isNotEmpty() && it.regions.any { r -> r.translatedText.isNotBlank() && r.region.sourceText.isNotBlank() } })
            assertTrue(host.published.values.all { it.dataFile.extension == "json" })
            assertTrue(container.translationRepository.glossary(mangaId).isEmpty())
            assertEquals(2, opens.get()); assertEquals(0L, cache.bytes.value)
        } finally { host.close() }
    }
    @Test fun fullMangaPreprocessingEvictsOnlyReadyBitmapsAndPublishesWithoutDecodingAgain() = runBlocking<Unit> {
        val opens = AtomicInteger(); val calls = AtomicInteger()
        val chapters = generatedChapters(6, opens)
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, "", BubbleRenderSettings(), .35f)
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated cache fixture", chapters, settings, cache, reusePages = false) {
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                assertTrue(published.isEmpty()); assertEquals(12, opens.get()); assertTrue(cache.bytes.value in 1..32L * 1_048_576)
                calls.incrementAndGet()
                return WorkflowValue.ListValue(call.expectedItems!!.items.reversed().map { item ->
                    val row = item as WorkflowValue.Record
                    row.copy(fields = row.fields + ("translation" to text("合成译文")))
                })
            }
        }
        try {
            withTimeout(120_000) { WorkflowRuntime().execute(WorkflowReferenceTemplates.fullManga("fixture"), host) }
            assertEquals(1, calls.get()); assertEquals(12, host.published.size); assertEquals(12, opens.get())
            assertEquals(0L, cache.bytes.value)
        } finally { host.close() }
    }
}
