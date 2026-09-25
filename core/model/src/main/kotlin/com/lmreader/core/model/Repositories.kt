package com.lmreader.core.model

import kotlinx.coroutines.flow.Flow

/**
 * 来源仓储契约（框架 3.6）。实现在 `core:database`。
 *
 * 顺带说明为什么接口在 `core:model` 而不是 `core:database`：扫描调度（`core:storage`）
 * 与 UI（`:app`）都要用同一组签名，若接口跟着 Room 走，双方都会被拖去依赖数据库模块。
 */
interface SourceRepository {
    fun observeSources(kind: SourceKind): Flow<List<LibrarySource>>
    suspend fun getSources(kind: SourceKind): List<LibrarySource>
    suspend fun saveSource(source: LibrarySource): LibrarySource
    suspend fun deleteSource(sourceId: String)
    suspend fun getSource(sourceId: String): LibrarySource?

    /** 拖动排序：立即持久化，只在同表内移动（开发文档 4.1）。 */
    suspend fun reorder(kind: SourceKind, orderedSourceIds: List<String>)
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

    /** 批量取章节；按自然序（`sortKey`）返回，封面取第一章不依赖调用方再排序。 */
    suspend fun getChapters(mangaId: String): List<ChapterRecord>

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
