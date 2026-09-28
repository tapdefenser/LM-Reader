package com.lmreader.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.ChapterEntity
import com.lmreader.core.database.entity.ChapterReadStateEntity
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.entity.MangaEntity
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.TranslationState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回归：**重扫不能抹掉用户按章的数据**。
 *
 * `chapters` 被 `chapter_read_state` 与 `chapter_translation` 以外键引用（级联删除）。
 * 如果章节写入用 `@Insert(onConflict = REPLACE)`，SQLite 的 REPLACE 会先删掉冲突行，
 * **级联就把这两张表里对应章节的行一起删了** —— 而"更新章节"在进详情页时会自动跑一次，
 * 于是用户刚标的已读、刚入队的待翻译记录，退出去再进来就没了。
 *
 * 这个失败是静默的（不报错、不崩溃），只有断言能挡住，所以单独留一条。
 */
@RunWith(AndroidJUnit4::class)
class ChapterUpsertIntegrityTest {

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
    fun reUpsertingChapterKeepsReadStateAndTranslation() = runBlocking {
        database.mangaDao().upsert(manga())
        database.chapterDao().upsertAll(listOf(chapter(title = "第1话")))

        database.chapterReadStateDao().upsertAll(
            listOf(ChapterReadStateEntity(chapterId = CHAPTER_ID, mangaId = MANGA_ID, read = true, updatedAt = 1L)),
        )
        database.translationDao().upsertAll(
            listOf(
                ChapterTranslationEntity(
                    chapterId = CHAPTER_ID,
                    mangaId = MANGA_ID,
                    targetLanguage = "简体中文",
                    state = TranslationState.PENDING.name,
                    sourceLanguage = "日语",
                    autoDetectSource = false,
                    configSnapshot = null,
                    queuedAt = 1L,
                    translatedAt = null,
                    translatedCount = 0,
                    failure = null,
                    updatedAt = 1L,
                ),
            ),
        )

        // 再写一次同一章（重扫/「更新章节」走的就是这条路），标题变了但身份没变。
        database.chapterDao().upsertAll(listOf(chapter(title = "第1话（重命名）")))

        assertEquals("已读标记必须活过重扫", 1, countReadStates())
        assertEquals("待翻译记录必须活过重扫", 1, countTranslations())
        assertEquals("第1话（重命名）", database.chapterDao().getById(CHAPTER_ID)?.title)
    }

    private suspend fun countReadStates(): Int =
        database.query("SELECT COUNT(*) FROM chapter_read_state", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private suspend fun countTranslations(): Int =
        database.query("SELECT COUNT(*) FROM chapter_translation", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun manga() = MangaEntity(
        mangaId = MANGA_ID,
        anchorDocumentId = "/library/Manga",
        sourceId = "source-1",
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        layoutMode = LayoutMode.MULTI_CHAPTER,
        displayName = "Manga",
        sortKey = "manga",
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

    private fun chapter(title: String) = ChapterEntity(
        chapterId = CHAPTER_ID,
        mangaId = MANGA_ID,
        documentId = "/library/Manga/$title",
        kind = ChapterKind.IMAGE_DIRECTORY,
        title = title,
        sortKey = title,
        position = 0L,
        modifiedAt = null,
        pageCount = null,
        coverDocumentId = null,
        contentRevision = 1L,
        discoveredAt = 0L,
    )

    private companion object {
        const val MANGA_ID = "manga"
        const val CHAPTER_ID = "chapter-1"
    }
}
