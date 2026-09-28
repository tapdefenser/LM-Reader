package com.lmreader.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.LibrarySourceEntity
import com.lmreader.core.database.entity.MangaEntity
import com.lmreader.core.database.entity.ReadingProgressEntity
import com.lmreader.core.database.entity.ShelfEntryEntity
import com.lmreader.core.model.BookshelfSort
import com.lmreader.core.model.BookshelfSortMode
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 书架排序（用户口径：一个**全局显示**的排序，作用于"分类之后"的漫画）。
 *
 * 排序规则写在 SQL 的 `CASE` 里（Room 不能把 `ORDER BY` 当参数传），**单元测试的
 * 假 DAO 测不到它**，所以只能在真 SQLite 上验：这里用 Room 内存库插入几部作品，
 * 逐个方式断言返回顺序。
 *
 * 三类断言各自对应一个容易写错的地方：
 * - 名称用**自然序**（`第2话` 在 `第10话` 之前），不是字典序；
 * - 加入时间/最近阅读能正确取到 `shelf_entries` 与 `reading_progress` 的值
 *   （后者是 LEFT JOIN，缺失行的处理靠 NULL 在 ORDER BY 里的位置）；
 * - 分类筛选与排序**正交**：换排序不影响筛选结果集合，只影响顺序。
 */
@RunWith(AndroidJUnit4::class)
class ShelfOrderingTest {

    private lateinit var database: LmReaderDatabase

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LmReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        database.sourceDao().upsert(source("s1", orderIndex = 0))
        database.sourceDao().upsert(source("s2", orderIndex = 1))

        // 三本书，加入顺序与名称顺序**刻意相反**，才能区分两种排序。
        // 名称：第10话 < 第2话 < 第9话（自然序）；加入时间：A 最早、C 最晚。
        database.mangaDao().upsert(manga("m1", "第10话", sourceId = "s1"))
        database.mangaDao().upsert(manga("m2", "第2话", sourceId = "s1"))
        database.mangaDao().upsert(manga("m3", "第9话", sourceId = "s2"))

        database.shelfDao().upsertEntry(ShelfEntryEntity(mangaId = "m1", categoryId = 0L, addedAt = 100L))
        database.shelfDao().upsertEntry(ShelfEntryEntity(mangaId = "m2", categoryId = 0L, addedAt = 200L))
        database.shelfDao().upsertEntry(ShelfEntryEntity(mangaId = "m3", categoryId = 5L, addedAt = 300L))

        // 只有 m2 读过，而且时间最早：用来验"从没读过的排后面"。
        database.readingProgressDao().upsert(
            ReadingProgressEntity(
                mangaId = "m2",
                chapterId = "c1",
                pageOrdinal = 0,
                intraPageRatio = 0f,
                read = false,
                bookmark = false,
                updatedAt = 50L,
            ),
        )
        database.readingProgressDao().upsert(
            ReadingProgressEntity(
                mangaId = "m1",
                chapterId = "c1",
                pageOrdinal = 0,
                intraPageRatio = 0f,
                read = false,
                bookmark = false,
                updatedAt = 900L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun nameUsesNaturalOrder() = runBlocking {
        // 自然序：第2话 < 第9话 < 第10话（字典序会得到 第10话 < 第2话 < 第9话）。
        assertEquals(
            listOf("m2", "m3", "m1"),
            ids(sort = BookshelfSort(BookshelfSortMode.NAME, descending = false)),
        )
        assertEquals(
            listOf("m1", "m3", "m2"),
            ids(sort = BookshelfSort(BookshelfSortMode.NAME, descending = true)),
        )
    }

    @Test
    fun addedTimeNewestFirstWhenDescending() = runBlocking {
        assertEquals(
            listOf("m3", "m2", "m1"),
            ids(sort = BookshelfSort(BookshelfSortMode.ADDED, descending = true)),
        )
        assertEquals(
            listOf("m1", "m2", "m3"),
            ids(sort = BookshelfSort(BookshelfSortMode.ADDED, descending = false)),
        )
    }

    @Test
    fun recentReadPutsNeverReadLastWhenDescending() = runBlocking {
        // m1 最近读过(900) → 最前；m2 读过但更早(50)；m3 从没读过 → 最后。
        assertEquals(
            listOf("m1", "m2", "m3"),
            ids(sort = BookshelfSort(BookshelfSortMode.READ, descending = true)),
        )
    }

    @Test
    fun categoryFilterIsIndependentOfSort() = runBlocking {
        // 只看分类 0：m1、m2 两部，排序仍生效。
        val byName = ids(categoryId = 0L, sort = BookshelfSort(BookshelfSortMode.NAME, descending = false))
        assertEquals(listOf("m2", "m1"), byName)

        val byAdded = ids(categoryId = 0L, sort = BookshelfSort(BookshelfSortMode.ADDED, descending = true))
        assertEquals(listOf("m2", "m1"), byAdded)
    }

    @Test
    fun searchAndSortCombine() = runBlocking {
        // 关键字只留 "第" 全部命中；排序照旧生效（搜索与排序是两件事）。
        assertEquals(
            listOf("m2", "m3", "m1"),
            ids(pattern = "%第%", sort = BookshelfSort(BookshelfSortMode.NAME, descending = false)),
        )
    }

    private suspend fun ids(
        categoryId: Long? = null,
        pattern: String? = null,
        sort: BookshelfSort,
    ): List<String> = database.mangaDao()
        .pageShelf(
            categoryId = categoryId,
            pattern = pattern,
            mode = sort.mode.name,
            descending = sort.descending,
            offset = 0,
            limit = 50,
        )
        .map { it.mangaId }

    private fun source(sourceId: String, orderIndex: Int) = LibrarySourceEntity(
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
        revision = 1L,
        lastScanAt = null,
        lastScanStatus = ScanRunStatus.COMPLETED,
        lastScanError = null,
    )

    private fun manga(mangaId: String, displayName: String, sourceId: String) = MangaEntity(
        mangaId = mangaId,
        anchorDocumentId = "/library/$mangaId",
        sourceId = sourceId,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        layoutMode = LayoutMode.MULTI_CHAPTER,
        displayName = displayName,
        sortKey = com.lmreader.core.index.NaturalOrder.sortKey(displayName),
        sourceOrderIndex = 0,
        author = null,
        hasMetadata = false,
        summary = null,
        coverDocumentId = null,
        coverChapterId = null,
        chapterCount = 1,
        chapterCountKnown = true,
        availability = MangaAvailability.AVAILABLE,
        discoveryGeneration = 1L,
        discoveredAt = 0L,
        updatedAt = 0L,
    )
}
