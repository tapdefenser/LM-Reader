package com.lmreader.ui.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.Category
import com.lmreader.core.model.LibraryDisplayMode
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // 冷启动进书架同样**只读本地缓存**，不触发扫描（用户要求）。
        // 书架是"已收藏"的引用，用户打开它是为了继续读，不是为了等索引更新；
        // 需要更新时由用户点刷新（开发文档 6.3）。
        viewModelScope.launch {
            loadMore()
        }
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
) {
    val selectedCategoryName: String
        get() = categories.firstOrNull { it.categoryId == selectedCategoryId }?.name ?: "全部"
}
