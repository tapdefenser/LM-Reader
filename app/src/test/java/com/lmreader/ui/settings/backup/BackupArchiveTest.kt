package com.lmreader.ui.settings.backup

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*
import java.io.*
import java.util.zip.*

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun packageRoot(): File = temp.newFolder().apply {
        listOf("preferences.json", "api.json", "settings.json", "workflows.json", "queue-order.json").forEach { File(this, it).writeText("{}") }
        File(this, "database").mkdirs()
        BackupArchive.tables.forEach { File(this, "database/$it.json").writeText("{\"rows\":[]}") }
    }
    @Test fun inventoryRoundTripRetainsAllBytes() {
        val source = packageRoot()
        File(source, "pages").mkdirs(); File(source, "pages/${"a".repeat(64)}.json").writeText("人工编辑的译文")
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(source, it) }.toByteArray()
        val restored = temp.newFolder(); BackupArchive.read(bytes.inputStream(), restored)
        source.walkTopDown().filter { it.isFile }.forEach { assertContentEquals(it.readBytes(), File(restored, it.relativeTo(source).path).readBytes()) }
    }
    @Test fun traversalAndUnexpectedFilesAreRejected() {
        for (name in listOf("../api.json", "/api.json", "database/unknown.json", "pages/../../outside", "api-request-logs/log.json", "models/key")) {
            assertFalse(BackupArchive.allowed(name))
            val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(1); it.closeEntry() } }.toByteArray()
            assertFails { BackupArchive.read(bytes.inputStream(), temp.newFolder()) }
        }
    }
    @Test fun changedPayloadAndMissingEntryFailBeforeRestore() {
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(packageRoot(), it) }.toByteArray()
        for (remove in listOf(false, true)) {
            val corrupted = ByteArrayOutputStream()
            ZipInputStream(bytes.inputStream()).use { input -> ZipOutputStream(corrupted).use { output ->
                while (true) { val entry = input.nextEntry ?: break
                    val payload = input.readBytes()
                    if (entry.name == "api.json" && remove) continue
                    output.putNextEntry(ZipEntry(entry.name)); output.write(if (entry.name == "api.json") "bad".toByteArray() else payload); output.closeEntry()
                }
            } }
            assertFails { BackupArchive.read(corrupted.toByteArray().inputStream(), temp.newFolder()) }
        }
    }
    @Test fun invalidFilenameCannotCreateAnExportRoot() {
        assertFails { com.lmreader.ui.queue.ExportFiles.ownedRoot(temp.root, "../escape") }
    }
}
