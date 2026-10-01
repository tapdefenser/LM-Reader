package com.lmreader.ui.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueOrderStoreTest {
    @Test fun sortThenDragPersistsAcrossRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "queue-order-test.json")
        file.delete()
        File(file.path + ".bak").delete()
        try {
            val store = QueueOrderStore(file)
            store.sortManga(listOf("m2", "m1"))
            store.moveManga("m1", -1, listOf("m2", "m1"))
            store.sortChapters("m1", listOf("c2@zh", "c1@zh"))
            store.moveChapter("m1", "c1@zh", -1, listOf("c2@zh", "c1@zh"))
            store.setPaused(true)
            store.excludePage("c1", 42, "page1")
            val reopened = QueueOrderStore(file)
            assertEquals(true, reopened.isPaused())
            assertEquals(true, reopened.isPageExcluded("c1", 42, "page1"))
            assertEquals(false, reopened.isPageExcluded("c1", 43, "page1"))
            reopened.includePage("c1", "page1")
            assertEquals(false, QueueOrderStore(file).isPageExcluded("c1", 42, "page1"))
            assertEquals(listOf("m1", "m2"), reopened.mangaOrder(listOf("m1", "m2")))
            assertEquals(listOf("c1@zh", "c2@zh"),
                reopened.chapterOrder("m1", listOf("c2@zh", "c1@zh")))
            assertEquals(listOf("m1", "m2", "m3"), reopened.mangaOrder(listOf("m1", "m2", "m3")))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }
}
