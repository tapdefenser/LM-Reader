package com.lmreader.ui.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.Category
import com.lmreader.core.model.LibraryDisplayMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ResolvedCover
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.model.StyleMode
import com.lmreader.core.storage.cover.CoverMetadataWriter
import com.lmreader.core.storage.cover.CoverResolver
import com.lmreader.core.storage.scan.ChapterSyncOutcome
import com.lmreader.core.storage.scan.MangaChapterSyncer
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.ui.common.CoverProbeQueue
import com.lmreader.ui.paging.PageSlice
import com.lmreader.ui.paging.PagingState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书架页（开发文档 8.2）。
 *
 * 书架是**收藏引用**，不复制漫画原文件；移出书架只删收藏关系，漫画仍在图库
 * （开发文档 7 段首、8.2）。因此这里的所有操作都不触碰源目录。
 */
class BookshelfViewModel(
    private val mangaRepository: MangaRepository,
    private val shelfRepository: ShelfRepository,
    private val chapterSyncer: MangaChapterSyncer,
    private val preferences: AppPreferences,
    private val coverResolver: CoverResolver,
    private val coverMetadataWriter: CoverMetadataWriter,
) : ViewModel() {

    private val paging = PagingState<MangaCard>(idOf = { it.mangaId })

    private val _state = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = _state.asStateFlow()

    /** 封面懒加载队列；与图库共用同一套规则与实现（见 [CoverProbeQueue]）。 */
    private val coverProbes = CoverProbeQueue(
        scope = viewModelScope,
        probeTargets = mangaRepository::coverProbeTargets,
        resolve = coverResolver::resolve,
        // 与图库同一条落库路径：写封面的同时更新简介（用户要求）。
        persist = { mangaId, cover, at -> coverMetadataWriter.write(mangaId, cover, at) },
        onResolved = ::onCoverResolved,
    )

    /** 防抖用的查询流；见 [onQueryChange]。 */
    private val queryFlow = MutableStateFlow("")

    init {
        paging.requestInitial()
        viewModelScope.launch {
            shelfRepository.ensureUncategorized()
            shelfRepository.observeCategories().collect { categories ->
                _state.update { current ->
                    // 选中的分类被删除时回退到"全部"，而不是显示空列表（开发文档 8.2）。
                    val stillExists = categories.any { it.categoryId == current.selectedCategoryId }
                    current.copy(
                        categories = categories,
                        selectedCategoryId = if (stillExists) current.selectedCategoryId else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            preferences.libraryDisplayMode.collect { mode -> _state.update { it.copy(displayMode = mode) } }
        }
        viewModelScope.launch {
            mangaRepository.observeDiscoveryProgress().collect {
                if (paging.hasFreeSlot()) loadMore()
            }
        }
        // 可见收藏**变化**时重建分页会话。
        //
        // 两种变化都要管，而它们的原因是相反的：
        // - **变小**：扫描完整结束后把"本轮没再发现"的旧卡片标成陈旧。已经加载进内存的
        //   卡片不会自己消失，不重建的话用户点过刷新仍会看到不该出现的旧卡片。
        // - **变大**：用户在图库里把一部漫画加入书架。早先只处理"变小"，于是**新加入的
        //   漫画不会出现在书架上**——列表是分页读出来的快照，没人去重读它（真机反馈）。
        viewModelScope.launch {
            _state.map { it.selectedCategoryId }.distinctUntilChanged().collectLatest { categoryId ->
                var previous = -1
                mangaRepository.observeVisibleCount(inShelfOnly = true, categoryId = categoryId)
                    .collect { visible ->
                        val grew = previous >= 0 && visible > previous
                        val shrank = previous >= 0 && visible < previous
                        previous = visible
                        if (grew) {
                            resetSession()
                            loadMore()
                            return@collect
                        }
                        if (!shrank) return@collect
                        val loaded = _state.value.items
                        if (loaded.isEmpty()) return@collect
                        val hasStaleCard = mangaRepository.getCards(loaded.map { it.mangaId })
                            .any { it.availability == MangaAvailability.STALE }
                        if (hasStaleCard) {
                            resetSession()
                            loadMore()
                        }
                    }
            }
        }
        // 冷启动进书架同样**只读本地缓存**，不触发扫描（用户要求）。
        // 书架是"已收藏"的引用，用户打开它是为了继续读，不是为了等索引更新；
        // 需要更新时由用户点刷新（开发文档 6.3）。
        viewModelScope.launch {
            loadMore()
        }
        viewModelScope.launch { observeQuery() }
    }

    /**
     * 搜索输入：300ms 防抖之后才查数据库。
     *
     * 与图库同一个坑，这里刻意照同样的方式分开：**输入框的值只由 [onQueryChange] 同步更新**，
     * 防抖回调只写 [BookshelfUiState.appliedQuery]。若把防抖之后的文本写回 `query`，
     * 用户连打几个字符时那次延迟发射会带着旧文本覆盖输入框，并把光标重置到开头
     * （真机症状："每次只能输入一个字符，光标跑到前面去"）。
     */
    @OptIn(FlowPreview::class)
    private suspend fun observeQuery() {
        queryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { query ->
                resetSession()
                _state.update { it.copy(appliedQuery = query) }
                loadMore()
            }
    }

    /** 搜索框输入；同步更新输入框的值，防抖查询交给 [queryFlow]。 */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    fun selectCategory(categoryId: Long?) {
        if (_state.value.selectedCategoryId == categoryId) return
        _state.update { it.copy(selectedCategoryId = categoryId, sidePanelOpen = false) }
        viewModelScope.launch {
            resetSession()
            loadMore()
        }
    }

    fun setSidePanelOpen(open: Boolean) {
        _state.update { it.copy(sidePanelOpen = open) }
    }

    fun onLoadMore() {
        paging.requestNextBatch()
        viewModelScope.launch { loadMore() }
    }

    /**
     * 一键更新**当前筛选下书架里所有漫画的章节**。
     *
     * ## 这个按钮不是"刷新书架"
     *
     * 书架列表是数据库里的收藏引用，它自己会跟着数据变（见 `init` 里对可见数量的订阅），
     * 不需要用户手动刷新。用户点这个按钮要的是另一件事：**把书架里这些漫画的章节目录
     * 重新读一遍**，好让新下载/新加进来的章节出现在详情页与阅读器里。
     *
     * 因此这里：
     * - 逐个调 [MangaChapterSyncer.sync]（与详情页「更新章节」同一条路径），
     *   **不**调 `scanCoordinator.rescanAll`——那是"重扫整库目录"，会把用户没在看的
     *   来源也一起翻一遍，既慢又不是用户想要的；
     * - 范围取"当前筛选下的全部收藏"（分类 + 关键词），用户勾了筛选就只更新那些；
     * - 更新完重建列表，因为章节数变了，卡片上的"共 N 章"要跟着变。
     */
    fun onRefresh() {
        if (_state.value.chapterUpdateTotal > 0) return // 已经在更新了，别重复排队
        val categoryId = _state.value.selectedCategoryId
        val query = _state.value.appliedQuery
        viewModelScope.launch {
            val ids = runCatching { mangaRepository.shelfMangaIds(categoryId, query) }
                .getOrElse { error ->
                    _state.update { it.copy(error = error.message ?: "读取书架失败") }
                    return@launch
                }
            if (ids.isEmpty()) {
                _state.update { it.copy(chapterUpdateMessage = "书架里没有可更新的漫画") }
                return@launch
            }
            _state.update {
                it.copy(
                    chapterUpdateDone = 0,
                    chapterUpdateTotal = ids.size,
                    chapterUpdateMessage = null,
                )
            }
            var failed = 0
            for ((index, mangaId) in ids.withIndex()) {
                // 逐个更新而不是并发：每部漫画都要枚举自己的目录，并发几十个只会
                // 让 SAF 与磁盘互相抢，而用户要的是"更新完"，不是"最快更新完"。
                val outcome = runCatching { chapterSyncer.sync(mangaId) }.getOrNull()
                if (outcome !is ChapterSyncOutcome.Success) failed++
                _state.update { it.copy(chapterUpdateDone = index + 1) }
            }
            _state.update {
                it.copy(
                    chapterUpdateTotal = 0,
                    chapterUpdateMessage = if (failed == 0) {
                        "已更新 ${ids.size} 部漫画的章节"
                    } else {
                        "已更新 ${ids.size - failed} 部，${failed} 部失败"
                    },
                )
            }
            resetSession()
            loadMore()
        }
    }

    /** 清掉一键更新完成后的提示（用户看过即可）。 */
    fun dismissChapterUpdateMessage() {
        if (_state.value.chapterUpdateMessage == null) return
        _state.update { it.copy(chapterUpdateMessage = null) }
    }

    /**
     * 重新读取当前筛选下的书架列表。
     *
     * 与 [onRefresh] 是**两件事**：这里只重读数据库（列表读失败时的「重试」走这里），
     * 不碰任何漫画的章节目录。把两者合成一个动作正是"刷新键语义不清"的来源。
     */
    fun reload() {
        viewModelScope.launch {
            resetSession()
            loadMore()
        }
    }

    /** 新建分类：名称必填 1–40 字符、同名禁止（开发文档 8.2）。 */
    fun createCategory(name: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { shelfRepository.createCategory(name) }
                .onFailure { onError(it.message ?: "无法创建分类") }
        }
    }

    fun renameCategory(categoryId: Long, name: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { shelfRepository.renameCategory(categoryId, name) }
                .onFailure { onError(it.message ?: "无法重命名分类") }
        }
    }

    /**
     * 修改分类文风。
     *
     * 这一步只写分类记录，**不自动重译**：开发文档 8.2 明确"保存后统一更新分类
     * 名称与文风版本；不会自动重译"，已提交任务用其快照。
     */
    fun updateCategoryStyle(categoryId: Long, mode: StyleMode, customStyle: String?) {
        viewModelScope.launch {
            shelfRepository.updateCategoryStyle(categoryId, mode, customStyle)
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            shelfRepository.deleteCategory(categoryId)
            resetSession()
            loadMore()
        }
    }

    fun moveCategory(categoryId: Long, delta: Int) {
        val categories = _state.value.categories
        val index = categories.indexOfFirst { it.categoryId == categoryId }
        val target = index + delta
        if (index < 0 || target !in categories.indices) return
        val reordered = categories.toMutableList().apply { add(target, removeAt(index)) }
        viewModelScope.launch { shelfRepository.reorderCategories(reordered.map { it.categoryId }) }
    }

    fun removeFromShelf(mangaId: String) {
        viewModelScope.launch {
            shelfRepository.removeFromShelf(mangaId)
            resetSession()
            loadMore()
        }
    }

    fun moveToCategory(mangaId: String, categoryId: Long) {
        viewModelScope.launch {
            shelfRepository.addToShelf(mangaId, categoryId)
            resetSession()
            loadMore()
        }
    }

    private suspend fun loadMore() {
        if (!paging.needsMore()) return
        paging.beginLoad()
        _state.update { it.copy(loading = true) }
        try {
            val page = mangaRepository.pageShelf(
                categoryId = _state.value.selectedCategoryId,
                offset = paging.nextOffset,
                limit = PAGE_SIZE,
                query = _state.value.appliedQuery,
            )
            paging.append(
                PageSlice(page.items, page.nextOffset, page.exhausted),
                totalKnown = page.totalKnown,
            )
            _state.update {
                it.copy(items = paging.items.toList(), loading = false, exhausted = paging.exhausted, error = null)
            }
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = error.message ?: "读取书架失败") }
        } finally {
            paging.endLoad()
        }
    }

    /**
     * 重建分页会话（必须与 `paging.reset()` 成对）。
     *
     * 换分类、换搜索词、增删收藏之后，旧的封面探测队列指向的是已经不在屏幕上的卡片，
     * 继续取只是白花目录枚举。
     */
    private fun resetSession() {
        paging.reset()
        coverProbes.clear()
    }

    /**
     * 上报当前可见的卡片下标（与图库同一套规则：滚动到可见才取封面）。
     *
     * 书架通常只有几十项，但"取过一次就不再取"的收益是一样的：一份 300 部的收藏
     * 若每次打开都重取，代价就是 300 次目录枚举。
     */
    fun onCardsVisible(indices: List<Int>) {
        if (indices.isEmpty()) return
        val items = paging.items
        val needsProbe = ArrayList<String>(indices.size)
        for (index in indices) {
            val card = items.getOrNull(index) ?: continue
            if (card.coverDocumentId != null || card.coverProbedAt != null) continue
            needsProbe += card.mangaId
        }
        coverProbes.request(needsProbe)
    }

    /** 封面探测完成：就地刷新那一张卡片，不重建分页会话（重建会打回第一页）。 */
    private fun onCoverResolved(mangaId: String, cover: ResolvedCover?, at: Long) {
        val updated = paging.updateItem(mangaId) { card ->
            card.copy(
                coverDocumentId = cover?.coverDocumentId,
                coverChapterId = cover?.coverChapterId,
                coverProbedAt = at,
            )
        }
        if (updated) _state.update { it.copy(items = paging.items.toList()) }
    }

    companion object {
        /** 一批多少项；与图库同一个口径（30）。 */
        const val PAGE_SIZE = PagingState.DEFAULT_PAGE_SIZE

        /**
         * 搜索防抖窗口。
         *
         * 与图库取同一个值：300ms 是"用户还在打字"与"感觉即时"之间的常用折中，
         * 而两处不一致会让用户在两个页面感受到不同的响应速度。
         */
        const val SEARCH_DEBOUNCE_MS = 300L

        fun factory(container: com.lmreader.di.AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BookshelfViewModel(
                    mangaRepository = container.mangaRepository,
                    shelfRepository = container.shelfRepository,
                    chapterSyncer = container.mangaChapterSyncer,
                    preferences = container.preferences,
                    coverResolver = container.coverResolver,
                    coverMetadataWriter = container.coverMetadataWriter,
                )
            }
        }
    }
}

/** 书架页状态。 */
data class BookshelfUiState(
    val items: List<MangaCard> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** null = 「全部」；0 = 未分类（开发文档 8.2 右侧分类栏）。 */
    val selectedCategoryId: Long? = null,
    val sidePanelOpen: Boolean = false,
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.LIST,
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
    /**
     * 搜索框里**当前**的文本；只由 [BookshelfViewModel.onQueryChange] 更新。
     *
     * 它是搜索框的 `value`，因此**绝不能**被防抖之后的延迟值覆盖
     * （那样会覆盖用户刚敲的字符并把光标重置到开头）。
     */
    val query: String = "",
    /** 当前结果对应的查询词（防抖之后的）；空状态文案按它说话。 */
    val appliedQuery: String = "",
    /** 一键更新章节的总数；0 = 当前没有在更新。 */
    val chapterUpdateTotal: Int = 0,
    /** 一键更新章节已完成的部数。 */
    val chapterUpdateDone: Int = 0,
    /** 一键更新结束后的提示；null = 无提示。 */
    val chapterUpdateMessage: String? = null,
) {
    /** 是否正在一键更新章节。 */
    val updatingChapters: Boolean get() = chapterUpdateTotal > 0

    val selectedCategoryName: String
        get() = categories.firstOrNull { it.categoryId == selectedCategoryId }?.name ?: "全部"

    /** 是否正在搜索（输入框非空）。 */
    val searching: Boolean get() = query.isNotBlank()
}
