package com.lmreader.ui.queue

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ExportFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun interruptedWriteCannotReplaceCommittedPage() {
        val page = temp.newFile("page.png").apply { writeText("old") }
        assertFails { ExportFiles.commit(page) { it.write("partial".toByteArray()); error("disk full") } }
        assertEquals("old", page.readText()); assertFalse(File(page.parentFile, "page.png.part").exists())
    }
    @Test fun resumeRejectsTruncatedAndUncommittedPages() {
        val page = temp.newFile("page.png").apply { writeText("complete") }
        assertFalse(ExportFiles.intact(page))
        ExportFiles.receipt(page); assertTrue(ExportFiles.intact(page))
        page.writeText("truncated"); assertFalse(ExportFiles.intact(page))
    }
}
