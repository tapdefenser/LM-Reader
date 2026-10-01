package com.lmreader.ui.queue

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.TranslationPageMode
import com.lmreader.core.model.TranslationState
import com.lmreader.core.model.TranslationWorkflow
import com.lmreader.core.model.StableId
import com.lmreader.di.AppContainer
import com.lmreader.ui.workflow.translationTaskSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import org.json.JSONObject

/** Creates one generated page under a uniquely named external test directory. Never scans user manga. */
@RunWith(AndroidJUnit4::class)
class TranslationQueueIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val container = AppContainer.from(context)
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val sourceId = "queue-test-source-$id"
    private val mangaId = "queue-test-manga-$id"
    private val chapterId = "queue-test-chapter-$id"
    private val root = File(Environment.getExternalStorageDirectory(), "LMReaderQueueFixture-$id")
    private var previouslyPaused = false

    @Before fun setup() {
        assumeTrue(container.treeAccess.usesDirectFileAccess())
        previouslyPaused = container.translationQueue.paused.value
        runBlocking {
            container.translationQueue.pause()
            container.translationQueue.awaitCurrentPage()
            assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING") })
        }
        assertTrue(root.mkdirs())
        val chapter = File(root, "chapter").apply { assertTrue(mkdirs()) }
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("HELLO WORLD", 100f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 58f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
        File(chapter, "page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun cleanup() {
        runCatching { runBlocking {
            val db = container.database.openHelper.writableDatabase
            db.execSQL("DELETE FROM chapter_translation WHERE chapterId = ?", arrayOf(chapterId))
            db.execSQL("DELETE FROM chapters WHERE chapterId = ?", arrayOf(chapterId))
            db.execSQL("DELETE FROM mangas WHERE mangaId = ?", arrayOf(mangaId))
            db.execSQL("DELETE FROM library_sources WHERE sourceId = ?", arrayOf(sourceId))
            if (previouslyPaused) container.translationQueue.pause() else container.translationQueue.resume()
        } }
        val pageId = StableId.pageId(chapterId, File(root, "chapter/page.png").absolutePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(pageId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val artifactRoot = container.localPageTranslator.artifacts.root.canonicalFile
        val artifact = File(artifactRoot, "$digest.json")
        if (artifact.canonicalFile.parentFile == artifactRoot && artifact.isFile) {
            runCatching {
                val savedPageId = JSONObject(JSONObject(artifact.readText()).getString("document")).getString("pageId")
                if (savedPageId == pageId) artifact.delete()
            }
        }
        val external = Environment.getExternalStorageDirectory().canonicalFile
        if (root.canonicalFile.parentFile == external && root.name.startsWith("LMReaderQueueFixture-")) {
            root.deleteRecursively()
        }
    }

    private suspend fun seedTask(state: String) {
        val db = container.database.openHelper.writableDatabase
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A${root.name}"
        db.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES (?, 'IMAGE_DIRECTORY', ?, ?, 1, 'MULTI_CHAPTER', 0, 'OK', 1)",
            arrayOf(sourceId, treeUri, root.absolutePath))
        db.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'Fixture', 'fixture', 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)",
            arrayOf(mangaId, root.absolutePath, sourceId))
        db.execSQL("INSERT INTO chapters (chapterId, mangaId, documentId, kind, title, sortKey, position, contentRevision, discoveredAt) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'chapter', 'chapter', 0, 1, 0)",
            arrayOf(chapterId, mangaId, File(root, "chapter").absolutePath))
        val settings = MangaTranslationSettings(sourceLanguage = "英语", targetLanguage = "简体中文",
            pageMode = TranslationPageMode.BUBBLE)
        val snapshot = translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE, settings, "英语",
            "简体中文", "", com.lmreader.core.model.BubbleRenderSettings())
        container.database.translationDao().upsertAll(listOf(ChapterTranslationEntity(chapterId,
            mangaId, "简体中文", state, "英语", false, snapshot,
            System.currentTimeMillis(), null, 0, if(state == "FAILED") "合成失败记录" else null, System.currentTimeMillis())))
    }
    @Test fun translatedChapterBecomesDoneWithoutChangingSource() = runBlocking {
        val image = File(root, "chapter/page.png")
        val original = image.readBytes()
        seedTask("PENDING")
        container.translationQueue.resume()
        val finished = withTimeout(40_000) { container.database.translationDao().observeQueue().map { container.database.translationDao().byChapter(chapterId) }.first { list ->
            list.any { it.chapterId == chapterId && it.state in listOf(TranslationState.DONE.name, TranslationState.FAILED.name) }
        }.first { it.chapterId == chapterId } }
        assertEquals(finished.failure, TranslationState.DONE.name, finished.state)
        assertEquals(1, finished.translatedCount)
        assertTrue(container.database.translationDao().queueSnapshot().none { it.chapterId == chapterId })
        assertTrue(original.contentEquals(image.readBytes()))
    }
    @Test fun startAllRetriesFailedTaskAndLeavesCompletedTaskUnchanged() = runBlocking {
        assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED") })
        seedTask("FAILED")
        val before = container.database.translationDao().byChapter(chapterId).single()
        container.translationQueue.startAll().join()
        val completed = withTimeout(40_000) { container.database.translationDao().observeQueue().map { container.database.translationDao().byChapter(chapterId) }.first { rows ->
            rows.any { it.chapterId == chapterId && it.state in listOf("DONE", "FAILED") && it.updatedAt > before.updatedAt }
        }.single { it.chapterId == chapterId } }
        assertEquals(completed.failure, "DONE", completed.state)
        assertEquals(before.queuedAt, completed.queuedAt)
        assertEquals(before.configSnapshot, completed.configSnapshot)
        assertEquals(1, completed.translatedCount)
        container.translationQueue.startAll().join()
        assertEquals(completed, container.database.translationDao().byChapter(chapterId).single())
    }
}
