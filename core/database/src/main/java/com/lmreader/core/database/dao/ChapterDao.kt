package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.ChapterEntity

/** 章节 → 所属漫画 的投影（见 [ChapterDao.mangaIdsOf]）。 */
data class ChapterOwnerRow(
    val chapterId: String,
    val mangaId: String,
)

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

    /**
     * 该漫画的章节，**按自然序**（`sortKey`）。
     *
     * 这里刻意**不**按 `position`：封面（开发文档 7.2）与简介（7.1 第 2 条）都取
     * "自然序第一章"，不能因为用户手动拖过章节就换一章。显示顺序见 [getByMangaInDisplayOrder]。
     */
    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY sortKey ASC, chapterId ASC")
    suspend fun getByManga(mangaId: String): List<ChapterEntity>

    /** 详情页章节列表：**按用户看到的顺序**（`position`），同位置时退回自然序保证确定。 */
    @Query(
        """
        SELECT * FROM chapters
        WHERE mangaId = :mangaId
        ORDER BY position ASC, sortKey ASC, chapterId ASC
        """,
    )
    suspend fun getByMangaInDisplayOrder(mangaId: String): List<ChapterEntity>

    /**
     * 批量取这些漫画的全部章节（按漫画 + 自然序）。
     *
     * 封面懒加载一次要处理一批 30 张卡片，逐张 `getByManga` 就是 30 次往返；
     * 一次取回再在内存里按 mangaId 分组取第一条，正好得到"每部漫画的第一章"。
     * 排序与 [getByManga] 完全一致——两处顺序不同会让封面与详情页显示的第一章不是同一个。
     */
    @Query(
        """
        SELECT * FROM chapters
        WHERE mangaId IN (:mangaIds)
        ORDER BY mangaId ASC, sortKey ASC, chapterId ASC
        """,
    )
    suspend fun getByMangas(mangaIds: List<String>): List<ChapterEntity>

    /** 写一次显示位置（排序抽屉重排 / 手动拖动 / 发现新章节都走它）。 */
    @Query("UPDATE chapters SET position = :position WHERE chapterId = :chapterId")
    suspend fun updatePosition(chapterId: String, position: Long)

    /** 该漫画当前最大的显示位置；空表返回 null（新章节追加时用）。 */
    @Query("SELECT MAX(position) FROM chapters WHERE mangaId = :mangaId")
    suspend fun maxPosition(mangaId: String): Long?

    /** 该漫画的章节 id 集合；整表重排前的完整性校验用（见 `setChapterOrder`）。 */
    @Query("SELECT chapterId FROM chapters WHERE mangaId = :mangaId")
    suspend fun chapterIdsOf(mangaId: String): List<String>

    /**
     * 这些章节各自属于哪部漫画（已读标记要带 mangaId，而外键要求章节行存在）。
     *
     * 一次批量取而不是逐章 `getById`：多选可以一次选上百章。
     */
    @Query("SELECT chapterId, mangaId FROM chapters WHERE chapterId IN (:chapterIds)")
    suspend fun mangaIdsOf(chapterIds: List<String>): List<ChapterOwnerRow>

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

    /**
     * 写入"已发现的章节数"，但**保留** `chapterCountKnown = 0`。
     *
     * 使用场景：多章节发现阶段只探测到第一个章节（用户要求不遍历所有章节文件夹），
     * 因此这里记录的是下限。界面据此显示「已发现 N 章，更新中」而不是「共 N 章」
     * （开发文档 5.1、8.1 明确禁止把探测到一章伪报成完整的一章）。
     */
    @Query(
        """
        UPDATE mangas
        SET chapterCount = :discovered,
            chapterCountKnown = 0
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun markChapterCountUnknown(mangaId: String, discovered: Int)
}
