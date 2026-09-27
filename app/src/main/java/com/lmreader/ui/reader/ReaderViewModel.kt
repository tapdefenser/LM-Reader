package com.lmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.NavigationRegions
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.TapAction
import com.lmreader.core.model.withMangaOverride
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
 * 阅读器状态机：章节编排 + 进度（开发文档 12）。
 *
 * ## 章节编排照搬 Mihon 的地方
 *
 * Mihon 的阅读器**同时持有当前章与相邻两章**（`ViewerChapters(curr, prev, next)`），
 * 并把"章节过渡"作为与页面并列的项放进同一个列表（`PagerViewerAdapter.setChapters`）。
 * 这样才能做到章末接着翻、条带尾部预置下一章。此前这里只加载当前章，于是到章末就停住
 * ——那是"多章节读取基本上是坏的"的直接原因。
 *
 * 项列表的组装顺序见 [buildReaderItems]；本类负责加载相邻章、把落点换算成进度，
 * 以及在预载完成导致下标整体后移时**按页面身份重新定位**，避免读者突然跳页。
 *
 * ## 进度粒度
 *
 * 照搬 Mihon：只持久化**页码**，页内偏移丢弃。因此 `intraPageRatio` 恒为 0
 * （详见 docs/框架实现说明 6.5）。
 */
class ReaderViewModel(
    private val mangaId: String,
    private val requestedChapterId: String,
    /**
     * 从哪一页开始（0 基）；[NO_START_PAGE] 表示"没指定"，此时按章节/进度决定。
     *
     * 与"继续阅读"入口的区别：那个是 chapterId 为 [RESUME_CHAPTER]、页码来自进度表；
     * 这个是用户点了**具体某一章**那一行，页码由详情页从他读到的地方带过来。
     */
    private val requestedStartPage: Int = NO_START_PAGE,
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

    /**
     * 这部漫画的阅读覆盖。
     *
     * 单独保存而不是只在启动时合并一次：全局设置在阅读器开着的时候也可能变化
     * （用户在阅读器内改模式、或别处改了默认值），每次都要用同一份覆盖重新合并。
     * 若不留着它，设置流的下一次发射会把合并结果整个换成"纯全局"，漫画级覆盖就丢了。
     */
    private var mangaModeOverride: ReadingMode? = null
    private var mangaOrientationOverride: com.lmreader.core.model.ReaderOrientation? = null

    /**
     * 相邻章的加载任务。
     *
     * 按章节 ID 保存是为了在快速换章时取消上一轮预载：不取消会让多个章节同时列页，
     * 既浪费 IO，也可能用过期结果覆盖当前状态。
     */
    private val neighborJobs = HashMap<String, Job>()

    init {
        // 设置持续观察：用户在阅读器内改模式后应立刻换布局并停在原处。
        viewModelScope.launch {
            readerPreferences.settings.collect { global ->
                // 每次都重新叠加这部漫画的覆盖，而不是直接用全局值——否则设置流的
                // 任何一次发射都会把漫画级覆盖抹掉。
                val merged = global.withMangaOverride(mangaModeOverride, mangaOrientationOverride)
                val wasContinuous = _state.value.isContinuous
                _state.update { it.copy(settings = merged) }
                if (wasContinuous != merged.readingMode.continuous) {
                    // 分页 ↔ 条漫互换时项的渲染方式变了，按身份重新定位避免跳页。
                    reanchorCurrentPage()
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
                // 记下这部漫画的覆盖，供设置流的每次发射重新叠加。
                mangaModeOverride = target.manga.readerModeOverride
                mangaOrientationOverride = target.manga.readerOrientationOverride
                // 漫画级覆盖与全局默认在这里**合并一次**，之后阅读器各处只读合并结果。
                // 界面里有约六十项设置、十几处读取点，任何一处漏判覆盖都会表现为
                // "某些行为听全局、某些听漫画"，那种不一致极难排查。
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
                // 1. 详情页带过来的起始页（用户点了某一章那一行，且他读到过那里）；
                // 2. "继续阅读"入口 + 进度表里记的正是这一章 → 用进度里的页码；
                // 3. 都没有 → 第一页。
                val fromRoute = requestedStartPage.takeIf { it >= 0 }
                val fromProgress = savedProgress
                    ?.takeIf {
                        requestedChapterId == RESUME_CHAPTER &&
                            it.chapterId == target.chapters[index].chapterId
                    }
                    ?.pageOrdinal
                val restorePage = fromRoute ?: fromProgress ?: 0
                openChapter(index, restorePage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开阅读器") }
            }
        }
    }

    // ------------------------------------------------------------ 章节

    fun openPreviousChapter() = moveChapter(-1)

    fun openNextChapter() = moveChapter(1)

    private fun moveChapter(delta: Int) {
        val current = _state.value
        val target = current.currentChapterIndex + delta
        if (target !in current.chapterList.indices || current.loading) return
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

    /**
     * 打开第 [chapterIndex] 章，把它放在正中，然后预载相邻两章。
     *
     * @param requestedPage 章内页序号（0 基）
     */
    private suspend fun openChapter(chapterIndex: Int, requestedPage: Int) {
        val snapshot = _state.value
        val chapter = snapshot.chapterList.getOrNull(chapterIndex)
            ?: error("章节已不存在")
        val treeUri = snapshot.sourceTreeUri ?: error("来源路径不可用")
        _state.update { it.copy(loading = true, error = null) }

        when (val loaded = loadChapter(chapter, treeUri)) {
            is ChapterLoadResult.Failed -> {
                _state.update { it.copy(loading = false, error = loaded.reason) }
                return
            }

            is ChapterLoadResult.Ok -> {
                val current = loaded.chapter
                val pageIndex = requestedPage.coerceIn(current.pages.indices)
                val chapters = ViewerChapters(current = current)
                val readerItems = buildReaderItems(chapters, snapshot.settings.alwaysShowChapterTransition)
                // 当前章页在项列表中的起点就是"章内第 0 页"的绝对下标。
                val offset = readerItems.currentChapterOffset

                _state.update {
                    it.copy(
                        loading = false,
                        currentChapterIndex = chapterIndex,
                        chapters = chapters,
                        items = readerItems.items,
                        currentPageIndex = (offset + pageIndex).coerceIn(readerItems.items.indices),
                        // 换章后重置条漫的页高缓存，避免沿用上一章的尺寸。
                        pageHeights = emptyMap(),
                        tapZoneOverlayVisible = it.settings.showTapZoneOverlayOnStart,
                        error = null,
                    )
                }
                mangaRepository.updateChapterPageInfo(
                    chapterId = current.chapter.chapterId,
                    pageCount = current.pages.size,
                    coverDocumentId = current.pages.firstOrNull()?.documentId,
                )
                saveProgress()
                preloadNeighbors(chapterIndex)
            }
        }
    }

    /**
     * 预载相邻章节。
     *
     * 这是"多章节能连续读"的关键：分页要在末页之后放下一章的过渡页，条漫要在条带尾部
     * 预置下一章的页。不做这一步，读者到章末会撞到一堵墙。
     *
     * 只预载**两侧各一章**，与 Mihon 的 `ViewerChapters` 一致：再远就不必，读到那里时
     * 会再次触发预载。
     */
    private fun preloadNeighbors(chapterIndex: Int) {
        val snapshot = _state.value
        val treeUri = snapshot.sourceTreeUri ?: return
        val list = snapshot.chapterList
        val current = snapshot.chapters?.current ?: return

        neighborJobs.values.forEach { it.cancel() }
        neighborJobs.clear()

        val previousIndex = chapterIndex - 1
        val nextIndex = chapterIndex + 1
        if (previousIndex >= 0) {
            loadNeighbor(list[previousIndex], treeUri, current.chapterId, forward = false)
        }
        if (nextIndex in list.indices) {
            loadNeighbor(list[nextIndex], treeUri, current.chapterId, forward = true)
        }
    }

    private fun loadNeighbor(
        chapter: ChapterRecord,
        treeUri: String,
        currentChapterId: String,
        forward: Boolean,
    ) {
        if (neighborJobs.containsKey(chapter.chapterId)) return
        // 先放一个"加载中"的占位：项列表因此立刻包含目标章的过渡页，
        // 读者翻到章末时看到的是"正在载入下一章"而不是空白。
        val placeholder = ViewerChapter(
            chapter = chapter,
            pages = emptyList(),
            source = _state.value.chapters?.current?.source ?: return,
            state = ViewerChapter.LoadState.LOADING,
        )
        applyNeighbor(currentChapterId, placeholder, forward)

        neighborJobs[chapter.chapterId] = viewModelScope.launch {
            val resolved = when (val result = loadChapter(chapter, treeUri)) {
                is ChapterLoadResult.Ok -> result.chapter
                is ChapterLoadResult.Failed -> placeholder.copy(state = ViewerChapter.LoadState.FAILED)
            }
            // 结果回来时当前章可能已经换了：只在仍然相邻时应用，否则丢弃。
            val now = _state.value.chapters ?: return@launch
            applyNeighbor(now.current.chapterId, resolved, forward)
        }
    }

    /** 把相邻章写进状态并重建项列表。 */
    private fun applyNeighbor(currentChapterId: String, neighbor: ViewerChapter, forward: Boolean) {
        _state.update { snapshot ->
            val existing = snapshot.chapters ?: return@update snapshot
            if (existing.current.chapterId != currentChapterId) return@update snapshot
            val updated = if (forward) existing.copy(next = neighbor) else existing.copy(previous = neighbor)
            rebuild(snapshot, updated)
        }
    }

    /**
     * 重建项列表，同时**保持读者当前所在的项**。
     *
     * 这一步不能省：预载完成会把上一章的页插到列表前面，于是所有绝对下标整体后移。
     * 若不按身份重新定位，读者会突然跳到另一页。
     */
    private fun rebuild(snapshot: ReaderUiState, chapters: ViewerChapters): ReaderUiState {
        val readerItems = buildReaderItems(chapters, snapshot.settings.alwaysShowChapterTransition)
        val newIndex = reanchorIndex(snapshot.items, snapshot.currentPageIndex, readerItems.items)
        return snapshot.copy(chapters = chapters, items = readerItems.items, currentPageIndex = newIndex)
    }

    /** 设置变化时（分页 ↔ 条漫）重新按身份定位，避免下标语义变化导致跳页。 */
    private fun reanchorCurrentPage() {
        _state.update { snapshot ->
            val chapters = snapshot.chapters ?: return@update snapshot
            val readerItems = buildReaderItems(chapters, snapshot.settings.alwaysShowChapterTransition)
            val newIndex = reanchorIndex(snapshot.items, snapshot.currentPageIndex, readerItems.items)
            snapshot.copy(items = readerItems.items, currentPageIndex = newIndex)
        }
    }

    /** 按项身份在新列表里找回位置；身份丢失时退回到夹取后的原下标。 */
    private fun reanchorIndex(
        oldItems: List<ReaderItem>,
        oldIndex: Int,
        newItems: List<ReaderItem>,
    ): Int {
        if (newItems.isEmpty()) return 0
        val anchorKey = oldItems.getOrNull(oldIndex)?.key
        if (anchorKey != null) {
            val found = newItems.indexOfFirst { it.key == anchorKey }
            if (found >= 0) return found
        }
        return oldIndex.coerceIn(newItems.indices)
    }

    /** 列出一章的页；失败返回结构化原因而不是抛异常（章级问题要能显示重试）。 */
    private suspend fun loadChapter(chapter: ChapterRecord, treeUri: String): ChapterLoadResult = try {
        when (val opened = pageSourceFactory.open(treeUri, chapter)) {
            is PageSourceOpenResult.Unsupported -> ChapterLoadResult.Failed(opened.reason)

            is PageSourceOpenResult.Ready -> {
                val pages = opened.source.pages()
                if (pages.isEmpty()) {
                    ChapterLoadResult.Failed("「${chapter.title}」目录内没有受支持的图片")
                } else {
                    ChapterLoadResult.Ok(
                        ViewerChapter(chapter = chapter, pages = pages, source = opened.source),
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ChapterLoadResult.Failed(error.message ?: "无法打开「${chapter.title}」")
    }

    private sealed interface ChapterLoadResult {
        data class Ok(val chapter: ViewerChapter) : ChapterLoadResult
        data class Failed(val reason: String) : ChapterLoadResult
    }

    // ------------------------------------------------------------ 翻页

    /**
     * 翻页（阅读顺序上的增量）。
     *
     * 可以落在章节过渡项上——那正是"进入下一章"的表达方式，与 Mihon 一致
     * （它的分页器同样不跨章翻页，跨界由过渡项承担）。
     */
    fun turnPage(delta: Int) {
        val current = _state.value
        if (current.items.isEmpty()) return
        val target = (current.currentPageIndex + delta).coerceIn(current.items.indices)
        if (target == current.currentPageIndex) return
        settleOn(target)
    }

    /** 页码滑杆：跳到当前章的第 [localPageIndex] 页（0 基）。 */
    fun jumpToPage(localPageIndex: Int) {
        val current = _state.value
        val chapter = current.chapters?.current ?: return
        if (localPageIndex !in chapter.pages.indices) return
        val offset = current.indexOfFirstPageOf(chapter.chapterId)
        if (offset < 0) return
        settleOn(offset + localPageIndex)
    }

    /**
     * 分页器或条带落到了第 [absoluteIndex] 项。
     *
     * 这是**唯一**的落点入口：分页滑动、条带滚动、点按翻页、滑杆跳转最后都汇聚到这里，
     * 因此跨章判定与进度落库只有一处，不会出现"某个手势路径忘了存进度"。
     */
    fun onItemSettled(absoluteIndex: Int) {
        settleOn(absoluteIndex)
    }

    private fun settleOn(absoluteIndex: Int) {
        val snapshot = _state.value
        if (absoluteIndex !in snapshot.items.indices) return
        if (absoluteIndex == snapshot.currentPageIndex) return

        when (val item = snapshot.items[absoluteIndex]) {
            is ReaderItem.Transition -> {
                // 目标章**已经加载好**时直接推进到它的第一页，不再把过渡项当作停留点。
                //
                // 落点必须显式算出来，不能指望 `promoteChapter` 用"项身份"找回来：
                // 那个身份**就是过渡项自身**，提升后会落回过期位置（曾经因此把读者
                // 送回上一章，并且看不到过渡页）。而"过渡项在新列表里的下一个位置"
                // 必然是目标章的第一页——这正是"翻过这一页就到了下一章"的字面含义。
                //
                // 只有向前翻会走到这里：过渡项只插入在当前章**之后**（它的 `from` 就是
                // 当前章），所以往回翻的落点是上一章的最后一页，走下面的 PageItem 分支。
                val target = item.to
                if (target != null && target.state == ViewerChapter.LoadState.Loaded) {
                    promoteChapter(target.chapterId, afterTransitionFrom = item)
                } else {
                    _state.update { it.copy(currentPageIndex = absoluteIndex) }
                    retryNeighborIfFailed(item)
                }
            }

            is ReaderItem.PageItem -> {
                val activeId = snapshot.chapters?.current?.chapterId
                // 先记录落点再考虑换章：顺序反了的话，换章过程里的任何位置调整都会把
                // 读者刚翻到的页覆盖掉，进度也就被写错。
                _state.update { it.copy(currentPageIndex = absoluteIndex) }
                if (item.chapterId != activeId) {
                    // 读者翻进了相邻章（例如从上一章末尾继续往前）：把它提升为当前章。
                    promoteChapter(item.chapterId, afterTransitionFrom = null)
                } else {
                    saveProgress()
                }
            }
        }
    }

    /**
     * 把已加载的相邻章提升为当前章。
     *
     * @param afterTransitionFrom 非空表示这次提升是"翻过了这个过渡项"触发的，
     *   落点取该项在新列表中的下一个位置，也就是目标章的第一页。
     *   为空表示读者已经落在目标章的某一页上，此时按项身份找回位置——
     *   新列表前面多了"上一章"的页与过渡项，绝对下标会平移，必须重新定位。
     *
     * 提升后必须**重新预载两侧**：原来的"上一章"位置现在应该放更早的一章。
     */
    private fun promoteChapter(chapterId: String, afterTransitionFrom: ReaderItem.Transition?) {
        val snapshot = _state.value
        val existing = snapshot.chapters ?: return
        val promoted = when (chapterId) {
            existing.previous?.chapterId -> existing.previous
            existing.next?.chapterId -> existing.next
            else -> null
        } ?: return
        if (promoted.state != ViewerChapter.LoadState.Loaded) return
        val index = snapshot.chapterList.indexOfFirst { it.chapterId == chapterId }
        if (index < 0) return

        val chapters = ViewerChapters(current = promoted)
        val readerItems = buildReaderItems(chapters, snapshot.settings.alwaysShowChapterTransition)
        val newIndex = if (afterTransitionFrom != null) {
            landingAfterTransition(
                newItems = readerItems.items,
                transitionKey = afterTransitionFrom.key,
                fallback = readerItems.currentChapterOffset,
            )
        } else {
            val anchorKey = snapshot.items.getOrNull(snapshot.currentPageIndex)?.key
            anchorKey
                ?.let { key -> readerItems.items.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
                ?: readerItems.currentChapterOffset
        }
        val safeIndex = newIndex.coerceIn(readerItems.items.indices)

        _state.update {
            it.copy(
                currentChapterIndex = index,
                chapters = chapters,
                items = readerItems.items,
                currentPageIndex = safeIndex,
                pageHeights = emptyMap(),
            )
        }
        saveProgress()
        preloadNeighbors(index)
    }

    /** 过渡项的目标章若是失败态则重试一次（Mihon 的过渡页也带重试按钮）。 */
    fun retryNeighbor(item: ReaderItem.Transition) {
        retryNeighborIfFailed(item)
    }

    /** 过渡项的目标章若是失败态则重试一次（Mihon 的过渡页也带重试按钮）。 */
    private fun retryNeighborIfFailed(item: ReaderItem.Transition) {
        val snapshot = _state.value
        val treeUri = snapshot.sourceTreeUri ?: return
        val target = item.to ?: return
        if (target.state != ViewerChapter.LoadState.FAILED) return
        val current = snapshot.chapters?.current ?: return
        val forward = snapshot.chapters?.next?.chapterId == target.chapterId
        loadNeighbor(target.chapter, treeUri, current.chapterId, forward)
    }

    // ------------------------------------------------------------ 条漫

    /**
     * 条带的滚动位置变化。
     *
     * 与分页走同一个落点入口；区别只是调用方用"页底越过视口底"算出这一项，
     * 而不是"视口里是哪一项"（Mihon 的条漫判定与分页不同）。
     */
    fun onScrolledToItem(absoluteIndex: Int) {
        settleOn(absoluteIndex)
    }

    /** 记录一页在条带里的布局高度，供滚动定位使用。 */
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
            TapAction.PREVIOUS -> turnPage(-1)
            TapAction.NEXT -> turnPage(1)
            // 屏幕左右两栏**刻意不按阅读模式翻转**：右到左靠分页列表反转实现，
            // 所以"屏幕左侧 = 阅读下一页"自然成立。多翻一次就会反向翻页（Mihon 也不翻）。
            TapAction.PAN_LEFT -> turnPage(-1)
            TapAction.PAN_RIGHT -> turnPage(1)
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

    /**
     * 用户改了阅读模式。
     *
     * 写的是**这部漫画的覆盖**而不是全局默认：阅读器里的模式切换是"对这部作品生效"
     * （Mihon 的 `mangas.viewer` 位域就是同一语义），全局默认在设置界面里改。
     * 这样一部条漫不会因为在这里切了一下就把所有漫画都改成条漫。
     */
    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch {
            mangaModeOverride = mode
            mangaRepository.updateReaderOverrides(
                mangaId = mangaId,
                mode = mode,
                orientation = mangaOrientationOverride,
            )
            // 立即反映到界面，不等设置流的下一次发射。
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
    fun setOrientationOverride(orientation: com.lmreader.core.model.ReaderOrientation?) {
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

    /**
     * 修改**全局**阅读设置（设置界面的"通用"与"自定义滤镜"两页）。
     *
     * 与 [setReadingMode] 的分工：那个写这部漫画的覆盖，这个写全局默认。
     */
    fun updateGlobalSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { readerPreferences.update(transform) }
    }

    // ------------------------------------------------------------ 进度

    /** 把当前落点写成阅读进度。落在过渡项上时不写（那不是某一页）。 */
    fun saveProgress() {
        val current = _state.value
        val item = current.items.getOrNull(current.currentPageIndex) as? ReaderItem.PageItem ?: return
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

    companion object {
        const val RESUME_CHAPTER = "resume"

        /** 没有指定起始页的哨兵值；见 [requestedStartPage]。 */
        const val NO_START_PAGE = -1

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
                )
            }
        }
    }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val mangaTitle: String = "",
    val sourceTreeUri: String? = null,
    /** 全部章节，仅用于换章与预载时定位相邻项。 */
    val chapterList: List<ChapterRecord> = emptyList(),
    val currentChapterIndex: Int = 0,
    val chapters: ViewerChapters? = null,
    /** 分页器/条带实际显示的项：上一章页 + 过渡 + 当前章页 + 过渡 + 下一章页。 */
    val items: List<ReaderItem> = emptyList(),
    /** 在 [items] 中的绝对下标。 */
    val currentPageIndex: Int = 0,
    val chromeVisible: Boolean = true,
    val tapZoneOverlayVisible: Boolean = false,
    /** 已探测到的条带页高（dp），键为页 ID。 */
    val pageHeights: Map<String, Int> = emptyMap(),
    val settings: ReaderSettings = ReaderSettings(),
    /**
     * 这部漫画的阅读模式覆盖；null 表示跟随全局默认。
     *
     * 与 [settings] 分开暴露：设置界面要显示"当前是否设过覆盖"并据此决定是否给出
     * "恢复默认"，而 [settings] 里已经是合并后的值，看不出到底有没有覆盖。
     */
    val mangaModeOverride: ReadingMode? = null,
    /** 这部漫画的屏幕方向覆盖；null 表示跟随全局默认。 */
    val mangaOrientationOverride: com.lmreader.core.model.ReaderOrientation? = null,
    val error: String? = null,
) {
    val currentChapter: ChapterRecord? get() = chapterList.getOrNull(currentChapterIndex)

    /** 当前章的页清单。 */
    val currentPages: List<ReaderPage> get() = chapters?.current?.pages.orEmpty()

    /** 当前章内已读到的页序号（0 基）；落在过渡项上时回退到 0。 */
    val localPageIndex: Int
        get() = (items.getOrNull(currentPageIndex) as? ReaderItem.PageItem)?.page?.ordinal ?: 0

    val hasPreviousChapter: Boolean get() = currentChapterIndex > 0
    val hasNextChapter: Boolean get() = currentChapterIndex < chapterList.lastIndex
    val readingMode: ReadingMode get() = settings.readingMode
    val isContinuous: Boolean get() = readingMode.continuous

    /** 当前落点是否是章节过渡项。 */
    val currentItemIsTransition: Boolean
        get() = items.getOrNull(currentPageIndex) is ReaderItem.Transition

    /** 指定章第一项在 [items] 里的绝对下标；找不到返回 -1。 */
    fun indexOfFirstPageOf(chapterId: String): Int =
        items.indexOfFirst { it is ReaderItem.PageItem && it.chapterId == chapterId }
}
