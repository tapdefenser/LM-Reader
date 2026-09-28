package com.lmreader.core.index

import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.SourceKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChapterResolverTest {
    @Test
    fun `resolves direct leaf directories and archives in natural order`() = runTest {
        val factory = InMemoryTreeFactory(
            rootName = "Manga",
            paths = listOf(
                "10/001.jpg",
                "2/001.png",
                "1/001.webp",
                "extras/nested/001.jpg",
                "mixed/cover.jpg",
                "mixed/chapter.cbz",
                "3.cbz",
            ),
        )

        val result = ChapterResolver(clock = { 42L }).resolve(
            manga = manga(),
            existingChapters = emptyList(),
            anchor = factory.root,
            factory = factory,
        )

        val success = assertIs<ChapterResolution.Success>(result)
        assertEquals(listOf("1", "2", "3", "10"), success.chapters.map { it.title })
        assertEquals(listOf("/1", "/2", "/3.cbz", "/10"), success.chapters.map { it.documentId })
        assertTrue(success.chapters.all { it.discoveredAt == 42L })
    }

    /**
     * 用户口径："更新章节的同时应该要数每章页数，因为这个时候要获取完整的表准备给翻译用了。"
     *
     * 判叶子本来就要列一次那个子目录，因此页数是**零额外 IO** 拿到的；归档要打开压缩包
     * 才知道，所以保持原值（NULL = 仍然未知，不假装）。
     */
    @Test
    fun `顺手数出每章的页数而归档保持未知`() = runTest {
        val factory = InMemoryTreeFactory(
            rootName = "Manga",
            paths = listOf(
                "1/001.jpg",
                "1/002.jpg",
                "1/003.jpg",
                "2/001.png",
                "3.cbz",
            ),
        )

        val result = ChapterResolver().resolve(manga(), emptyList(), factory.root, factory)

        val success = assertIs<ChapterResolution.Success>(result)
        assertEquals(
            mapOf("1" to 3, "2" to 1, "3" to null),
            success.chapters.associate { it.title to it.pageCount },
        )
    }

    @Test
    fun `returns failure when any direct child cannot be enumerated`() = runTest {
        val factory = InMemoryTreeFactory(
            rootName = "Manga",
            paths = listOf("1/001.jpg", "2/001.jpg"),
        ).apply { failingPaths = setOf("/2") }

        val result = ChapterResolver().resolve(manga(), emptyList(), factory.root, factory)

        val failure = assertIs<ChapterResolution.Failure>(result)
        assertTrue(failure.reason.contains("2"))
    }

    private fun manga() = MangaRecord(
        mangaId = "manga",
        anchorDocumentId = "/",
        sourceId = "source",
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        layoutMode = LayoutMode.MULTI_CHAPTER,
        displayName = "Manga",
        author = null,
        hasMetadata = false,
        summary = null,
        coverDocumentId = null,
        coverChapterId = null,
        chapterCount = 1,
        chapterCountKnown = false,
        availability = MangaAvailability.AVAILABLE,
        discoveryGeneration = 7L,
        discoveredAt = 1L,
        updatedAt = 1L,
    )
}
