package com.lmreader.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.ChapterEntity
import com.lmreader.core.database.entity.LibrarySourceEntity
import com.lmreader.core.database.entity.MangaEntity
import com.lmreader.core.database.entity.ReadingProgressEntity
import com.lmreader.core.database.entity.ShelfEntryEntity
import com.lmreader.core.database.repository.MangaRepositoryImpl
import com.lmreader.core.database.repository.SourceRepositoryImpl
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.ScanResult
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 回归：更新 mangas 父行不能触发级联删除章节与书架关系。 */
@RunWith(AndroidJUnit4::class)
class MangaUpsertIntegrityTest {

    private lateinit var database: LmReaderDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LmReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun updatingExistingMangaPreservesChildrenAndShelfEntry() = runBlocking {
        val manga = mangaEntity(MANGA_ID, sourceId = "source-1", displayName = "Before")
        database.mangaDao().upsert(manga)
        database.chapterDao().upsertAll(
            listOf(
                ChapterEntity(
                    chapterId = "chapter-1",
                    mangaId = MANGA_ID,
                    documentId = "/library/manga/chapter-1",
                    kind = ChapterKind.IMAGE_DIRECTORY,
                    title = "Chapter 1",
                    sortKey = "chapter 00000000000000000001",
                    pageCount = 1,
                    coverDocumentId = null,
                    contentRevision = 1,
                    discoveredAt = 1,
                ),
            ),
        )
        database.shelfDao().upsertEntry(ShelfEntryEntity(MANGA_ID, categoryId = 0, addedAt = 1))
        database.readingProgressDao().upsert(
            ReadingProgressEntity(
                mangaId = MANGA_ID,
                chapterId = "chapter-1",
                pageOrdinal = 3,
                intraPageRatio = 0f,
                read = false,
                bookmark = false,
                updatedAt = 1,
            ),
        )

        database.mangaDao().upsert(
            manga.copy(
                displayName = "After",
                chapterCount = 2,
                discoveryGeneration = 2,
                updatedAt = 2,
            ),
        )

        assertEquals("After", database.mangaDao().getById(MANGA_ID)?.displayName)
        assertEquals(1, database.chapterDao().countByManga(MANGA_ID))
        assertNotNull(database.shelfDao().getEntry(MANGA_ID))
        assertEquals(3, database.readingProgressDao().get(MANGA_ID)?.pageOrdinal)
    }

    @Test
    fun staleSourceRevisionCannotWriteScanResults() = runBlocking {
        database.sourceDao().upsert(sourceEntity("source-revision", orderIndex = 0, revision = 2))
        val repository = MangaRepositoryImpl(database, database.mangaDao())
        val result = ScanResult(
            sourceId = "source-revision",
            sourceKind = SourceKind.IMAGE_DIRECTORY,
            sourceRevision = 1,
            generation = 9,
            manga = mangaRecord("stale-manga", "source-revision", generation = 9),
            chapters = listOf(chapterRecord("stale-chapter", "stale-manga")),
            fullyEnumeratedContainers = setOf("/library/stale-manga"),
            metadataCandidates = emptyList(),
        )

        val report = repository.upsertScanResult(result)

        assertEquals(0, report.mangasInserted)
        assertEquals(false, report.accepted)
        assertEquals(null, database.mangaDao().getById("stale-manga"))
        assertEquals(0, database.chapterDao().countByManga("stale-manga"))
    }

    @Test
    fun reorderUpdatesSourceRowsAndMangaProjectionTogether() = runBlocking {
        database.sourceDao().upsert(sourceEntity("s1", orderIndex = 0, revision = 1))
        database.sourceDao().upsert(sourceEntity("s2", orderIndex = 1, revision = 1))
        database.mangaDao().upsert(mangaEntity("m1", sourceId = "s1", sourceOrderIndex = 0))
        database.mangaDao().upsert(mangaEntity("m2", sourceId = "s2", sourceOrderIndex = 1))

        SourceRepositoryImpl(database, database.sourceDao()).reorder(listOf("s2", "s1"))

        assertEquals(0, database.sourceDao().getById("s2")?.orderIndex)
        assertEquals(1, database.sourceDao().getById("s1")?.orderIndex)
        assertEquals(0, database.mangaDao().getBySource("s2").single().sourceOrderIndex)
        assertEquals(1, database.mangaDao().getBySource("s1").single().sourceOrderIndex)
    }

    @Test
    fun sourceCountsComeFromPersistentIndex() = runBlocking {
        database.mangaDao().upsert(mangaEntity("m1", sourceId = "counted-source"))
        database.mangaDao().upsert(mangaEntity("m2", sourceId = "counted-source"))

        val counts = database.mangaDao().observeVisibleCountsBySource().first()

        assertEquals(2, counts.single { it.sourceId == "counted-source" }.itemCount)
    }

    private fun sourceEntity(sourceId: String, orderIndex: Int, revision: Long) = LibrarySourceEntity(
        sourceId = sourceId,
        kind = SourceKind.IMAGE_DIRECTORY,
        treeUri = "content://test/tree/$sourceId",
        displayPath = "/$sourceId",
        providerLabel = null,
        displayName = null,
        recursive = true,
        mode = LayoutMode.MULTI_CHAPTER,
        orderIndex = orderIndex,
        permission = SourcePermissionState.OK,
        revision = revision,
        lastScanAt = null,
        lastScanStatus = null,
        lastScanError = null,
    )

    private fun mangaEntity(
        mangaId: String,
        sourceId: String,
        displayName: String = mangaId,
        sourceOrderIndex: Int = 0,
    ) = MangaEntity(
        mangaId = mangaId,
        anchorDocumentId = "/library/$mangaId",
        sourceId = sourceId,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        layoutMode = LayoutMode.MULTI_CHAPTER,
        displayName = displayName,
        sortKey = displayName.lowercase(),
        sourceOrderIndex = sourceOrderIndex,
        author = null,
        hasMetadata = false,
        summary = null,
        coverDocumentId = null,
        coverChapterId = null,
        chapterCount = 1,
        chapterCountKnown = true,
        availability = MangaAvailability.AVAILABLE,
        discoveryGeneration = 1,
        discoveredAt = 1,
        updatedAt = 1,
    )

    private fun mangaRecord(mangaId: String, sourceId: String, generation: Long) = MangaRecord(
        mangaId = mangaId,
        anchorDocumentId = "/library/$mangaId",
        sourceId = sourceId,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        layoutMode = LayoutMode.MULTI_CHAPTER,
        displayName = mangaId,
        author = null,
        hasMetadata = false,
        summary = null,
        coverDocumentId = null,
        coverChapterId = null,
        chapterCount = 1,
        chapterCountKnown = true,
        availability = MangaAvailability.AVAILABLE,
        discoveryGeneration = generation,
        discoveredAt = 1,
        updatedAt = 1,
    )

    private fun chapterRecord(chapterId: String, mangaId: String) = ChapterRecord(
        chapterId = chapterId,
        mangaId = mangaId,
        documentId = "/library/$mangaId/$chapterId",
        kind = ChapterKind.IMAGE_DIRECTORY,
        title = chapterId,
        sortKey = chapterId,
        pageCount = 1,
        coverDocumentId = null,
        contentRevision = 1,
        discoveredAt = 1,
    )

    private companion object {
        const val MANGA_ID = "manga-upsert-integrity"
    }
}
