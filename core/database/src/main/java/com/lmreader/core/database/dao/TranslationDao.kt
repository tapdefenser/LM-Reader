package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.entity.MangaGlossaryEntity
import kotlinx.coroutines.flow.Flow

/** 侧栏「翻译队列」角标用的计数投影。 */
data class PendingTranslationRow(
    val chapterId: String,
)

/**
 * 翻译数据读写（阶段 2 的待翻译队列与漫画译名字典）。
 *
 * 状态列存 `TranslationState` 的**名字**而不是序数：枚举增删后序数会悄悄指向另一个值，
 * 而这种错误没有任何报错（与阅读覆盖列同一个理由）。
 */
@Dao
interface TranslationDao {

    @Query(
        """
        SELECT * FROM chapter_translation
        WHERE mangaId = :mangaId AND targetLanguage = :targetLanguage
        """,
    )
    suspend fun byManga(mangaId: String, targetLanguage: String): List<ChapterTranslationEntity>

    @Query("SELECT * FROM chapter_translation WHERE chapterId = :chapterId")
    suspend fun byChapter(chapterId: String): List<ChapterTranslationEntity>

    /** 批量取这些章节的全部翻译记录（可选目标语言）；入队前的状态判定用一次查询取齐。 */
    @Query("SELECT * FROM chapter_translation WHERE chapterId IN (:chapterIds)")
    suspend fun byChapters(chapterIds: List<String>): List<ChapterTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChapterTranslationEntity>)

    @Query(
        """
        DELETE FROM chapter_translation
        WHERE chapterId IN (:chapterIds) AND targetLanguage = :targetLanguage
        """,
    )
    suspend fun deleteAll(chapterIds: List<String>, targetLanguage: String)

    /** 待翻译（含翻译中）：侧栏角标。 */
    @Query("SELECT chapterId FROM chapter_translation WHERE state IN ('PENDING', 'RUNNING')")
    fun observePending(): Flow<List<PendingTranslationRow>>

    @Query("SELECT COUNT(*) FROM chapter_translation WHERE state IN ('PENDING', 'RUNNING')")
    suspend fun pendingCount(): Int

    @Query(
        """
        SELECT * FROM manga_glossary
        WHERE mangaId = :mangaId AND targetLanguage = :targetLanguage
        ORDER BY source ASC
        """,
    )
    suspend fun glossary(mangaId: String, targetLanguage: String): List<MangaGlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossary(entities: List<MangaGlossaryEntity>)

    @Query(
        """
        DELETE FROM manga_glossary
        WHERE mangaId = :mangaId AND targetLanguage = :targetLanguage AND source = :source
        """,
    )
    suspend fun deleteGlossary(mangaId: String, targetLanguage: String, source: String)
}
