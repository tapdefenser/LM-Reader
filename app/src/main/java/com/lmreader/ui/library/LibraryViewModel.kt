package com.lmreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.LibraryDisplayMode
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.ScanReason
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.ui.common.ScreenState
import com.lmreader.ui.paging.PageSlice
import com.lmreader.ui.paging.PagingState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 图库页（开发文档 8.1、6.4）。
 *
 * 三条必须守住的行为：
 * 1. **边扫边显示**：扫描在后台继续，界面只按 40 项额度增长，绝不等待全库扫完；
 * 2. **不阻塞**：读缓存立即出结果，刷新只是发起扫描；
 * 3. **不伪造总数**：总数未知时只显示"已发现 N 项"。
 */
class LibraryViewModel(
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val shelfRepository: ShelfRepository,
    private val scanCoordinator: LibraryScanCoordinator,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val paging = PagingState<MangaCard>(idOf = { it.mangaId })

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        paging.requestInitial()
        viewModelScope.launch {
            preferences.libraryDisplayMode.collect { mode ->
                _state.update { it.copy(displayMode = mode) }
            }
        }
        viewModelScope.launch {
            scanCoordinator.overall.collect { overall -> _state.update { it.copy(scan = overall) } }
        }
        // 扫描期间新条目落库：额度还有空位就立即补入（开发文档 6.4）。
        viewModelScope.launch {
            mangaRepository.observeDiscoveryProgress().collect {
                if (paging.hasFreeSlot()) loadMore()
            }
        }
        viewModelScope.launch {
            observeQuery()
        }
        refresh(ScanReason.FIRST_RUN)
    }

    /** 首屏：立即读缓存并请求一次扫描。 */
    private fun refresh(reason: ScanReason) {
        viewModelScope.launch {
            paging.reset()
            loadMore()
            scanCoordinator.rescanAll(reason)
        }
    }

    /** 顶部刷新按钮/下拉刷新：请求变化扫描（开发文档 6.3）。 */
    fun onRefresh() {
        viewModelScope.launch {
            paging.reset()
            loadMore()
            scanCoordinator.rescanAll(ScanReason.REFRESH)
        }
    }

    /**
     * 搜索输入：300ms 防抖查询数据库，不为每个字符启动扫描（开发文档 6.3）。
     *
     * 打开搜索会话时触发一次全源变化扫描（开发文档 6.3「打开搜索」）。
     */
    @OptIn(FlowPreview::class)
    private suspend fun observeQuery() {
        queryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { query ->
                paging.reset()
                _state.update {
                    // 扫描/补全未完成时不能把零结果说成"没有漫画"（开发文档 6.4）。
                    it.copy(query = query, indexingHint = query.isNotBlank() && scanCoordinator.overall.value.running)
                }
                loadMore()
            }
    }

    fun onQueryChange(query: String) {
        queryFlow.value = query
    }

    fun onSearchOpened() {
        // 打开搜索时触发一次全源变化扫描（开发文档 6.3）；结果立即用缓存回答。
        scanCoordinator.rescanAll(ScanReason.SEARCH)
    }

    /** 滚动到列表末端：额度 +40 并追加（开发文档 8.1「底部加载」）。 */
    fun onLoadMore() {
        paging.requestNextBatch()
        viewModelScope.launch { loadMore() }
    }

    fun setDisplayMode(mode: LibraryDisplayMode) {
        viewModelScope.launch { preferences.setLibraryDisplayMode(mode) }
    }

    fun cancelScan() {
        scanCoordinator.cancelAll()
    }

    /** 长按卡片：加入书架（开发文档 8.2「加入书架」是分类单选弹窗的入口）。 */
    fun addToShelf(mangaId: String, categoryId: Long = 0L) {
        viewModelScope.launch {
            shelfRepository.addToShelf(mangaId, categoryId)
            loadMore()
        }
    }

    private suspend fun loadMore() {
        if (!paging.needsMore()) return
        paging.beginLoad()
        _state.update { it.copy(loading = true) }
        try {
            val query = queryFlow.value.trim()
            val page = if (query.isEmpty()) {
                mangaRepository.pageLibrary(paging.nextOffset, PAGE_SIZE)
            } else {
                mangaRepository.search(query, paging.nextOffset, PAGE_SIZE)
            }
            paging.append(
                PageSlice(items = page.items, nextOffset = page.nextOffset, exhausted = page.exhausted),
                totalKnown = page.totalKnown,
            )
            _state.update {
                it.copy(
                    loading = false,
                    items = paging.items.toList(),
                    exhausted = paging.exhausted,
                    discoveredCount = paging.items.size,
                    error = null,
                )
            }
        } catch (error: Exception) {
            // 读缓存失败是可恢复错误：给出原因与重试，而不是显示空列表（开发文档 3）。
            _state.update {
                it.copy(loading = false, error = error.message ?: "读取本地索引失败")
            }
        } finally {
            paging.endLoad()
        }
    }

    companion object {
        const val PAGE_SIZE = 40
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun factory(container: com.lmreader.di.AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    mangaRepository = container.mangaRepository,
                    sourceRepository = container.sourceRepository,
                    shelfRepository = container.shelfRepository,
                    scanCoordinator = container.scanCoordinator,
                    preferences = container.preferences,
                )
            }
        }
    }
}

/** 图库页状态。 */
data class LibraryUiState(
    val items: List<MangaCard> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val query: String = "",
    val discoveredCount: Int = 0,
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.LIST,
    val scan: com.lmreader.core.storage.scan.OverallScanState =
        com.lmreader.core.storage.scan.OverallScanState(),
    val indexingHint: Boolean = false,
    val error: String? = null,
) {
    /** 四态判定；空态必须区分"还没扫描"与"扫描完确实没有漫画"。 */
    val screenState: ScreenState<Unit>
        get() = when {
            error != null -> ScreenState.Error(error, actionLabel = "重试")
            items.isNotEmpty() -> ScreenState.Content(Unit)
            loading -> ScreenState.Loading
            scan.running -> ScreenState.Empty("正在发现漫画，已发现 $discoveredCount 项，请稍候")
            query.isNotBlank() -> ScreenState.Empty("没有匹配「$query」的漫画")
            else -> ScreenState.Empty(
                message = "图库里还没有漫画",
                actionLabel = null,
            )
        }
}
