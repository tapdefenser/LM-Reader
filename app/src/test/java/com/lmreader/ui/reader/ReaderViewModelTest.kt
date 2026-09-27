package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaBackfillTarget
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.storage.settings.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderViewModelTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `读到窗口边缘后继续加载因此可连续跨过初始预载范围`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val chapters = (0 until 8).map(::chapter)
            val mangaRepository = mockk<MangaRepository>(relaxed = true) {
                coEvery { getBackfillTarget(MANGA_ID) } returns target(chapters)
            }
            val progressRepository = mockk<ReadingProgressRepository>(relaxed = true)
            val pageSourceFactory = mockk<PageSourceFactory>()
            every { pageSourceFactory.open(TREE_URI, any()) } answers {
                val chapter = secondArg<ChapterRecord>()
                PageSourceOpenResult.Ready(sourceFor(chapter))
            }
            val readerPreferences = mockk<ReaderPreferences> {
                every { settings } returns flowOf(ReaderSettings(preloadPages = 9))
            }
            val viewModel = ReaderViewModel(
                mangaId = MANGA_ID,
                requestedChapterId = chapters.first().chapterId,
                mangaRepository = mangaRepository,
                progressRepository = progressRepository,
                pageSourceFactory = pageSourceFactory,
                readerPreferences = readerPreferences,
            )
            advanceUntilIdle()

            val initial = viewModel.state.value
            viewModel.move(1)
            advanceUntilIdle()
            assertEquals(initial.scrollRequest + 1, viewModel.state.value.scrollRequest)
            assertEquals("c0-p1", viewModel.state.value.items[viewModel.state.value.currentPageIndex].key)

            // 点击连续走过末页、过渡页、下一章首页；每次都是明确的界面滚动请求。
            val beforeTransitionClicks = viewModel.state.value.scrollRequest
            repeat(3) { viewModel.move(1) }
            advanceUntilIdle()
            assertEquals(beforeTransitionClicks + 3, viewModel.state.value.scrollRequest)
            assertEquals("c1-p0", viewModel.state.value.items[viewModel.state.value.currentPageIndex].key)

            // 每章 3 页、预载 9 页时，初始窗口只覆盖前几章。不断翻到当前列表末端，
            // 状态机必须在靠近边缘时继续加载，最终走到第 8 章，而不是在第 3/4 章封死。
            var largestWindow = 0
            repeat(100) {
                val state = viewModel.state.value
                largestWindow = maxOf(largestWindow, state.chapters?.window?.size ?: 0)
                if (state.chapters?.currentChapterId == chapters.last().chapterId) return@repeat
                state.items.getOrNull(state.currentPageIndex + 1)?.key?.let { nextKey ->
                    // 模拟分页器/条带的滑动落页：回传稳定身份而不是容易过期的下标。
                    viewModel.onItemSettled(nextKey)
                    advanceUntilIdle()
                }
            }

            val final = viewModel.state.value
            assertEquals(chapters.last().chapterId, final.chapters?.currentChapterId)
            assertTrue(final.items.any { it.chapterId == chapters.last().chapterId })
            assertTrue(largestWindow <= 1 + MAX_CHAPTERS_PER_SIDE * 2)

            // 快速滑动产生的旧回调可能晚于窗口更新到达；旧键已被淘汰时必须忽略，
            // 不能把它原先的数字下标套到新列表并误跳回本章。
            viewModel.onItemSettled("c0-p0")
            assertEquals(chapters.last().chapterId, viewModel.state.value.chapters?.currentChapterId)

            // 前端淘汰过的章节仍然可以按需重新加载，反向也不能在旧窗口边缘封死。
            repeat(100) {
                val state = viewModel.state.value
                largestWindow = maxOf(largestWindow, state.chapters?.window?.size ?: 0)
                if (state.chapters?.currentChapterId == chapters.first().chapterId) return@repeat
                state.items.getOrNull(state.currentPageIndex - 1)?.key?.let { previousKey ->
                    viewModel.onItemSettled(previousKey)
                    advanceUntilIdle()
                }
            }
            assertEquals(chapters.first().chapterId, viewModel.state.value.chapters?.currentChapterId)
            assertTrue(largestWindow <= 1 + MAX_CHAPTERS_PER_SIDE * 2)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun sourceFor(chapter: ChapterRecord): PageSource = mockk {
        coEvery { pages() } returns (0 until PAGES_PER_CHAPTER).map { ordinal ->
            ReaderPage(
                pageId = "${chapter.chapterId}-p$ordinal",
                ordinal = ordinal,
                displayName = "$ordinal.jpg",
                documentId = "${chapter.documentId}/$ordinal.jpg",
            )
        }
    }

    private fun chapter(index: Int): ChapterRecord = ChapterRecord(
        chapterId = "c$index",
        mangaId = MANGA_ID,
        documentId = "/manga/c$index",
        kind = ChapterKind.IMAGE_DIRECTORY,
        title = "第 $index 章",
        sortKey = index.toString().padStart(3, '0'),
        pageCount = null,
        coverDocumentId = null,
        contentRevision = 1,
        discoveredAt = 1,
    )

    private fun target(chapters: List<ChapterRecord>) = MangaBackfillTarget(
        manga = MangaRecord(
            mangaId = MANGA_ID,
            anchorDocumentId = "/manga",
            sourceId = "source",
            sourceKind = SourceKind.IMAGE_DIRECTORY,
            layoutMode = LayoutMode.MULTI_CHAPTER,
            displayName = "测试漫画",
            author = null,
            hasMetadata = false,
            summary = null,
            coverDocumentId = null,
            coverChapterId = null,
            chapterCount = chapters.size,
            chapterCountKnown = true,
            availability = MangaAvailability.AVAILABLE,
            discoveryGeneration = 1,
            discoveredAt = 1,
            updatedAt = 1,
        ),
        chapters = chapters,
        sourceTreeUri = TREE_URI,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        sourcePermission = SourcePermissionState.OK,
        hasCover = false,
        hasMetadata = false,
    )

    private companion object {
        const val MANGA_ID = "manga"
        const val TREE_URI = "content://test/tree"
        const val PAGES_PER_CHAPTER = 3
        const val MAX_CHAPTERS_PER_SIDE = ReaderSettings.PRELOAD_PAGES_MAX
    }
}
