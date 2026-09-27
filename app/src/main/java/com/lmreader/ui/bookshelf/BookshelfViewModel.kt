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
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.model.StyleMode
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.ScanReason
import com.lmreader.core.storage.settings.AppPreferences
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
    private val scanCoordinator: LibraryScanCoordinator,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val paging = PagingState<MangaCard>(idOf = { it.mangaId })

    private val _state = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = _state.asStateFlow()

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
        // 可见收藏**变小**时（扫描完整结束后把"本轮没再发现"的旧卡片标成陈旧），
        // 已经加载进内存的卡片不会自己消失：确认后重建分页会话，否则用户点过刷新
        // 仍会看到不该出现的旧卡片——与图库同一处理。
        viewModelScope.launch {
            _state.map { it.selectedCategoryId }.distinctUntilChanged().collectLatest { categoryId ->
                var previous = -1
                mangaRepository.observeVisibleCount(inShelfOnly = true, categoryId = categoryId)
                    .collect { visible ->
                        val shrank = previous >= 0 && visible < previous
                        previous = visible
                        val loaded = _state.value.items
                        if (!shrank || loaded.isEmpty()) return@collect
                        val hasStaleCard = mangaRepository.getCards(loaded.map { it.mangaId })
                            .any { it.availability == MangaAvailability.STALE }
                        if (hasStaleCard) {
                            paging.reset()
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
                paging.reset()
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
            paging.reset()
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

    fun onRefresh() {
        viewModelScope.launch {
            paging.reset()
            loadMore()
            scanCoordinator.rescanAll(ScanReason.REFRESH)
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
            paging.reset()
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
            paging.reset()
            loadMore()
        }
    }

    fun moveToCategory(mangaId: String, categoryId: Long) {
        viewModelScope.launch {
            shelfRepository.addToShelf(mangaId, categoryId)
            paging.reset()
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

    companion object {
        const val PAGE_SIZE = 40

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
                    scanCoordinator = container.scanCoordinator,
                    preferences = container.preferences,
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
     * 它是 `OutlinedTextField` 的 `value`，因此**绝不能**被防抖之后的延迟值覆盖
     * （那样会覆盖用户刚敲的字符并把光标重置到开头）。
     */
    val query: String = "",
    /** 当前结果对应的查询词（防抖之后的）；空状态文案按它说话。 */
    val appliedQuery: String = "",
) {
    val selectedCategoryName: String
        get() = categories.firstOrNull { it.categoryId == selectedCategoryId }?.name ?: "全部"

    /** 是否正在搜索（输入框非空）。 */
    val searching: Boolean get() = query.isNotBlank()
}
