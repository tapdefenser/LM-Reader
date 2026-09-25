package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.MangaEntity
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.SourceKind
import kotlinx.coroutines.flow.Flow

/**
 * 卡片投影行：图库/书架列表一次查询取齐（开发文档 8.1）。
 *
 * 列名必须与下面的 SQL 别名一致；Room 只按名字匹配，改名不会编译失败，
 * 只会在运行时抛缺列异常，所以两处改动必须同时进行。
 */
data class CardQueryRow(
    val mangaId: String,
    val displayName: String,
    val summaryPreview: String?,
    val sourceId: String,
    val coverDocumentId: String?,
    val coverChapterId: String?,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    val availability: MangaAvailability,
    /** null = 不在书架；用于推导 `MangaCard.inShelf`。 */
    val shelfCategoryId: Long?,
)

/**
 * 漫画与卡片的查询（开发文档 6.4、8.1）。
 *
 * 分页用 `LIMIT/OFFSET`（框架 9.2 的已知限制），排序键固定在
 * `sourceOrderIndex → sortKey → mangaId`：前两列分别表达「源顺序」与「自然名称」，
 * 最后一列保证同序时结果稳定，否则翻页会出现重复与丢失。
 */
@Dao
interface MangaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MangaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: MangaEntity): Long

    @Query("SELECT * FROM mangas WHERE mangaId = :mangaId")
    suspend fun getById(mangaId: String): MangaEntity?

    @Query("SELECT * FROM mangas WHERE anchorDocumentId = :documentId AND sourceKind = :kind")
    suspend fun getByAnchor(documentId: String, kind: SourceKind): MangaEntity?

    /**
     * 可见卡片总数。
     *
     * `availability != 'STALE'`：陈旧卡片（本来源最近一次完整扫描没有再发现的旧卡片）
     * 默认不出现在图库/书架，总数必须与列表同一口径，否则界面会显示"共 N 项"却只滚
     * 得到更少的卡片。
     */
    @Query("SELECT COUNT(*) FROM mangas WHERE availability != 'STALE'")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM mangas WHERE availability != 'STALE'")
    fun observeCount(): Flow<Int>

    /**
     * 单调递增的发现进度。
     *
     * 为什么不用 `COUNT(*)` 当刷新信号：扫描过程中条目数会因为重扫去重与删除
     * 忽增忽减，用它触发"还有空位就补入"的判断会漏掉批次；rowid 只会增长，
     * 每插入一行就变一次，正好对应"发现阶段批量写数据库"（开发文档 6.1）。
     */
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM mangas")
    fun observeDiscoveryProgress(): Flow<Long>

    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.availability AS availability,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageLibrary(offset: Int, limit: Int): List<CardQueryRow>

    /**
     * 图库分页 + 图源筛选。
     *
     * 筛选放在 SQL 里而不是取回后内存过滤：万级图库只勾选一个来源时，
     * 内存过滤要先读回全部行再丢弃，等于把分页的意义抹掉（开发文档 6.4）。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.availability AS availability,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.sourceId IN (:sourceIds)
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageLibraryFiltered(sourceIds: List<String>, offset: Int, limit: Int): List<CardQueryRow>

    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.availability AS availability,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageShelf(offset: Int, limit: Int): List<CardQueryRow>

    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.availability AS availability,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE s.categoryId = :categoryId
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageShelfInCategory(categoryId: Long, offset: Int, limit: Int): List<CardQueryRow>

    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.availability AS availability,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.mangaId IN (:mangaIds)
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        """,
    )
    suspend fun cardsByIds(mangaIds: List<String>): List<CardQueryRow>

    /**
     * 关键字搜索。
     *
     * 本步只做漫画名与 `normalized_search_text` 的子串匹配（框架 9.1 的已知限制）：
     * 开发文档 6.4 的 2-gram 侧表与 `MangaSearchDocument` 投影是 P1 项，未实现。
     * 因此这里**不能**声称万级全库搜索已达性能目标。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.availability AS availability,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE (m.displayName LIKE :pattern ESCAPE '\'
           OR md.normalizedSearchText LIKE :pattern ESCAPE '\')
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(pattern: String, offset: Int, limit: Int): List<CardQueryRow>

    /**
     * 书架条目数。陈旧卡片不算进去：它们默认不出现在书架上（行本身保留，
     * 卡片被重新发现时会自动回到书架）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM shelf_entries AS e
        JOIN mangas AS m ON m.mangaId = e.mangaId
        WHERE m.availability != 'STALE'
        """,
    )
    fun observeShelfTotal(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM shelf_entries AS e
        JOIN mangas AS m ON m.mangaId = e.mangaId
        WHERE e.categoryId = :categoryId
          AND m.availability != 'STALE'
        """,
    )
    fun observeShelfCountInCategory(categoryId: Long): Flow<Int>

    @Query("DELETE FROM mangas WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)

    /**
     * 补全阶段写回派生字段。
     *
     * 用 `COALESCE(:value, 原值)` 而不是直接赋值：补全可能只拿到封面、没拿到简介
     * （首章目录临时不可读），直接覆盖会把已经显示的简介清空。
     * `hasMetadata` 用 OR 同理——读过一次 XML 就不该因为本次没读到而退回"无简介"。
     */
    @Query(
        """
        UPDATE mangas
        SET coverDocumentId = COALESCE(:coverDocumentId, coverDocumentId),
            coverChapterId = COALESCE(:coverChapterId, coverChapterId),
            summary = COALESCE(:summary, summary),
            author = COALESCE(:author, author),
            hasMetadata = CASE WHEN :hasMetadata = 1 THEN 1 ELSE hasMetadata END,
            updatedAt = :at
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun updateDerivedFields(
        mangaId: String,
        coverDocumentId: String?,
        coverChapterId: String?,
        summary: String?,
        author: String?,
        hasMetadata: Boolean,
        at: Long,
    )

    @Query("SELECT * FROM mangas WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: String): List<MangaEntity>

    /**
     * 待补全的漫画：缺封面或缺 XML。
     *
     * 用 `hasMetadata = 0 OR coverDocumentId IS NULL` 而不是"上次扫描之后新增的"：
     * 补全可能因为权限或解码失败中断，未完成的条目必须能再次被选中，否则它永远
     * 停在"无简介"状态且不会被搜索命中（验收 A10）。
     */
    @Query(
        """
        SELECT mangaId FROM mangas
        WHERE availability = 'AVAILABLE'
          AND (hasMetadata = 0 OR coverDocumentId IS NULL)
        ORDER BY sourceOrderIndex ASC, sortKey ASC, mangaId ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingBackfillIds(limit: Int): List<String>

    /**
     * 来源顺序变化时重排冗余列。
     *
     * 与 `library_sources.orderIndex` 必须成对更新，否则图库排序会与路径表不一致
     * （开发文档 4.1 拖动排序、6.4 有效源顺序）。
     */
    @Query("UPDATE mangas SET sourceOrderIndex = :orderIndex WHERE sourceId = :sourceId")
    suspend fun updateSourceOrder(sourceId: String, orderIndex: Int)

    @Query("UPDATE mangas SET availability = :availability WHERE sourceId = :sourceId")
    suspend fun updateAvailabilityBySource(sourceId: String, availability: MangaAvailability)

    /**
     * 把该来源里"不是本轮发现的"卡片标成陈旧（[MangaAvailability.STALE]）。
     *
     * 只在来源扫描**完整跑完**之后调用（调用点见
     * [com.lmreader.core.model.MangaRepository.markUndiscoveredAsStale]）：取消、目录读取
     * 失败、授权失效时一律不能调用，否则会把"这次没读到"误判成"已经不存在"（验收 A07）。
     *
     * `discoveryGeneration` 是发现阶段每次写入都带上的本次扫描代次，所以"本轮是否发现"
     * 不需要回传一份 ID 清单；`availability != 'STALE'` 让返回值正好等于"本次新隐藏的
     * 数量"，重复调用不会把同一批卡片反复计数。
     *
     * 只改可用性、不删行：章节、书架关系、阅读进度与译文全部保留（外键级联删除会
     * 把这些一起带走，因此这里绝不能改成 DELETE）。
     */
    @Query(
        """
        UPDATE mangas
        SET availability = 'STALE'
        WHERE sourceId = :sourceId
          AND discoveryGeneration != :generation
          AND availability != 'STALE'
        """,
    )
    suspend fun markUndiscoveredAsStale(sourceId: String, generation: Long): Int

    /**
     * 把一个来源的**全部**卡片标成陈旧；删除该来源行之前调用。
     *
     * 为什么不是删行：用户可能只是想换一个目录，过一会儿又把原目录加回来。漫画行
     * 按稳定 ID 保存，加回来再扫一次就会重新发现同一批 ID，卡片、书架关系、阅读进度
     * 与译文都会原样回来；删行则会级联带走这些用户数据（开发文档 4.1「授权在所有
     * 引用释放后再释放」的同一思路：先保住数据，再谈清理）。
     */
    @Query("UPDATE mangas SET availability = 'STALE' WHERE sourceId = :sourceId AND availability != 'STALE'")
    suspend fun markSourceAsStale(sourceId: String): Int

    /**
     * 把"来源行已经不存在的"卡片标成陈旧（孤儿卡片清扫）。
     *
     * 历史遗留：`mangas` 没有指向 `library_sources` 的外键，早期版本删掉一条路径之后
     * 卡片会永远留在图库里（真机实测 4749 张卡片里 4595 张是这种孤儿）。这条清扫在
     * 启动时跑一次，把它们归入陈旧、从图库/书架隐藏，但**不删行**。
     */
    @Query(
        """
        UPDATE mangas
        SET availability = 'STALE'
        WHERE availability != 'STALE'
          AND sourceId NOT IN (SELECT sourceId FROM library_sources)
        """,
    )
    suspend fun markOrphanedAsStale(): Int
}
