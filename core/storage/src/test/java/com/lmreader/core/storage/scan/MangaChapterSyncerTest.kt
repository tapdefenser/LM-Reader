package com.lmreader.core.storage.scan

import com.lmreader.core.index.ChapterResolver
import com.lmreader.core.index.TreeFactory
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaBackfillTarget
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ScanPersistReport
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.TreeAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MangaChapterSyncerTest {
    @Test
    fun `complete resolution persists exact chapter list`() = runTest {
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val sourceRepository = mockk<SourceRepository>()
        val treeAccess = mockk<TreeAccess>()
        val target = target()
        coEvery { mangaRepository.getBackfillTarget(MANGA_ID) } returns target
        coEvery { sourceRepository.getSource(SOURCE_ID) } returns source()
        every { treeAccess.checkReadable(TREE_URI) } returns null
        every { treeAccess.openAt(TREE_URI, ANCHOR_ID) } returns StaticTree(
            listOf(archive("10.cbz"), archive("2.cbz")),
        )
        every { treeAccess.treeFactory(TREE_URI) } returns TreeFactory { null }
        coEvery { mangaRepository.upsertScanResult(any()) } returns report()

        val outcome = syncer(treeAccess, mangaRepository, sourceRepository).sync(MANGA_ID)

        val success = assertIs<ChapterSyncOutcome.Success>(outcome)
        assertEquals(listOf("2", "10"), success.chapters.map { it.title })
        coVerify(exactly = 1) {
            mangaRepository.upsertScanResult(match { result ->
                result.sourceRevision == 4L &&
                    result.manga.chapterCountKnown &&
                    result.manga.chapterCount == 2 &&
                    result.fullyEnumeratedContainers == setOf(ANCHOR_ID)
            })
        }
    }

    @Test
    fun `enumeration failure preserves existing database rows`() = runTest {
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val sourceRepository = mockk<SourceRepository>()
        val treeAccess = mockk<TreeAccess>()
        coEvery { mangaRepository.getBackfillTarget(MANGA_ID) } returns target()
        coEvery { sourceRepository.getSource(SOURCE_ID) } returns source()
        every { treeAccess.checkReadable(TREE_URI) } returns null
        every { treeAccess.openAt(TREE_URI, ANCHOR_ID) } returns FailingTree
        every { treeAccess.treeFactory(TREE_URI) } returns TreeFactory { null }

        val outcome = syncer(treeAccess, mangaRepository, sourceRepository).sync(MANGA_ID)

        val failure = assertIs<ChapterSyncOutcome.Failure>(outcome)
        assertTrue(failure.reason.contains("无法读取漫画目录"))
        coVerify(exactly = 0) { mangaRepository.upsertScanResult(any()) }
    }

    @Test
    fun `stale source revision is surfaced as failure`() = runTest {
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val sourceRepository = mockk<SourceRepository>()
        val treeAccess = mockk<TreeAccess>()
        coEvery { mangaRepository.getBackfillTarget(MANGA_ID) } returns target()
        coEvery { sourceRepository.getSource(SOURCE_ID) } returns source()
        every { treeAccess.checkReadable(TREE_URI) } returns null
        every { treeAccess.openAt(TREE_URI, ANCHOR_ID) } returns StaticTree(listOf(archive("1.cbz")))
        every { treeAccess.treeFactory(TREE_URI) } returns TreeFactory { null }
        coEvery { mangaRepository.upsertScanResult(any()) } returns report(accepted = false)

        val outcome = syncer(treeAccess, mangaRepository, sourceRepository).sync(MANGA_ID)

        val failure = assertIs<ChapterSyncOutcome.Failure>(outcome)
        assertTrue(failure.reason.contains("来源配置已变更"))
    }

    private fun syncer(
        treeAccess: TreeAccess,
        mangaRepository: MangaRepository,
        sourceRepository: SourceRepository,
    ) = MangaChapterSyncer(
        treeAccess = treeAccess,
        resolver = ChapterResolver(clock = { NOW }),
        mangaRepository = mangaRepository,
        sourceRepository = sourceRepository,
        clock = { NOW },
    )

    private fun target() = MangaBackfillTarget(
        manga = MangaRecord(
            mangaId = MANGA_ID,
            anchorDocumentId = ANCHOR_ID,
            sourceId = SOURCE_ID,
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
            discoveryGeneration = 9L,
            discoveredAt = 1L,
            updatedAt = 1L,
        ),
        chapters = emptyList(),
        sourceTreeUri = TREE_URI,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        sourcePermission = SourcePermissionState.OK,
        hasCover = false,
        hasMetadata = false,
    )

    private fun source() = LibrarySource(
        sourceId = SOURCE_ID,
        kind = SourceKind.IMAGE_DIRECTORY,
        treeUri = TREE_URI,
        displayPath = "/library",
        providerLabel = null,
        displayName = null,
        recursive = true,
        mode = LayoutMode.MULTI_CHAPTER,
        orderIndex = 0,
        permission = SourcePermissionState.OK,
        revision = 4L,
        lastScanAt = null,
        lastScanStatus = ScanRunStatus.COMPLETED,
        lastScanError = null,
    )

    private fun archive(name: String) = ChildNode(
        documentId = "$ANCHOR_ID/$name",
        name = name,
        isDirectory = false,
        mimeType = "application/vnd.comicbook+zip",
    )

    private fun report(accepted: Boolean = true) = ScanPersistReport(0, 1, 1, 0, 0, accepted)

    private class StaticTree(private val children: List<ChildNode>) : ContentTree {
        override val rootName = "Manga"
        override suspend fun listChildren() = children
        override suspend fun hasDirectoryChildren() = children.any { it.isDirectory }
        override suspend fun hasImageChild() = false
        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private object FailingTree : ContentTree {
        override val rootName = "Manga"
        override suspend fun listChildren(): List<ChildNode> = throw IOException("模拟失败")
        override suspend fun hasDirectoryChildren() = false
        override suspend fun hasImageChild() = false
        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private companion object {
        const val MANGA_ID = "manga"
        const val SOURCE_ID = "source"
        const val TREE_URI = "content://test/tree/library"
        const val ANCHOR_ID = "/library/Manga"
        const val NOW = 1_700_000_000_000L
    }
}
