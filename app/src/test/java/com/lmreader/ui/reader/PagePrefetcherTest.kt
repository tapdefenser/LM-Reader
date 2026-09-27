package com.lmreader.ui.reader

import android.content.Context
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagePrefetcherTest {
    @get:Rule val temporary = TemporaryFolder()

    private val page = ReaderPage("page-1", 0, "1.jpg", "1.jpg")

    @Test
    fun `prefetch writes a readable disk entry`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = mockk<PageSource> {
            coEvery { open(page) } answers { ByteArrayInputStream(bytes) }
        }
        val context = mockk<Context> { every { cacheDir } returns temporary.root }
        val prefetcher = PagePrefetcher(context, maxBytes = 100, maxEntryBytes = 10)

        prefetcher.request("manga", listOf(PrefetchCandidate(page, source)), emptyList())
        val cached = withTimeout(3_000) {
            var found: ByteArray? = null
            while (found == null) {
                found = prefetcher.stream(page.pageId)?.use { it.readBytes() }
                if (found == null) delay(10)
            }
            found
        }
        assertContentEquals(bytes, cached)
        prefetcher.cancelAll()
    }

    @Test
    fun `oversized entry is rejected without leaving a partial file`() = runBlocking {
        val bytes = ByteArray(100) { 7 }
        val closed = AtomicBoolean(false)
        val source = mockk<PageSource> {
            coEvery { open(page) } answers {
                object : ByteArrayInputStream(bytes) {
                    override fun close() {
                        super.close()
                        closed.set(true)
                    }
                }
            }
        }
        val context = mockk<Context> { every { cacheDir } returns temporary.root }
        val prefetcher = PagePrefetcher(context, maxBytes = 100, maxEntryBytes = 4)

        prefetcher.request("manga", listOf(PrefetchCandidate(page, source)), emptyList())
        withTimeout(3_000) {
            while (!closed.get() || temporary.root.resolve("reader-pages").listFiles()?.isNotEmpty() == true) {
                delay(10)
            }
        }
        assertNull(prefetcher.stream(page.pageId))
        assertTrue(temporary.root.resolve("reader-pages").listFiles().orEmpty().isEmpty())
        prefetcher.cancelAll()
    }
}
