package com.lmreader.core.model

import kotlinx.coroutines.flow.Flow

/**
 * 来源仓储契约（框架 3.6）。实现在 `core:database`。
 *
 * 顺带说明为什么接口在 `core:model` 而不是 `core:database`：扫描调度（`core:storage`）
 * 与 UI（`:app`）都要用同一组签名，若接口跟着 Room 走，双方都会被拖去依赖数据库模块。
 */
interface SourceRepository {
    /**
     * 全部路径来源，按用户排序（`orderIndex`）。
     *
     * 只有**一张**路径表：一次遍历同时解释图片目录与 CBZ/ZIP/PDF，来源的 `kind`
     * 不再区分表，只保留为身份与徽标（见 `SourceKind`）。
     */
    fun observeSources(): Flow<List<LibrarySource>>
    suspend fun getSources(): List<LibrarySource>
    suspend fun saveSource(source: LibrarySource): LibrarySource
    suspend fun deleteSource(sourceId: String)
    suspend fun getSource(sourceId: String): LibrarySource?

    /** 拖动排序：立即持久化（开发文档 4.1）。 */
    suspend fun reorder(orderedSourceIds: List<String>)
    suspend fun updateScanResult(sourceId: String, at: Long, status: ScanRunStatus, error: String?)
    suspend fun updatePermission(sourceId: String, permission: SourcePermissionState)
}

/**
 * 漫画仓储契约（框架 3.6）。实现在 `core:database`。
 */
interface MangaRepository {
    /**
     * 按当前来源顺序与自然名称排序取一页；只返回已发现条目，不阻塞扫描（开发文档 6.4）。
     *
     * @param sourceFilter 图源筛选：`null` = 不筛选（显示全部）。**空集合表示"没有
     * 匹配项"**而不是"显示全部"——仓储不做这层解释，否则"用户取消了所有勾选"与
     * "用户没有筛选"无法区分；界面负责决定空筛选按哪种语义处理。
     */
    suspend fun pageLibrary(
        offset: Int,
        limit: Int,
        sourceFilter: Set<String>? = null,
    ): MangaPage

    suspend fun pageShelf(categoryId: Long?, offset: Int, limit: Int): MangaPage

    /** 订阅「可见集合长度」变化，用于扫描过程中把新条目补进当前额度（开发文档 6.4）。 */
    fun observeVisibleCount(inShelfOnly: Boolean, categoryId: Long?): Flow<Int>

    /**
     * 各来源当前可见卡片数。
     *
     * 图源筛选栏必须读取持久索引，而不是读取本进程的扫描状态；否则冷启动按要求
     * 不自动扫描时，每个来源都会错误显示“共 0 项”。
     */
    fun observeVisibleCountsBySource(): Flow<Map<String, Int>>

    /**
     * 扫描写入进度信号，只增不减；每次插入一行漫画就发射一次。
     *
     * 与 [observeVisibleCount] 的分工：后者给出「现在该显示多少」（绝对值，会因删除
     * 与重扫上下波动），本方法给出「又有新条目落库了」（单调触发信号）。UI 在额度
     * 还有空位时用本信号补入下一批，避免用计数比较来推断"是否有新增"。
     */
    fun observeDiscoveryProgress(): Flow<Long>

    suspend fun getCards(mangaIds: List<String>): List<MangaCard>

    /**
     * 取补全阶段需要的工作投影（开发文档 6.1 第 2 步）。
     *
     * 返回 null 表示漫画行已不存在（例如用户刚删掉了整个来源）。
     */
    suspend fun getBackfillTarget(mangaId: String): MangaBackfillTarget?

    /** 应用一次补全结果；`null` 字段表示"本次没有新值"，不覆盖已有值。 */
    suspend fun applyMetadataUpdate(update: MangaMetadataUpdate)

    /**
     * 尚未补全的漫画 ID（缺封面或缺 XML 简介），按分页顺序返回。
     *
     * 补全不能只在"当前可见卡片"上做：开发文档 6.4 要求「后台补全扫描不以已显示
     * 卡片为前提」，否则只有滚到过的作品才可被简介搜索命中（验收 A10）。
     */
    suspend fun pendingBackfillIds(limit: Int): List<String>

    /** 来源不可用时把其下漫画标灰，但**保留**卡片与用户数据（开发文档 8.2）。 */
    suspend fun setAvailabilityBySource(sourceId: String, availability: MangaAvailability)

    /**
     * 把该来源里"本轮没有被再发现"的卡片标成 [MangaAvailability.STALE]。
     *
     * 调用时机只有一个：该来源的扫描**完整跑完**（`ScanSummary.completed = true`）之后。
     * 取消、目录读取失败、授权失效时**不得**调用——那会把"这次没读到"误判成"已经不存在"
     * （验收 A07「失败不删索引」）。开发文档 6.2 的删除判定依据同源：只有完整枚举过的
     * 容器才能用来判断"什么已经消失"。
     *
     * 为什么用 generation 而不是回传一份 ID 清单：发现阶段每次写入都已经把本次
     * generation 写进卡片行，一条 `UPDATE ... WHERE discoveryGeneration != :generation`
     * 就能表达"本轮没再发现"，万级来源也不必构造超长 `IN (...)`。
     *
     * 只改 `availability`，不删行：书架关系、章节、阅读进度与译文全部保留，
     * 卡片被重新发现时会自动回到 [MangaAvailability.AVAILABLE]。
     *
     * @return 本次**新**标成陈旧的卡片数（已经是陈旧的不重复计数）。
     */
    suspend fun markUndiscoveredAsStale(sourceId: String, generation: Long): Int

    /**
     * 把"来源行已经不存在"的卡片标成陈旧（孤儿卡片清扫），返回本次新标的数量。
     *
     * 为什么需要单独一条：`mangas` 没有指向 `library_sources` 的外键，删除一条路径
     * 不会带走它的卡片。真机实测某次图库里 4749 张卡片中有 4595 张是这种孤儿，
     * 界面因此完全没法看。启动时跑一次把它们从图库/书架隐藏（仍然只改可用性、
     * 不删行，用户把原目录加回来再扫一次就会全部恢复）。
     */
    suspend fun markOrphanedAsStale(): Int

    /** 批量取章节；按自然序（`sortKey`）返回，封面取第一章不依赖调用方再排序。 */
    suspend fun getChapters(mangaId: String): List<ChapterRecord>

    /** 页源完整枚举后回填页数和首页；不改变章节身份。 */
    suspend fun updateChapterPageInfo(chapterId: String, pageCount: Int, coverDocumentId: String?)

    /**
     * 落库一次扫描结果。必须在单个事务内完成，并且只有 `completed = true` 的扫描
     * 才允许删除章节（框架 5.2）。
     */
    suspend fun upsertScanResult(result: ScanResult): ScanPersistReport
    suspend fun search(query: String, offset: Int, limit: Int): MangaPage
    suspend fun deleteManga(mangaId: String)
    suspend fun observeTotalCount(): Flow<Int>
}

/**
 * 书架仓储契约（框架 3.6）。实现在 `core:database`。
 */
interface ShelfRepository {
    fun observeCategories(): Flow<List<Category>>

    /** 取内置「未分类」的 categoryId（0），首次调用时确保它存在（框架 5.3）。 */
    suspend fun ensureUncategorized(): Long
    suspend fun createCategory(name: String): Category
    suspend fun renameCategory(categoryId: Long, name: String)
    suspend fun updateCategoryStyle(categoryId: Long, mode: StyleMode, customStyle: String?)
    suspend fun deleteCategory(categoryId: Long)

    /** 删除分类后其收藏移到未分类，不删漫画（开发文档 8.2）。 */
    suspend fun reorderCategories(orderedIds: List<Long>)
    suspend fun addToShelf(mangaId: String, categoryId: Long)
    suspend fun removeFromShelf(mangaId: String)

    /**
     * 书架条目数。
     *
     * 刻意**不是** suspend：它返回的是冷流，订阅本身不挂起，做成 suspend 只会
     * 强迫调用方在没有必要时也进入协程（分类侧栏在 Compose 里直接 collect）。
     */
    fun observeShelfCount(categoryId: Long?): Flow<Int>
}

/** 阅读恢复点与派生索引分开，重扫不得删除（开发文档 15.3）。 */
interface ReadingProgressRepository {
    suspend fun get(mangaId: String): ReadingProgress?
    suspend fun save(progress: ReadingProgress)
}
