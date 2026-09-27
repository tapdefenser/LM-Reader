package com.lmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.NavigationRegions
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.TapAction
import com.lmreader.core.model.withMangaOverride
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.storage.settings.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 阅读器状态机。
 *
 * ## 只有一个动作：在一条直线上前后移动
 *
 * 阅读器展示的是一条线性列表（见 [ViewerChapters] 的说明）：
 *
 * ```
 * … issue1 的页、[过渡页]、issue2 的页、[过渡页]、issue3 的页 …
 * ```
 *
 * 滑动、点按区域、音量键、滑杆、「上一章 / 下一章」按钮，**全部**归结为同一个操作：
 * 把 [ReaderUiState.currentPageIndex] 加减若干。当前在哪一章由"当前项属于哪一章"反推。
 *
 * 这里**没有**"跨章"这个事件，也没有"提升章节"。上一版把它当成事件处理，于是要重建列表、
 * 要把位置在两套下标之间换算——跳页、卡住、"有时进下一章有时被送回上一章"全部由此而来。
 * 把跨章降级为位置之后，"翻页跳到别的页"在结构上不可能发生。
 *
 * ## 窗口固定，只在接近边界时才接
 *
 * 窗口（这条线上有哪些章）在阅读过程中**不动**，因此所有项的下标不变。只有当读者走到
 * 窗口靠边的章时，才向那一端补一批章；补的时候按页身份重新定位（[reanchorIndex]），
 * 所以画面不跳。
 *
 * ## 进度粒度
 *
 * 照搬 Mihon：只持久化**页码**，页内偏移丢弃。因此 `intraPageRatio` 恒为 0。
 */
class ReaderViewModel(
    private val mangaId: String,
    private val requestedChapterId: String,
    /**
     * 从哪一页开始（0 基）；[NO_START_PAGE] 表示"没指定"，此时按章节/进度决定。
     */
    private val requestedStartPage: Int = NO_START_PAGE,
    private val mangaRepository: MangaRepository,
    private val progressRepository: ReadingProgressRepository,
    private val pageSourceFactory: PageSourceFactory,
    private val readerPreferences: ReaderPreferences,
    /** 页面字节的预取缓存；为空时一切照旧，只是每页都走页源现读。 */
    private val prefetcher: PagePrefetcher? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var savedProgress: ReadingProgress? = null
    private val progressSaveMutex = Mutex()

    /** 这部漫画的阅读覆盖；每次设置流发射都要用它重新叠加。 */
    private var mangaModeOverride: ReadingMode? = null
    private var mangaOrientationOverride: ReaderOrientation? = null

    /** 已安排的章节加载任务，按章节 ID 保存。 */
    private val loadJobs = HashMap<String, Job>()

    /** 已经加载出页清单的章，按 ID 索引。窗口由它与规划共同决定。 */
    private val loaded = LinkedHashMap<String, ViewerChapter>()

    init {
        viewModelScope.launch {
            readerPreferences.settings.collect { global ->
                val merged = global.withMangaOverride(mangaModeOverride, mangaOrientationOverride)
                val previous = _state.value.settings
                _state.update { it.copy(settings = merged) }
                // 过渡页开关变了要重组列表；预载预算变了要重算窗口。
                if (previous.showChapterTransitions != merged.showChapterTransitions ||
                    previous.preloadPages != merged.preloadPages
                ) {
                    rebuild()
                }
            }
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val target = mangaRepository.getBackfillTarget(mangaId)
                    ?: error("漫画或来源已不存在")
                if (target.chapters.isEmpty()) error("这部漫画还没有可读章节")
                savedProgress = progressRepository.get(mangaId)
                mangaModeOverride = target.manga.readerModeOverride
                mangaOrientationOverride = target.manga.readerOrientationOverride
                val merged = readerPreferences.settings.first()
                    .withMangaOverride(mangaModeOverride, mangaOrientationOverride)
                _state.update {
                    it.copy(
                        settings = merged,
                        mangaModeOverride = mangaModeOverride,
                        mangaOrientationOverride = mangaOrientationOverride,
                    )
                }
                val desired = requestedChapterId.takeUnless { it == RESUME_CHAPTER }
                    ?: savedProgress?.chapterId
                val index = target.chapters.indexOfFirst { it.chapterId == desired }
                    .takeIf { it >= 0 }
                    ?: 0
                _state.update {
                    it.copy(
                        mangaTitle = target.manga.displayName,
                        sourceTreeUri = target.sourceTreeUri,
                        chapterList = target.chapters,
                    )
                }
                // 恢复页码的三种来源，按优先级：
                // 1. 详情页带过来的起始页；2. "继续阅读"入口 + 进度记的正是这一章；
                // 3. 都没有 → 第一页。
                val fromRoute = requestedStartPage.takeIf { it >= 0 }
                val fromProgress = savedProgress
                    ?.takeIf {
                        requestedChapterId == RESUME_CHAPTER &&
                            it.chapterId == target.chapters[index].chapterId
                    }
                    ?.pageOrdinal
                val restorePage = fromRoute ?: fromProgress ?: 0
                openAt(index, restorePage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开阅读器") }
            }
        }
    }

    // ------------------------------------------------------------ 打开与窗口

    /**
     * 打开第 [chapterIndex] 章并落在它的第 [page] 页。
     *
     * 只有"用户主动跳章"（详情页点章节、打开阅读器）才走这里；翻页本身**不**走这里，
     * 它只移动 [ReaderItem] 下标。
     */
    private suspend fun openAt(chapterIndex: Int, page: Int) {
        val snapshot = _state.value
        val record = snapshot.chapterList.getOrNull(chapterIndex) ?: error("章节已不存在")
        val treeUri = snapshot.sourceTreeUri ?: error("来源路径不可用")
        _state.update { it.copy(loading = true, error = null) }

        val current = loadChapter(record, treeUri)
        if (current == null) {
            _state.update { it.copy(loading = false, error = "无法打开「${record.title}」") }
            return
        }
        loaded[record.chapterId] = current
        // 先把窗口建立起来（只为算出当前章第一项的位置），再落到指定页。
        fillWindow(record.chapterId)
        _state.update { state ->
            val items = state.items
            val first = items.indexOfFirstPageOfChapter(record.chapterId).coerceAtLeast(0)
            val target = (first + page).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            state.copy(loading = false, currentPageIndex = target, error = null)
        }
        val opened = _state.value
        mangaRepository.updateChapterPageInfo(
            chapterId = record.chapterId,
            pageCount = current.pages.size,
            coverDocumentId = current.pages.firstOrNull()?.documentId,
        )
        opened.items.getOrNull(opened.currentPageIndex)?.let { saveProgressAt(it) }
        loadNeighbors()
        warmPrefetch()
    }

    /**
     * 按预载预算补齐窗口，并重建项列表。
     *
     * 已在窗口里的章**一律保留**（[buildWindow] 会用 `loaded` 兜住），因此这个方法可以
     * 反复调用而不会丢掉已经加载好的内容。
     */
    private fun fillWindow(currentChapterId: String) {
        val snapshot = _state.value
        val list = snapshot.chapterList
        if (list.isEmpty()) return
        val effectiveCurrent = currentChapterId.ifEmpty {
            snapshot.chapters?.currentChapterId ?: list.first().chapterId
        }
        val plan = planFor(list, effectiveCurrent, snapshot.settings)
        val (window, currentIndex) = buildWindow(list, effectiveCurrent, plan, loaded)
        if (window.isEmpty()) return
        val chapters = ViewerChapters(window = window, currentIndex = currentIndex)
        writeItems(chapters)
    }

    /**
     * 算出要收进窗口的章。
     *
     * 除了规划覆盖的章之外，还会再接**一格边界章**。必须这样做的理由：规划用的是已知页数，
     * 而一章的页数要等它加载完才知道。于是"刚量到页数的那些章"会立刻改变规划结果，
     * 一次规划只能多覆盖一章——窗口会永远比预载进度慢一步。
     *
     * 边界章同样是**要被加载的**（见 [chaptersToLoad]），否则窗口永远长不起来。
     */
    private fun planFor(
        list: List<ChapterRecord>,
        currentChapterId: String,
        settings: ReaderSettings,
    ): PreloadPlan {
        val currentIndex = list.indexOfFirst { it.chapterId == currentChapterId }
        if (currentIndex < 0) return PreloadPlan.EMPTY
        val base = PreloadPlan.compute(
            chapterCount = list.size,
            currentIndex = currentIndex,
            budget = settings.preloadPages,
            maxChapters = MAX_PRELOAD_CHAPTERS_PER_SIDE,
            pagesOf = { index ->
                list.getOrNull(index)?.let { record ->
                    loaded[record.chapterId]?.pages?.size?.takeIf { size -> size > 0 }
                }
            },
        )
        val next = base.nextIndices.toMutableList()
        val previous = base.previousIndices.toMutableList()
        val nextFrontier = (next.lastOrNull() ?: currentIndex) + 1
        val previousFrontier = (previous.firstOrNull() ?: currentIndex) - 1
        if (nextFrontier in list.indices && next.size < MAX_PRELOAD_CHAPTERS_PER_SIDE) {
            next += nextFrontier
        }
        if (previousFrontier in list.indices && previous.size < MAX_PRELOAD_CHAPTERS_PER_SIDE) {
            previous += previousFrontier
        }
        return PreloadPlan(previousIndices = previous, nextIndices = next)
    }

    /**
     * 需要加载的章：规划覆盖的章 **加上** 一格边界章。
     *
     * 边界章必须一起加载——否则窗口只会包含"规划算出来的那几章"，而边界章的页数永远
     * 未知，窗口就再也长不起来（真机上表现为只剩当前章、翻到末页就显示"已是最后一章"）。
     */
    private fun chaptersToLoad(
        list: List<ChapterRecord>,
        currentChapterId: String,
        settings: ReaderSettings,
    ): List<ChapterRecord> {
        val currentIndex = list.indexOfFirst { it.chapterId == currentChapterId }
        if (currentIndex < 0) return emptyList()
        val plan = planFor(list, currentChapterId, settings)
        val indices = plan.nextIndices + plan.previousIndices.asReversed()
        return indices.mapNotNull { list.getOrNull(it) }
    }

    /** 重新组装项列表并按项身份把读者放回原处。 */
    private fun writeItems(chapters: ViewerChapters) {
        _state.update { state ->
            val items = chapters.items(
                showTransitions = state.settings.showChapterTransitions,
                isFinalChapter = { id -> state.chapterList.lastOrNull()?.chapterId == id },
            )
            state.copy(
                chapters = chapters,
                items = items,
                currentPageIndex = reanchorIndex(state.items, state.currentPageIndex, items),
            )
        }
    }

    /** 按当前状态重算窗口；设置变化与邻章加载完成都走这里。 */
    private fun rebuild() {
        val current = _state.value.chapters?.currentChapterId
            ?: _state.value.chapterList.firstOrNull()?.chapterId
            ?: return
        fillWindow(current)
    }

    private fun loadNeighbors() {
        val snapshot = _state.value
        val treeUri = snapshot.sourceTreeUri ?: return
        val current = snapshot.chapters?.currentChapterId
            ?: snapshot.chapterList.firstOrNull()?.chapterId
            ?: return
        for (record in chaptersToLoad(snapshot.chapterList, current, snapshot.settings)) {
            loadOne(record, treeUri)
        }
    }

    /**
     * 加载一章的页清单。
     *
     * 完成后把它写进 `loaded` 并重建列表。因为窗口固定、且重建走 [reanchorIndex]，
     * 页与过渡页是**插进**已有列表的，读者的位置不变。
     */
    private fun loadOne(record: ChapterRecord, treeUri: String) {
        if (loadJobs.containsKey(record.chapterId)) return
        loadJobs[record.chapterId] = viewModelScope.launch {
            val result = loadChapter(record, treeUri)
            loaded[record.chapterId] = result ?: ViewerChapter(
                chapter = record,
                pages = emptyList(),
                source = _state.value.chapters?.current?.source
                    ?: return@launch,
                state = ViewerChapter.LoadState.FAILED,
            )
            rebuild()
            loadNeighbors()
            warmPrefetch()
        }
    }

    /** 列出一章的页；失败返回 null（章级问题由过渡页显示原因与重试）。 */
    private suspend fun loadChapter(record: ChapterRecord, treeUri: String): ViewerChapter? = try {
        when (val opened = pageSourceFactory.open(treeUri, record)) {
            is PageSourceOpenResult.Unsupported -> null
            is PageSourceOpenResult.Ready -> {
                val pages = opened.source.pages()
                if (pages.isEmpty()) {
                    null
                } else {
                    ViewerChapter(chapter = record, pages = pages, source = opened.source)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    // ------------------------------------------------------------ 移动

    /** 在直线上移动 [delta] 格。滑动、点按、音量键、按钮都汇聚到这里。 */
    fun move(delta: Int) {
        val current = _state.value
        if (current.items.isEmpty()) return
        val target = (current.currentPageIndex + delta).coerceIn(current.items.indices)
        if (target == current.currentPageIndex) return
        settleAt(target)
    }

    /** 「上一章 / 下一章」按钮：同样是移动一格，只是移动的是整章的量。 */
    fun jumpToAdjacentChapter(forward: Boolean) {
        val state = _state.value
        val list = state.chapterList
        val index = list.indexOfFirst { it.chapterId == state.chapters?.currentChapterId ?: "" }
        val target = if (forward) index + 1 else index - 1
        if (index < 0 || target !in list.indices) return
        viewModelScope.launch {
            try {
                openAt(target, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开章节") }
            }
        }
    }

    private fun settleAt(absoluteIndex: Int) {
        val snapshot = _state.value
        if (absoluteIndex !in snapshot.items.indices) return
        if (absoluteIndex == snapshot.currentPageIndex) return
        _state.update { it.copy(currentPageIndex = absoluteIndex) }
        val item = snapshot.items[absoluteIndex]
        // 当前章由"当前项属于哪一章"反推——不再有提升动作。
        val chapterId = item.chapterId
        if (item is ReaderItem.PageItem) saveProgressAt(item)
        val moved = _state.value
        if (moved.chapters?.currentChapterId != chapterId) {
            _state.update { state ->
                val window = state.chapters ?: return@update state
                val position = window.indexOf(chapterId)
                if (position < 0) state else state.copy(chapters = window.copy(currentIndex = position))
            }
        }
        // 快走到窗口边缘时补窗口；补完按页身份定位，画面不跳。
        if (nearWindowEdge()) rebuild()
    }

    /** 读者是否已经走到窗口靠边的章（再往前一寸就该补窗口了）。 */
    private fun nearWindowEdge(): Boolean {
        val chapters = _state.value.chapters ?: return false
        val index = chapters.currentIndex
        return index <= WINDOW_EDGE_MARGIN || index >= chapters.window.size - 1 - WINDOW_EDGE_MARGIN
    }

    /** 页码滑杆：跳到当前章的第 [localPageIndex] 页（0 基）。 */
    fun jumpToPage(localPageIndex: Int) {
        val current = _state.value
        val chapterId = current.chapters?.currentChapterId ?: return
        val pages = current.chapters?.current?.pages.orEmpty()
        if (localPageIndex !in pages.indices) return
        val first = current.items.indexOfFirstPageOfChapter(chapterId)
        if (first < 0) return
        settleAt(first + localPageIndex)
    }

    /** 分页器或条带落到了第 [absoluteIndex] 项。 */
    fun onItemSettled(absoluteIndex: Int) {
        settleAt(absoluteIndex)
    }

    /** 记录一页在条带里的布局高度，供滚动定位使用。 */
    fun onPageHeightMeasured(pageId: String, heightDp: Int) {
        if (heightDp <= 0) return
        _state.update { current ->
            if (current.pageHeights[pageId] == heightDp) {
                current
            } else {
                current.copy(pageHeights = current.pageHeights + (pageId to heightDp))
            }
        }
    }

    // ------------------------------------------------------------ 预取页字节

    /**
     * 把当前页附近 [ReaderSettings.preloadPages] 页的**图片字节**提前读进磁盘缓存。
     *
     * 缓存只放磁盘、不放堆：真机上解码一页就已经吃过整图分配的亏（见 `ReaderImageView`），
     * 再往堆里压几页字节会把 OOM 重新引回来。
     */
    private fun warmPrefetch() {
        val prefetcher = prefetcher ?: return
        val snapshot = _state.value
        val budget = snapshot.settings.preloadPages
        if (budget <= 0) return
        val chapters = snapshot.chapters ?: return

        val ahead = ArrayList<PrefetchCandidate>()
        val behind = ArrayList<PrefetchCandidate>()
        val anchored = (snapshot.items.getOrNull(snapshot.currentPageIndex) as? ReaderItem.PageItem)
            ?.takeIf { it.chapter.chapterId == chapters.currentChapterId }
        val localOrdinal = anchored?.page?.ordinal ?: 0

        chapters.current.pages.forEach { page ->
            when {
                page.ordinal > localOrdinal -> ahead += PrefetchCandidate(page, chapters.current.source)
                page.ordinal < localOrdinal -> behind += PrefetchCandidate(page, chapters.current.source)
            }
        }
        behind.reverse()

        // 当前页之后的项（含过渡页与后续章节的页），按列表顺序取前 budget 个。
        for (index in snapshot.currentPageIndex + 1 until snapshot.items.size) {
            if (ahead.size >= budget) break
            val item = snapshot.items[index] as? ReaderItem.PageItem ?: continue
            ahead += PrefetchCandidate(item.page, item.chapter.source)
        }
        for (index in snapshot.currentPageIndex - 1 downTo 0) {
            if (behind.size >= budget) break
            val item = snapshot.items[index] as? ReaderItem.PageItem ?: continue
            behind += PrefetchCandidate(item.page, item.chapter.source)
        }

        prefetcher.request(
            scopeKey = prefetchScopeKey(snapshot),
            ahead = ahead.take(budget),
            behind = behind.take(budget),
        )
    }

    /** 预取范围的稳定标识；只在窗口边界变化时才清理旧缓存。 */
    private fun prefetchScopeKey(snapshot: ReaderUiState): String {
        val chapters = snapshot.chapters
        return buildString {
            append(mangaId)
            append('|').append(chapters?.window?.firstOrNull()?.chapterId.orEmpty())
            append('|').append(chapters?.window?.lastOrNull()?.chapterId.orEmpty())
        }
    }

    // ------------------------------------------------------------ 点按与控制栏

    fun onTap(x: Float, y: Float) {
        val current = _state.value
        val action = NavigationRegions.hitTest(
            zones = current.settings.tapZones,
            invert = current.settings.tapInvert,
            mode = current.settings.readingMode,
            x = x,
            y = y,
        )
        when (action) {
            TapAction.MENU -> toggleChrome()
            TapAction.PREVIOUS, TapAction.PAN_LEFT -> move(-1)
            TapAction.NEXT, TapAction.PAN_RIGHT -> move(1)
        }
    }

    fun toggleChrome() {
        _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    fun showTapZoneOverlay() {
        _state.update { it.copy(tapZoneOverlayVisible = true) }
    }

    fun hideTapZoneOverlay() {
        if (!_state.value.tapZoneOverlayVisible) return
        _state.update { it.copy(tapZoneOverlayVisible = false) }
    }

    /** 重试当前窗口里加载失败的章。 */
    fun retryFailedChapters() {
        val snapshot = _state.value
        val treeUri = snapshot.sourceTreeUri ?: return
        val list = snapshot.chapterList
        for (chapter in snapshot.chapters?.window.orEmpty()) {
            if (chapter.state != ViewerChapter.LoadState.FAILED) continue
            loadJobs.remove(chapter.chapterId)
            val record = list.firstOrNull { it.chapterId == chapter.chapterId } ?: continue
            loadOne(record, treeUri)
        }
    }

    // ------------------------------------------------------------ 设置

    /**
     * 用户改了阅读模式；写的是**这部漫画的覆盖**而不是全局默认。
     */
    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch {
            mangaModeOverride = mode
            mangaRepository.updateReaderOverrides(
                mangaId = mangaId,
                mode = mode,
                orientation = mangaOrientationOverride,
            )
            _state.update {
                it.copy(
                    settings = it.settings.withMangaOverride(mode, mangaOrientationOverride),
                    mangaModeOverride = mode,
                )
            }
        }
    }

    /** 清除阅读模式覆盖，回到全局默认。 */
    fun clearReadingModeOverride() {
        viewModelScope.launch {
            mangaModeOverride = null
            mangaRepository.updateReaderOverrides(mangaId, null, mangaOrientationOverride)
            val global = readerPreferences.settings.first()
            _state.update {
                it.copy(
                    settings = global.withMangaOverride(null, mangaOrientationOverride),
                    mangaModeOverride = null,
                )
            }
        }
    }

    /** 设置这部漫画的屏幕方向覆盖；null 表示清除覆盖。 */
    fun setOrientationOverride(orientation: ReaderOrientation?) {
        viewModelScope.launch {
            mangaOrientationOverride = orientation
            mangaRepository.updateReaderOverrides(mangaId, mangaModeOverride, orientation)
            _state.update {
                it.copy(
                    settings = it.settings.withMangaOverride(mangaModeOverride, orientation),
                    mangaOrientationOverride = orientation,
                )
            }
        }
    }

    /** 修改**全局**阅读设置（设置界面的"通用"与"自定义滤镜"两页）。 */
    fun updateGlobalSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { readerPreferences.update(transform) }
    }

    // ------------------------------------------------------------ 进度

    /** 把某个页面项写成阅读进度。落在过渡页上时不写（那不是某一页）。 */
    private fun saveProgressAt(item: ReaderItem) {
        if (item !is ReaderItem.PageItem) return
        val progress = ReadingProgress(
            mangaId = mangaId,
            chapterId = item.chapter.chapterId,
            pageOrdinal = item.page.ordinal,
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

    /** 把当前落点写成阅读进度。 */
    fun saveProgress() {
        val current = _state.value
        current.items.getOrNull(current.currentPageIndex)?.let { saveProgressAt(it) }
    }

    override fun onCleared() {
        super.onCleared()
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        prefetcher?.cancelAll()
    }

    companion object {
        const val RESUME_CHAPTER = "resume"

        /** 没有指定起始页的哨兵值；见 [requestedStartPage]。 */
        const val NO_START_PAGE = -1

        /**
         * 单侧最多预载几章。
         *
         * 预算是页数，理论上"每章只有 1 页"的长篇会把整部作品拉进来；这个上限把最坏情况
         * 钉住。取 3 是因为真机样本里一章 29–106 页，而预算上限 60 页在 3 章内必然用完。
         */
        const val MAX_PRELOAD_CHAPTERS_PER_SIDE = 3

        /**
         * 距窗口端点还有几章时就去补窗口。
         *
         * 取 1：读者走到倒数第二或第二章时就补，于是"补"总发生在还看得见旧内容的时候，
         * 不会在读到底那一刻才卡一下。补窗口按页身份定位，因此画面不跳。
         */
        const val WINDOW_EDGE_MARGIN = 1

        fun factory(
            container: com.lmreader.di.AppContainer,
            mangaId: String,
            chapterId: String,
            startPage: Int = NO_START_PAGE,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    mangaId = mangaId,
                    requestedChapterId = chapterId,
                    requestedStartPage = startPage,
                    mangaRepository = container.mangaRepository,
                    progressRepository = container.readingProgressRepository,
                    pageSourceFactory = container.pageSourceFactory,
                    readerPreferences = container.readerPreferences,
                    prefetcher = container.pagePrefetcher,
                )
            }
        }
    }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val mangaTitle: String = "",
    val sourceTreeUri: String? = null,
    /** 全部章节，用于换章、窗口规划与"是不是最后一章"。 */
    val chapterList: List<ChapterRecord> = emptyList(),
    /** 当前窗口与当前章在其中的下标。 */
    val chapters: ViewerChapters? = null,
    /** 整条直线的项列表：窗口里已加载章的页 + 章之间的过渡页。 */
    val items: List<ReaderItem> = emptyList(),
    /** 在 [items] 中的绝对下标。 */
    val currentPageIndex: Int = 0,
    /**
     * 控制栏是否可见；默认**隐藏**：阅读器一打开就应该是内容
     * （Mihon 的 `ReaderActivity` 同样以隐藏态进入）。
     */
    val chromeVisible: Boolean = false,
    val tapZoneOverlayVisible: Boolean = false,
    /** 已探测到的条带页高（dp），键为页 ID。 */
    val pageHeights: Map<String, Int> = emptyMap(),
    val settings: ReaderSettings = ReaderSettings(),
    /** 这部漫画的阅读模式覆盖；null 表示跟随全局默认。 */
    val mangaModeOverride: ReadingMode? = null,
    /** 这部漫画的屏幕方向覆盖；null 表示跟随全局默认。 */
    val mangaOrientationOverride: ReaderOrientation? = null,
    val error: String? = null,
) {
    /** 当前章在整部里的下标；用于「上一章 / 下一章」按钮的可用性。 */
    val currentChapterListIndex: Int
        get() = chapters?.currentChapterId?.let { id ->
            chapterList.indexOfFirst { it.chapterId == id }
        } ?: -1

    val hasPreviousChapter: Boolean get() = currentChapterListIndex > 0
    val hasNextChapter: Boolean
        get() = currentChapterListIndex >= 0 && currentChapterListIndex < chapterList.lastIndex

    /** 当前章的页清单。 */
    val currentPages: List<ReaderPage> get() = chapters?.current?.pages.orEmpty()

    /** 当前章的记录。 */
    val currentChapter: ChapterRecord?
        get() = currentChapterListIndex.takeIf { it >= 0 }?.let { chapterList.getOrNull(it) }

    /**
     * 当前落点是否是过渡页。
     *
     * 过渡页上页码相关的一切都要"置零置灰"（用户要求），而它本身没有任何按钮——
     * 它就是一张夹在中间的图。
     */
    val currentItemIsTransition: Boolean
        get() = items.getOrNull(currentPageIndex) is ReaderItem.Transition

    /** 当前章内已读到的页序号（0 基）；落在过渡页上时为 null。 */
    val localPageIndex: Int?
        get() = (items.getOrNull(currentPageIndex) as? ReaderItem.PageItem)?.page?.ordinal

    /** 当前章内页数；过渡页上仍返回本章页数（滑杆自己决定要不要置灰）。 */
    val currentPageCount: Int get() = currentPages.size

    val readingMode: ReadingMode get() = settings.readingMode
    val isContinuous: Boolean get() = readingMode.continuous

    /** 窗口里是否有章加载失败，供过渡页显示重试。 */
    val windowHasFailedChapter: Boolean
        get() = chapters?.window?.any { it.state == ViewerChapter.LoadState.FAILED } ?: false
}
