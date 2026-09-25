package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.ChapterEntity

/**
 * 章节读写（开发文档 15.3）。
 *
 * 章节的物理定位键是 `(documentId, kind)`：同一目录既可能是图片章节也可能被
 * 归档表识别，唯一键不能只用 documentId。
 */
@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY sortKey ASC, chapterId ASC")
    suspend fun getByManga(mangaId: String): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM chapters WHERE mangaId = :mangaId")
    suspend fun countByManga(mangaId: String): Int

    @Query("SELECT * FROM chapters WHERE chapterId = :chapterId")
    suspend fun getById(chapterId: String): ChapterEntity?

    /** 补全阶段设置章节的页数/封面（首图）；`null` 表示本次没有读到新值。 */
    @Query(
        """
        UPDATE chapters
        SET pageCount = COALESCE(:pageCount, pageCount),
            coverDocumentId = COALESCE(:coverDocumentId, coverDocumentId)
        WHERE chapterId = :chapterId
        """,
    )
    suspend fun updateDerivedFields(chapterId: String, pageCount: Int?, coverDocumentId: String?)

    /** 本次扫描中该漫画仍然存在的章节 documentId 集合，用于判定删除（开发文档 6.2）。 */
    @Query("SELECT documentId FROM chapters WHERE mangaId = :mangaId")
    suspend fun documentIdsOf(mangaId: String): List<String>

    @Query("DELETE FROM chapters WHERE mangaId = :mangaId AND documentId IN (:documentIds)")
    suspend fun deleteByDocumentIds(mangaId: String, documentIds: List<String>)

    @Query("DELETE FROM chapters WHERE mangaId = :mangaId")
    suspend fun deleteByManga(mangaId: String)

    /** 章节数变化后回填漫画行的统计列，避免 UI 用 COUNT 子查询逐卡片统计。 */
    @Query(
        """
        UPDATE mangas
        SET chapterCount = (SELECT COUNT(*) FROM chapters WHERE mangaId = :mangaId),
            chapterCountKnown = 1
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun refreshChapterCount(mangaId: String)
}
