package com.lmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingDirection
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.TapAction
import com.lmreader.core.model.NavigationRegions
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.storage.settings.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 阅读器状态机（开发文档 12；行为对齐 Mihon `ReaderViewModel` 的**页码语义**）。
 *
 * 关于进度的粒度：Mihon 的分页与条漫阅读器都只持久化**页码**，恢复时把该页对齐到
 * 视口顶部，页内偏移设计上丢弃。本项目照搬这一行为，因此 `reading_progress.intraPageRatio`
 * 保留在表里但恒为 0（详见 docs/框架实现说明 6.5）。
 *
 * 关于"当前页"的判定：分页与条漫的语义**不同**，不能共用一个实现——
 * - 分页：视口里那一页就是当前页；
 * - 条漫：页底越过视口底部才算当前页（"读完这一页"而不是"看到这一页"）。
 * 具体判定在各自的 Composable 里，本类只负责接收结果并落库。
 */
class ReaderViewModel(
    private val mangaId: String,
    private val requestedChapterId: String,
    private val mangaRepository: MangaRepository,
    private val progressRepository: ReadingProgressRepository,
    private val pageSourceFactory: PageSourceFactory,
    private val readerPreferences: ReaderPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var savedProgress: ReadingProgress? = null
    private val progressSaveMutex = Mutex()

    init {
        // 设置是持续观察的：用户在下方设置栏改了阅读模式，阅读器应当立刻换布局并
        // 尽量停在原页，而不是要求退出重进。
        viewModelScope.launch {
            readerPreferences.settings.collect { settings ->
                val previous = _state.value.settings
                _state.update { it.copy(settings = settings) }
                if (previous.readingMode != settings.readingMode) {
                    onReadingModeChanged()
                }
            }
        }
        reload()
    }

    /** 加载漫画、章节与被记住的位置。 */
    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val target = mangaRepository.getBackfillTarget(mangaId)
                    ?: error("漫画或来源已不存在")
                if (target.chapters.isEmpty()) error("这部漫画还没有可读章节")
                savedProgress = progressRepository.get(mangaId)
                val desiredChapterId = requestedChapterId.takeUnless { it == RESUME_CHAPTER }
                    ?: savedProgress?.chapterId
                val chapterIndex = target.chapters.indexOfFirst { it.chapterId == desiredChapterId }
                    .takeIf { it >= 0 }
                    ?: 0
                _state.update {
                    it.copy(
                        mangaTitle = target.manga.displayName,
                        sourceTreeUri = target.sourceTreeUri,
                        chapters = target.chapters,
                    )
                }
                // 只有"继续阅读"入口才恢复页码；从章节列表点进某一章时从头开始。
                val restorePage = savedProgress
                    ?.takeIf {
                        requestedChapterId == RESUME_CHAPTER &&
                            it.chapterId == target.chapters[chapterIndex].chapterId
                    }
                    ?.pageOrdinal
                    ?: 0
                openChapter(chapterIndex, restorePage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开阅读器") }
            }
        }
    }

    // ------------------------------------------------------------ 翻页

    /**
     * 分页模式的翻页。`delta` 是**阅读顺序**上的增量，不是屏幕方向。
     *
     * 右到左模式下"下一页"在屏幕左侧，这个映射由分页 Composable 的 `reverseLayout`
     * 负责；这里只关心阅读顺序，因此同一套动作可用于三种分页方向。
     */
    fun turnPage(delta: Int) {
        val current = _state.value
        val target = current.currentPageIndex + delta
        // 越界不在这里跨章：Mihon 用独立的章节过渡页承担跨界，翻到头就停住。
        if (target !in current.pages.indices) return
        if (target == current.currentPageIndex) return
        _state.update { it.copy(currentPageIndex = target) }
        saveProgress()
    }

    /** 页码滑杆：直接跳到本章第 [pageIndex] 页（0 基）。 */
    fun jumpToPage(pageIndex: Int) {
        val current = _state.value
        if (pageIndex !in current.pages.indices) return
        if (pageIndex == current.currentPageIndex) return
        _state.update { it.copy(currentPageIndex = pageIndex) }
        saveProgress()
    }

    /**
     * 条漫的滚动位置变化。
     *
     * 与分页走同一个落库路径；区别只是调用方用"页底越过视口底"算出这一页，
     * 而不是"视口里是哪一页"。
     */
    fun onScrolledToPage(pageIndex: Int) {
        val current = _state.value
        if (pageIndex !in current.pages.indices) return
        if (pageIndex == current.currentPageIndex) return
        _state.update { it.copy(currentPageIndex = pageIndex) }
        saveProgress()
    }

    // ------------------------------------------------------------ 章节

    fun openPreviousChapter() = moveChapter(-1)

    fun openNextChapter() = moveChapter(1)

    /**
     * 点击区域命中。
     *
     * 区域表与反转都由模型层解析，这里只把动作映射成阅读器行为。竖向模式下的
     * 上/下平移语义与横向相反，因此要按方向分派——这正是 Mihon 里
     * `PagerViewer` 与 `WebtoonViewer` 各自实现 `moveToNext` 的原因。
     */
    fun onTap(x: Float, y: Float) {
        val current = _state.value
        val action = NavigationRegions.hitTest(
            zones = current.settings.tapZones,
            invert = current.settings.tapInvert,
            mode = current.settings.readingMode,
            x = x,
            y = y,
        )
        when (action) {            TapAction.MENU -> toggleChrome()
            // 上一页/下一页是阅读顺序；PAN_LEFT/PAN_RIGHT 是屏幕方向。
            // 五种模式的"屏幕左/右"到"阅读前/后"的映射不同：
            // - 横向分页：视情况互换（右到左时屏幕左侧是下一页）；
            // - 竖向：屏幕方向无关，两个都按阅读顺序处理。
            TapAction.PREVIOUS -> turnPage(-1)
            TapAction.NEXT -> turnPage(1)
            TapAction.PAN_LEFT -> handleScreenSide(left = true)
            TapAction.PAN_RIGHT -> handleScreenSide(left = false)
        }
    }

    /**
     * 屏幕左/右半边的动作。
     *
     * 这里刻意**不**按阅读模式翻转：Mihon 也不翻转。右到左模式的翻页方向是由分页
     * 组件的列表反转实现的，于是"屏幕左侧 = 阅读下一页"自然成立。若在这里再翻转
     * 一次，右到左模式就会反向翻页——这是个很容易"顺手修好"却把行为改错的点。
     *
     * 竖向模式同样落到这里：Mihon 的竖向分页也用左右两栏点击区，且它的"左"同样映射
     * 到阅读上一页，因此不需要按方向分支。
     */
    private fun handleScreenSide(left: Boolean) {
        turnPage(if (left) -1 else 1)
    }

    fun toggleChrome() {
        _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    /** 点按区域遮罩层的显示与消退（Mihon `ReaderNavigationOverlayView`）。 */
    fun showTapZoneOverlay() {
        _state.update { it.copy(tapZoneOverlayVisible = true) }
    }

    fun hideTapZoneOverlay() {
        if (!_state.value.tapZoneOverlayVisible) return
        _state.update { it.copy(tapZoneOverlayVisible = false) }
    }

    /** 用户在阅读器内改了阅读模式（设置栏），写回偏好。 */
    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch { readerPreferences.update { it.copy(readingMode = mode) } }
    }

    /**
     * 阅读模式变了：保持章节与页码，不重新枚举章节。
     *
     * 不重开章节的理由：换布局不该丢掉"我在第几页"，而重新枚举会重排页码并可能
     * 触发一次网络/磁盘读取。页码越界由各 Composable 的 `coerceIn` 兜住。
     */
    private fun onReadingModeChanged() {
        _state.update { it.copy(chromeVisible = true, pageSource = it.pageSource) }
    }

    private fun moveChapter(delta: Int) {
        val current = _state.value
        val target = current.currentChapterIndex + delta
        if (target !in current.chapters.indices || current.loading) return
        viewModelScope.launch {
            try {
                openChapter(target, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开章节") }
            }
        }
    }

    // ------------------------------------------------------------ 进度

    fun saveProgress() {
        val current = _state.value
        val chapter = current.currentChapter ?: return
        if (current.pages.isEmpty()) return
        val progress = ReadingProgress(
            mangaId = mangaId,
            chapterId = chapter.chapterId,
            pageOrdinal = current.currentPageIndex.coerceIn(current.pages.indices),
            // 页内比例照搬 Mihon：只存页码，恢复时对齐页顶。
            intraPageRatio = 0f,
            read = savedProgress?.read == true,
            bookmark = savedProgress?.bookmark == true,
            updatedAt = clock(),
        )
        savedProgress = progress
        viewModelScope.launch {
            progressSaveMutex.withLock { progressRepository.save(progress) }
        }
    }

    // ------------------------------------------------------------ 打开章节

    private suspend fun openChapter(chapterIndex: Int, requestedPage: Int) {
        val current = _state.value
        val chapter = current.chapters.getOrNull(chapterIndex)
            ?: error("章节已不存在")
        val treeUri = current.sourceTreeUri ?: error("来源路径不可用")
        _state.update { it.copy(loading = true, error = null) }
        when (val opened = pageSourceFactory.open(treeUri, chapter)) {
            is PageSourceOpenResult.Unsupported -> {
                _state.update { it.copy(loading = false, error = opened.reason) }
            }

            is PageSourceOpenResult.Ready -> {
                val pages = opened.source.pages()
                // 页数为 0 时下面所有 coerceIn 都会抛空区间异常（早前真机崩溃过），
                // 因此在进入状态机之前就要挡住，而不是依赖调用方保证非空。
                if (pages.isEmpty()) {
                    _state.update { it.copy(loading = false, error = "章节目录内没有受支持的图片") }
                    return
                }
                mangaRepository.updateChapterPageInfo(
                    chapterId = chapter.chapterId,
                    pageCount = pages.size,
                    coverDocumentId = pages.firstOrNull()?.documentId,
                )
                _state.update {
                    it.copy(
                        loading = false,
                        currentChapterIndex = chapterIndex,
                        pages = pages,
                        currentPageIndex = requestedPage.coerceIn(pages.indices),
                        pageSource = opened.source,
                        // 换章后重置每页布局高度缓存，避免沿用上一章的尺寸。
                        pageHeights = emptyMap(),
                        tapZoneOverlayVisible = it.settings.showTapZoneOverlayOnStart,
                        error = null,
                    )
                }
                saveProgress()
            }
        }
    }

    /**
     * 记录一页在条带里的布局高度。
     *
     * 条漫需要先知道每页按原图比例换算出的高度才能定位滚动，而尺寸要探测才知道。
     * 探测结果由 Composable 写回这里，于是"已探测"这件事在换章后自动失效
     * （[openChapter] 清空）。
     */
    fun onPageHeightMeasured(pageId: String, heightPx: Int) {
        if (heightPx <= 0) return
        _state.update { current ->
            if (current.pageHeights[pageId] == heightPx) {
                current
            } else {
                current.copy(pageHeights = current.pageHeights + (pageId to heightPx))
            }
        }
    }

    companion object {
        const val RESUME_CHAPTER = "resume"

        fun factory(
            container: com.lmreader.di.AppContainer,
            mangaId: String,
            chapterId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    mangaId = mangaId,
                    requestedChapterId = chapterId,
                    mangaRepository = container.mangaRepository,
                    progressRepository = container.readingProgressRepository,
                    pageSourceFactory = container.pageSourceFactory,
                    readerPreferences = container.readerPreferences,
                )
            }
        }
    }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val mangaTitle: String = "",
    val sourceTreeUri: String? = null,
    val chapters: List<ChapterRecord> = emptyList(),
    val currentChapterIndex: Int = 0,
    val pages: List<ReaderPage> = emptyList(),
    val currentPageIndex: Int = 0,
    val pageSource: PageSource? = null,
    val chromeVisible: Boolean = true,
    /** 点按区域遮罩层；首次进入或偏好要求时短暂显示，任何点击都会让它消退。 */
    val tapZoneOverlayVisible: Boolean = false,
    /**
     * 已探测到的页面布局高度（条漫用）。
     *
     * 键是页 ID，值是按当前视口宽度换算出的像素高度。空表示尚未探测，
     * Composable 用视口高度占位。
     */
    val pageHeights: Map<String, Int> = emptyMap(),
    val settings: ReaderSettings = ReaderSettings(),
    val error: String? = null,
) {
    val currentChapter: ChapterRecord? get() = chapters.getOrNull(currentChapterIndex)
    val hasPreviousChapter: Boolean get() = currentChapterIndex > 0
    val hasNextChapter: Boolean get() = currentChapterIndex < chapters.lastIndex
    val readingMode: ReadingMode get() = settings.readingMode

    /** 是否走连续滚动实现（条漫两极）。 */
    val isContinuous: Boolean get() = readingMode.continuous
}
