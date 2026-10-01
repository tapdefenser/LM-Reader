package com.lmreader.reliability

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExportIntegrationTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var sourceId: String
    private lateinit var source: Uri
    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext()); container = AppContainer(app)
        container.startupReady.await()
        sourceId = "source-${UUID.randomUUID()}"; source = tree(sourceId)
        seedRow(container, "mangas", mapOf("mangaId" to "m", "anchorDocumentId" to sourceId, "sourceId" to "s", "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE"))
        seedRow(container, "chapters", mapOf("chapterId" to "c", "mangaId" to "m", "documentId" to sourceId, "kind" to "IMAGE_DIRECTORY"))
    }
    @After fun cleanup() = runBlocking { closeContainer(container) }
    private fun tree(id: String): Uri {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val targetPackage = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e tree $id -e target $targetPackage").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        return DocumentsContract.buildTreeDocumentUri(FaultDocumentsProvider.AUTHORITY, id)
    }
    private fun task(mode: String, format: ExportFormat) = ExportTask(UUID.randomUUID().toString(), "m", "c", "漫画", "一章", source.toString(), tree("$mode-${UUID.randomUUID()}").toString(), false, format)
    private fun saved(task: ExportTask): JSONObject = JSONObject().put("id", task.id).put("mangaId", task.mangaId).put("chapterId", task.chapterId)
        .put("mangaTitle", task.mangaTitle).put("chapterTitle", task.chapterTitle).put("sourceTreeUri", task.sourceTreeUri)
        .put("destinationTreeUri", task.destinationTreeUri).put("singleChapter", task.singleChapter).put("format", task.format.name)
        .put("state", task.state).put("completedPages", task.completedPages).put("totalPages", task.totalPages)
    private suspend fun queue(task: ExportTask, json: JSONObject = saved(task)): ExportQueueCoordinator {
        File(app.filesDir, "export-queue.json").writeText(JSONArray().put(json).toString())
        return ExportQueueCoordinator(container).also { it.pauseAndAwait(); it.resumeAll() }
    }
    @Test fun actualSafPngJpegAndCbzOutputsReopenInOrder() = runBlocking(Dispatchers.IO) {
        for (format in ExportFormat.entries) {
            val task = task("ok", format); val queue = queue(task); queue.process(task)
            assertCompleted(queue, task)
            val uri = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().single().uri
            if (format == ExportFormat.CBZ) {
                val names = mutableListOf<String>()
                app.contentResolver.openInputStream(uri)!!.use { ZipInputStream(it).use { zip -> while (true) { val entry = zip.nextEntry ?: break; names += entry.name; assertTrue(zip.readBytes().isNotEmpty()) } } }
                assertEquals(listOf("00001.png", "00002.png"), names)
            } else assertEquals(listOf("00001.${format.extension}", "00002.${format.extension}"), DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.findFile("漫画 - 一章")!!.listFiles().map { it.name }.sortedBy { it })
            assertEquals(2, DocumentFile.fromTreeUri(app, source)!!.listFiles().size)
            assertFalse(ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging"), task.id).exists())
        }
    }
    @Test fun spaceFailureAndUnsupportedRenameNeverPublishSuccess() = runBlocking(Dispatchers.IO) {
        for (mode in listOf("fail-write", "no-rename", "revoked")) {
            val task = task(mode, ExportFormat.CBZ); val queue = queue(task); queue.process(task)
            val failed = queue.tasks.value.single(); assertEquals("FAILED", failed.state); assertNull(failed.outputUri)
            assertTrue(DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().isEmpty())
            assertTrue(ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging"), task.id).exists())
        }
    }
    @Test fun startupCleansRemoteFileCreatedBeforeUriWasRecorded() = runBlocking(Dispatchers.IO) {
        val task = task("ok", ExportFormat.CBZ).copy(state = "RUNNING")
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!
        tree.createFile("application/octet-stream", ".lmreader-${task.id}.part")!!
        val queue = queue(task, saved(task).put("publicationPhase", "COPYING").put("publicationName", "漫画 - 一章.cbz"))
        assertTrue(tree.listFiles().isEmpty()); assertEquals("INTERRUPTED", queue.tasks.value.single().state)
        assertNull(queue.tasks.value.single().publicationPhase)
    }
    @Test fun startupRecoversRenameBeforeDoneReceiptWithoutDuplicateOutput() = runBlocking(Dispatchers.IO) {
        val task = task("ok", ExportFormat.CBZ).copy(state = "RUNNING")
        val root = ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging").apply { mkdirs() }, task.id).apply { mkdirs() }
        File(root, "chapter.cbz").writeText("complete fixture")
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!
        val doc = tree.createFile("application/octet-stream", ".lmreader-${task.id}.part")!!
        val oldUri = doc.uri.toString(); app.contentResolver.openOutputStream(doc.uri)!!.use { it.write("complete fixture".toByteArray()) }
        assertTrue(doc.renameTo("漫画 - 一章.cbz"))
        val queue = queue(task, saved(task).put("publicationPhase", "READY").put("publicationName", "漫画 - 一章.cbz").put("temporaryUri", oldUri))
        assertCompleted(queue, task); assertEquals(1, tree.listFiles().size)
        app.contentResolver.openInputStream(tree.listFiles().single().uri)!!.use {
            assertEquals("complete fixture", it.readBytes().toString(Charsets.UTF_8))
        }
    }
    @Test fun retryKeepsFrozenPageBytesAfterSourceChanges() = runBlocking(Dispatchers.IO) {
        val task = task("no-rename", ExportFormat.CBZ); val queue = queue(task); queue.process(task)
        assertEquals("FAILED", queue.tasks.value.single().state)
        val sourcePage = DocumentFile.fromTreeUri(app, source)!!.findFile("1.png")!!
        val before = app.contentResolver.openInputStream(sourcePage.uri)!!.use { it.readBytes() }
        app.contentResolver.openOutputStream(sourcePage.uri, "wt")!!.use { it.write("damaged new source".toByteArray()) }
        container.exportSettings.setDestination(com.lmreader.core.model.LayoutMode.MULTI_CHAPTER, tree("ok-${UUID.randomUUID()}"))
        queue.retry(task.id)
        val retry = queue.tasks.value.single(); queue.process(retry)
        assertCompleted(queue, retry)
        val output = DocumentFile.fromTreeUri(app, Uri.parse(retry.destinationTreeUri))!!.listFiles().single()
        app.contentResolver.openInputStream(output.uri)!!.use { ZipInputStream(it).use { zip ->
            assertEquals("00001.png", zip.nextEntry.name); assertArrayEquals(before, zip.readBytes())
        } }
    }
    private fun assertCompleted(queue: ExportQueueCoordinator, task: ExportTask) {
        assertTrue(queue.tasks.value.toString(), queue.tasks.value.isEmpty())
        assertEquals(0, JSONArray(File(app.filesDir, "export-queue.json").readText()).length())
        assertFalse(ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging"), task.id).exists())
    }
    @Test fun startupDequeuesOldCompletionAndKeepsUnfinishedTasks() = runBlocking(Dispatchers.IO) {
        val completed = task("ok", ExportFormat.CBZ).copy(state = "DONE")
        val failed = completed.copy(id = UUID.randomUUID().toString(), state = "FAILED")
        val paused = completed.copy(id = UUID.randomUUID().toString(), state = "PAUSED")
        val root = ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging").apply { mkdirs() }, completed.id).apply { mkdirs() }
        File(root, "snapshot.json").writeText("old snapshot")
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(completed.destinationTreeUri))!!
        val output = tree.createFile("application/zip", "completed.cbz")!!
        app.contentResolver.openOutputStream(output.uri)!!.use { it.write("published output".toByteArray()) }
        File(app.filesDir, "export-queue.json").writeText(JSONArray().put(saved(completed).put("outputUri", output.uri.toString()))
            .put(saved(failed)).put(saved(paused)).toString())
        val queue = ExportQueueCoordinator(container); queue.pauseAndAwait()
        assertEquals(setOf(failed.id, paused.id), queue.tasks.value.map { it.id }.toSet())
        assertEquals(2, JSONArray(File(app.filesDir, "export-queue.json").readText()).length())
        assertFalse(root.exists())
        app.contentResolver.openInputStream(output.uri)!!.use { assertEquals("published output", it.readBytes().toString(Charsets.UTF_8)) }
        val restarted = ExportQueueCoordinator(container); restarted.pauseAndAwait()
        assertEquals(setOf(failed.id, paused.id), restarted.tasks.value.map { it.id }.toSet())
    }
    @Test fun failedTemporaryDeletionRemainsRecoverableInJournal() = runBlocking(Dispatchers.IO) {
        val task = task("no-delete-no-rename", ExportFormat.CBZ); val queue = queue(task); queue.process(task)
        val failed = queue.tasks.value.single(); assertEquals("FAILED", failed.state)
        assertNotNull(failed.temporaryUri); assertEquals("READY", failed.publicationPhase)
        val restarted = ExportQueueCoordinator(container); restarted.pauseAndAwait()
        assertEquals("INTERRUPTED", restarted.tasks.value.single().state)
        assertNotNull(restarted.tasks.value.single().temporaryUri)
        assertEquals(1, DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().size)
    }
    @Test fun documentIdsRetainSlashesPlusAndPercentSigns() {
        for (id in listOf("root/child with space.png", "root/a+b.png", "root/a%20b.png", "root/中文.png")) {
            val uri = container.safAccess.documentUri(source.toString(), id)!!
            assertEquals(id, DocumentsContract.getDocumentId(uri))
        }
    }
    @Test fun queueWriteFailurePausesWithoutDroppingExistingTasks() = runBlocking(Dispatchers.IO) {
        val task = task("ok", ExportFormat.CBZ)
        val queue = queue(task)
        val state = File(app.filesDir, "export-queue.json")
        assertTrue(state.delete()); assertTrue(state.mkdir())
        // A file cannot atomically replace a directory. Exercise the actual commit failure.
        queue.pauseAll()
        assertNotNull(queue.storageFailure.value)
        assertTrue(queue.paused.value)
        assertEquals(task.id, queue.tasks.value.single().id)
        queue.resumeAll(); queue.process(task)
        assertTrue(queue.paused.value)
        assertTrue(DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().isEmpty())
    }
}
