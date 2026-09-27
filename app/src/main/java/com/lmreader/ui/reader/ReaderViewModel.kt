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
 * 阅读器状态机：章节编排 + 进度（开发文档 12）。
 *
 * ## 章节编排照搬 Mihon 的地方
 *
 * Mihon 的阅读器同时持有当前章与相邻章（`ViewerChapters(curr, prev, next)`），并把
 * "章节过渡"作为与页面并列的项放进同一个列表（`PagerViewerAdapter.setChapters`）。
 * 这样才能做到章末接着翻、条带尾部预置下一章。
 *
 * 与 Mihon 的两处差异，都是应真机反馈做的：
 *
 * 1. **窗口不止一格**。预载量可配（[ReaderSettings.preloadPages]），预算大于一章的页数
 *    时会继续要下一章，因此 [ViewerChapters] 用列表表达两侧的窗口。
 * 2. **过渡页会停住**（[ReaderSettings.pauseOnChapterTransition]）。Mihon 到达过渡页即
 *    自动推进；真机实测里读者因此怀疑自己落错了页，所以默认停下等再翻一次。
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
    /**
     * 页面字节的预取缓存。
     *
     * 可空是为了让不需要 Android 上下文的场景（单元测试）不必构造它；为空时阅读器一切
     * 照旧，只是每页都走页源现读。
     */
    private val prefetcher: PagePrefetcher? = null,
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
    private var mangaOrientationOverride: ReaderOrientation? = null

    /**
     * 已安排的章节加载任务，按章节 ID 保存。
     *
     * 按 ID 保存是为了在换章时取消**已经不在窗口里的**那些任务：不取消会让多个章节
     * 同时列页，既浪费 IO，也可能用过期结果覆盖当前状态。
     */
    private val neighborJobs = HashMap<String, Job>()

    /** 当前生效的预载规划；换章或设置变化时重算。 */
    private var preloadPlan: PreloadPlan = PreloadPlan.EMPTY



    /**
     * 换章代数：每次当前章变化就 +1。
     *
     * [applyWindow] 先用快照算规划、再写回状态，这两步之间读者可能已经换了章（邻章加载
     * 完成、快速连翻都会触发）。不加校验的话，一个**上一章**算出来的窗口会被追加到
     * **当前章**的窗口上，于是窗口变成"当前章之后紧跟着一个已读过的章"这种畸形结构，
     * 项列表随之把某一页画成过渡页、末页误报"已是最后一章"。
     */
    private var chapterGeneration = 0

    init {
        // 设置持续观察：用户在阅读器内改模式后应立刻换布局并停在原处。
        viewModelScope.launch {
            readerPreferences.settings.collect { global ->
                // 每次都重新叠加这部漫画的覆盖，而不是直接用全局值——否则设置流的
                // 任何一次发射都会把漫画级覆盖抹掉。
                val merged = global.withMangaOverride(mangaModeOverride, mangaOrientationOverride)
                val previous = _state.value.settings
                val wasContinuous = _state.value.isContinuous
                val preloadChanged = previous.preloadPages != merged.preloadPages
                _state.update { it.copy(settings = merged) }
                if (wasContinuous != merged.readingMode.continuous) {
                    // 分页 ↔ 条漫互换时项的渲染方式变了，按身份重新定位避免跳页。
                    reanchorCurrentPage()
                }
                // 预载预算变了：重新规划窗口，把新纳入的章排上加载。
                if (preloadChanged && _state.value.chapters != null) {
                    applyWindow()
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
     * 打开第 [chapterIndex] 章，把它放在正中，预载两侧窗口，然后预取页字节。
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
                val readerItems = rebuildItems(_state.value, chapters).second
                // 当前章页在项列表中的起点就是"章内第 0 页"的绝对下标。
                val offset = readerItems.currentChapterOffset ?: 0
                // 换章：让任何还在飞的窗口写入作废（见 chapterGeneration）。
                chapterGeneration++

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
                applyWindow()
            }
        }
    }

    /**
     * 按预载预算算出两侧窗口。
     *
     * 页数来源按优先级：**已加载的页清单**（最准）→ **数据库里的 pageCount**（扫描或
     * 详情页回填写入的）→ 未知。未知的章会被当作"边界章"放进来并要求枚举一次——
     * 没有这一步，第一部打开的漫画就会因为"谁都不知道有几页"而把窗口算成空的，
     * 末页的过渡页只能显示"已是最后一章"，而后面其实还有几十章（真机上就是这样）。
     */
    /** 某一章已知的页数；页清单优先于数据库列，因为它更可能是刚枚举出来的。 */
    private fun knownPageCountOf(window: ViewerChapters?, list: List<ChapterRecord>, index: Int): Int? {
        val record = list.getOrNull(index) ?: return null
        val inWindow = window?.chapterInWindow(record.chapterId)
        if (inWindow != null) return inWindow.knownPageCount
        return record.pageCount?.takeIf { it > 0 }
    }

    /**
     * 计算某一章的窗口应该收哪些章。
     *
     * 页数来源：**已加载的页清单**（最准）→ 数据库的 `pageCount` → 未知。未知的章会被
     * 当作"边界章"放进来并要求枚举一次——没有这一步，第一部打开的漫画会因为"谁都不知道
     * 有几页"而把窗口算成空的，末页只能显示"已是最后一章"，而后面其实还有几十章。
     */
    private fun planFor(
        window: ViewerChapters?,
        chapterList: List<ChapterRecord>,
        currentIndex: Int,
        settings: ReaderSettings,
    ): PreloadPlan = PreloadPlan.compute(
        chapterCount = chapterList.size,
        currentIndex = currentIndex,
        budget = settings.preloadPages,
        maxChapters = MAX_PRELOAD_CHAPTERS_PER_SIDE,
        pagesOf = { index -> knownPageCountOf(window, chapterList, index) },
    )

    /**
     * 窗口外侧再各接一格边界章。
     *
     * 规划用的是"已经知道的页数"，而一章的页数要等它加载完才知道。因此"刚量到页数的那些章"
     * 会立刻改变规划结果，一次规划只能多覆盖一章，窗口永远比预载进度慢一步——读者在章末
     * 看到的就永远是"正在载入"，而下一章其实早就好了。补上这一格，下一轮规划就能看到它的
     * 真实页数，再补下一格，逐格追上预载进度。
     *
     * 单侧最多接 [MAX_PRELOAD_CHAPTERS_PER_SIDE] 格，防止"某章页数始终未知"时无限外扩。
     */
    private fun frontierAdditions(
        list: List<ChapterRecord>,
        window: ViewerChapters,
    ): List<Pair<Int, Boolean>> {
        val currentIndex = list.indexOfFirst { it.chapterId == window.current.chapterId }
        if (currentIndex < 0) return emptyList()
        val additions = ArrayList<Pair<Int, Boolean>>(2)
        val farthestNext = window.nextWindow
            .maxOfOrNull { record -> list.indexOfFirst { it.chapterId == record.chapterId } }
        val farthestPrevious = window.previousWindow
            .minOfOrNull { record -> list.indexOfFirst { it.chapterId == record.chapterId } }
        val nextTarget = (farthestNext ?: currentIndex) + 1
        val previousTarget = (farthestPrevious ?: currentIndex) - 1
        if (nextTarget in list.indices && window.nextWindow.size < MAX_PRELOAD_CHAPTERS_PER_SIDE) {
            additions += nextTarget to true
        }
        if (previousTarget in list.indices && window.previousWindow.size < MAX_PRELOAD_CHAPTERS_PER_SIDE) {
            additions += previousTarget to false
        }
        return additions
    }

    /**
     * 按当前规划补齐窗口，并把结果**原子地**写进状态。
     *
     * ## 为什么必须是一次更新的单一入口
     *
     * 这里连续出过两次错，根因是同一件事：**用快照算出来的结果去和实时状态比较**。
     *
     * 1. 先在 `_state.value` 的快照上补齐，再用 `if (rebuilt != chapters)` 决定要不要写。
     *    于是当"实时状态的页清单已经变了、而快照里没变"时，这次写入被整个跳过——
     *    刚加载好的页永远进不了项列表，读者看到的是"下一章正在载入"，而它其实已经好了。
     * 2. 窗口与项列表分两次读状态再写，两个并发回调（两个邻章同时加载完成）会互相覆盖。
     *
     * 因此现在：读取、补齐、排序、构建项列表、写入全部在**同一个** `_state.update` 里完成，
     * 并且用代数校验丢弃过期的规划结果。窗口与项列表在任何时刻都是一致的，
     * 也不会出现"页明明加载好了却显示正在载入"。
     */
    private fun applyWindow() {
        val generation = chapterGeneration
        // 补齐窗口这一步只依赖"已知页数"，而页数只增不减，因此用快照先算一遍是可以的；
        // 关键是**不能拿它去和实时状态比较**，写入统一在下面的 update 里按实时状态重做。
        val preview = _state.value.chapters ?: return
        val list = _state.value.chapterList
        val currentIndex = _state.value.currentChapterIndex
        val planned = planFor(preview, list, currentIndex, _state.value.settings)
        preloadPlan = planned

        var pending: ViewerChapters? = null
        _state.update { live ->
            if (chapterGeneration != generation) return@update live
            val window = live.chapters ?: return@update live
            // 从**当前章**重新拼窗口，并把旧窗口里**已经加载好**的章节接回去。
            //
            // 不能从旧窗口原样扩张：换章时旧窗口的 current 已经过期（它现在应该待在上一章
            // 列表里）。但也不能把旧窗口里的邻章丢掉——它们的页清单是宝贵的。真机上正是因为
            // 换章时把它们换成空占位，才出现"每跨一章都要等载入，而下一章其实早就加载好了"。
            var rebuilt = ViewerChapters(current = window.current)
            val nextIds = window.nextWindow.mapTo(mutableSetOf()) { it.chapterId }
            for (chapter in window.nextWindow + window.previousWindow) {
                if (!chapter.isUsable) continue
                rebuilt = rebuilt.withAdded(chapter, forward = chapter.chapterId in nextIds)
            }
            val additions = planned.nextIndices.map { it to true } +
                planned.previousIndices.asReversed().map { it to false }
            for ((target, forward) in additions) {
                val record = list.getOrNull(target) ?: continue
                rebuilt = rebuilt.withAdded(placeholderFor(record, window), forward)
            }
            for ((target, forward) in frontierAdditions(list, rebuilt)) {
                val record = list.getOrNull(target) ?: continue
                rebuilt = rebuilt.withAdded(placeholderFor(record, window), forward)
            }
            val sanitized = rebuilt
                .sortedByChapterOrder { id -> list.indexOfFirst { it.chapterId == id } }
                .sanitized { id -> list.indexOfFirst { it.chapterId == id } }
            val readerItems = buildReaderItems(sanitized)
            val items = readerItems.items
            pending = sanitized
            if (sanitized == window && items.size == live.items.size) return@update live
            live.copy(
                chapters = sanitized,
                items = items,
                currentPageIndex = reanchorIndex(live.items, live.currentPageIndex, items),
            )
        }

        val windowNow = pending ?: return
        // 取消已经不在窗口里的加载任务。
        val inWindow = buildSet {
            add(windowNow.current.chapterId)
            windowNow.previousWindow.forEach { add(it.chapterId) }
            windowNow.nextWindow.forEach { add(it.chapterId) }
        }
        for (chapterId in neighborJobs.keys.filterNot { it in inWindow }) {
            neighborJobs.remove(chapterId)?.cancel()
        }

        if (chapterGeneration != generation) return
        scheduleNeighborLoads(list, windowNow, _state.value.sourceTreeUri ?: return)
        // 窗口定下来之后再算预取。顺序不能反：预取要按窗口里的页清单来排序。
        warmPrefetch()
    }

    /**
     * 窗口里某一章的占位。
     *
     * 页源只能借用当前章的实例——项列表只需要章节身份就能拼出来，真正的页源在加载完成
     * 后被替换（`PageSource` 是每章一个实例）。
     */
    private fun placeholderFor(record: ChapterRecord, window: ViewerChapters): ViewerChapter =
        ViewerChapter(
            chapter = record,
            pages = emptyList(),
            source = window.current.source,
            state = ViewerChapter.LoadState.LOADING,
        )

    /**
     * 安排加载，顺序"由近及远"：最可能被翻到的章先就绪。
     *
     * 窗口里出现的章，加上两侧边界章，全部排进加载队列。
     */
    private fun scheduleNeighborLoads(
        list: List<ChapterRecord>,
        window: ViewerChapters,
        treeUri: String,
    ) {
        val order = ArrayList<Pair<Int, Boolean>>()
        window.nextWindow.forEach { record ->
            list.indexOfFirst { it.chapterId == record.chapterId }.takeIf { it >= 0 }
                ?.let { order += it to true }
        }
        window.previousWindow.asReversed().forEach { record ->
            list.indexOfFirst { it.chapterId == record.chapterId }.takeIf { it >= 0 }
                ?.let { order += it to false }
        }
        order += frontierAdditions(list, window)
        for ((target, _) in order) {
            val record = list.getOrNull(target) ?: continue
            if (record.chapterId == window.current.chapterId) continue
            if (window.chapterInWindow(record.chapterId)?.isUsable == true) continue
            loadNeighbor(record, treeUri)
        }
    }

    /**
     * 加载窗口里的一章。
     *
     * 占位（`LOADING`）已由 [applyWindow] 放进窗口，于是项列表里立刻有它的过渡页——
     * 读者翻到章末时看到的是"正在载入下一章"而不是空白。Mihon 的 `ChapterTransition`
     * 就是为这件事存在的。
     */
    private fun loadNeighbor(chapter: ChapterRecord, treeUri: String) {
        if (neighborJobs.containsKey(chapter.chapterId)) return
        val chapters = _state.value.chapters ?: return
        val placeholder = chapters.chapterInWindow(chapter.chapterId) ?: return
        if (placeholder.isUsable) return

        neighborJobs[chapter.chapterId] = viewModelScope.launch {
            val resolved = when (val result = loadChapter(chapter, treeUri)) {
                is ChapterLoadResult.Ok -> result.chapter
                is ChapterLoadResult.Failed -> placeholder.copy(state = ViewerChapter.LoadState.FAILED)
            }
            // **必须在这里重新读状态**，不能用启动时的快照：列一页目录要几百毫秒，
            // 这段时间里读者可能已经换了章。用旧快照写回会把整个窗口（包括当前章）
            // 退回到加载开始前的那一刻——真机上表现为"翻着翻着突然回到上一章"。
            applyLoaded(chapter.chapterId, resolved)
            // 这一章刚拿到页清单：重新规划（真实页数可能比数据库里的更新）并预取。
            applyWindow()
        }
    }

    /**
     * 把加载好的某一章写进窗口并重建项列表，同时保持读者当前所在的项。
     *
     * ## 为什么要在写入时才检查窗口
     *
     * 这个函数由异步的章节加载回调调用，而加载可能比读者的翻页慢得多。若它拿着
     * **启动时**的窗口快照直接写回，就会覆盖掉这期间读者的所有进展——真机上出现过
     * "翻到第 3 章又被送回第 2 章"，根因就是这里。因此：
     *
     * 1. 写回时重新读取 [ReaderUiState.chapters]，而不是用外部传来的快照；
     * 2. 目标章若已经不在窗口里（读者已经走远），整次写回丢弃；
     * 3. 当前章一律沿用**读取到的那一刻**的那一章，绝不因这次写回而改变。
     */
    private fun applyLoaded(targetChapterId: String, loaded: ViewerChapter) {
        _state.update { snapshot ->
            val live = snapshot.chapters ?: return@update snapshot
            if (!live.contains(targetChapterId)) return@update snapshot
            val chapters = live.withChapter(loaded, force = true)
            if (chapters == live) return@update snapshot
            val (window, items) = rebuildItems(snapshot, chapters)
            snapshot.copy(
                chapters = window,
                items = items.items,
                currentPageIndex = reanchorIndex(snapshot.items, snapshot.currentPageIndex, items.items),
            )
        }
    }

    /** 重新按项身份定位，避免项列表重建后跳到别的页。 */
    private fun reanchorCurrentPage() {
        _state.update { snapshot ->
            val chapters = snapshot.chapters ?: return@update snapshot
            val (window, readerItems) = rebuildItems(snapshot, chapters)
            val items = readerItems.items
            snapshot.copy(
                chapters = window,
                items = items,
                currentPageIndex = reanchorIndex(snapshot.items, snapshot.currentPageIndex, items),
            )
        }
    }

    /**
     * 重建项列表的唯一入口。
     *
     * 每次构建之前先把窗口修自洽（[ViewerChapters.sanitized]）：窗口被三处异步代码改写，
     * 任何一处把章放错侧或放重，项列表就会把某一页画成过渡页，或让读者翻到
     * "已是最后一章"而后面其实还有章。与其在每处防御，不如在这里统一兜住。
     *
     * @param snapshot 本次写入所基于的状态。**必须与将要写入的状态是同一次快照**，
     *   因为 [ViewerChapters.sanitized] 要用 `currentChapterIndex` 判断每一章该在哪一侧：
     *   真机上曾经用"实时状态里的下标"去校验"旧快照里的窗口"，于是窗口被整体重排成
     *   错误的形状（当前章被挪进上一章列表、末章的项被打乱），读者来回翻都会撞墙。
     */
    private fun rebuildItems(
        snapshot: ReaderUiState,
        window: ViewerChapters,
    ): Pair<ViewerChapters, ReaderItems> {
        val list = snapshot.chapterList
        val sanitized = window.sanitized { id -> list.indexOfFirst { it.chapterId == id } }
        if (sanitized != window) {
        }
        return sanitized to buildReaderItems(sanitized)
    }

    /** 列出一章的页；失败返回结构化原因而不是抛异常（章级问题要能显示重试）。 */    private suspend fun loadChapter(chapter: ChapterRecord, treeUri: String): ChapterLoadResult = try {
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
        val offset = current.items.indexOfFirstPageOfChapter(chapter.chapterId)
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

        when {
            snapshot.items[absoluteIndex] is ReaderItem.Transition ->
                handleTransitionSettled(snapshot, absoluteIndex, snapshot.items[absoluteIndex] as ReaderItem.Transition)

            snapshot.items[absoluteIndex] is ReaderItem.PageItem ->
                handlePageSettled(snapshot, absoluteIndex, snapshot.items[absoluteIndex] as ReaderItem.PageItem)
        }
    }

    /** 落点是章节过渡项。 */
    private fun handleTransitionSettled(
        snapshot: ReaderUiState,
        absoluteIndex: Int,
        item: ReaderItem.Transition,
    ) {
        // 先把落点记下来：过渡页本身就是一个停留点，读者要能看到"进入下一章"，
        // 也要有机会确认自己为什么换了章。
        _state.update { it.copy(currentPageIndex = absoluteIndex) }
        val target = item.to
        when {
            // 章节目录端点：停在这里就好。
            target == null -> Unit

            snapshot.settings.pauseOnChapterTransition -> retryNeighborIfFailed(item)

            // 立刻推进到目标章第一页；目标章还没加载好就只能先停着，
            // 让读者看到"正在载入下一章"。
            target.isUsable -> promoteChapter(
                chapterId = target.chapterId,
                landingPage = target.pages.first(),
                from = item,
            )

            else -> retryNeighborIfFailed(item)
        }
    }

    /** 落点是某一页。 */
    private fun handlePageSettled(
        snapshot: ReaderUiState,
        absoluteIndex: Int,
        item: ReaderItem.PageItem,
    ) {
        val activeId = snapshot.chapters?.current?.chapterId
        // 先记录落点再考虑换章：顺序反了的话，换章过程里的任何位置调整都会把
        // 读者刚翻到的页覆盖掉，进度也就被写错。
        _state.update { it.copy(currentPageIndex = absoluteIndex) }
        val previous = pastStartChapter(snapshot, absoluteIndex)
        when {
            item.chapterId != activeId ->
                // 读者翻进了窗口里的另一章：提升它。落点用**页身份**表达，
                // 它不随预载窗口变化而失效。
                promoteChapter(chapterId = item.chapterId, landingPage = item.page, from = null)

            // 顺着阅读顺序往前、却离开了当前章的页 → 那是上一章的最后一页，
            // 必须把上一章提升为当前章。不处理的话读者会"撞到墙上弹回来"：
            // 真机上往回翻越过章界时就是这个表现。
            previous != null && previous.isUsable -> promoteChapter(
                chapterId = previous.chapterId,
                landingPage = previous.pages.last(),
                from = null,
            )

            else -> saveProgress()
        }
    }

    /**
     * 判断读者是否刚翻出了当前章的**开头**，返回应该提升的上一章。
     *
     * 结构依据：在 [buildReaderItems] 生成的列表里，某一章**之前**的第一项就是
     * "到达该章"的正向过渡（阅读顺序上，前一章末尾的下一个位置）。因此当读者落在
     * 一个正向过渡上、而它的目标恰好是当前章时，就说明他是从当前章第一页往回翻出来的。
     */
    private fun pastStartChapter(snapshot: ReaderUiState, absoluteIndex: Int): ViewerChapter? {
        val current = snapshot.chapters?.current ?: return null
        val previousItem = snapshot.items.getOrNull(absoluteIndex + 1) as? ReaderItem.PageItem ?: return null
        if (previousItem.chapterId != current.chapterId || previousItem.page.ordinal != 0) return null
        return snapshot.chapters?.previous
    }

    /**
     * 把窗口里已加载的章节提升为当前章。
     *
     * @param landingPage 读者翻到的那一页。这是落点的**首选**依据：页身份与预载窗口无关，
     *   因此不会出现"提升后过渡项从新列表里消失、只能退回章首"的情形。
     * @param from 若非空，表示这次提升是"翻过这个过渡项"触发的；[landingPage] 之外还会
     *   依次尝试过渡项的后继与章首兜底。
     *
     * 提升后必须**重新规划并加载两侧**，否则读者翻两章之后会撞到墙。
     */
    private fun promoteChapter(
        chapterId: String,
        landingPage: ReaderPage,
        from: ReaderItem.Transition?,
    ) {
        val snapshot = _state.value
        val existing = snapshot.chapters ?: return
        val promoted = existing.chapterInWindow(chapterId) ?: return
        if (!promoted.isUsable) return
        val index = snapshot.chapterList.indexOfFirst { it.chapterId == chapterId }
        if (index < 0) return

        // 提升时**保留整个窗口**，只把 current 换成已加载好的那一章。
        //
        // 不能写成 `ViewerChapters(current = promoted)`：那样会丢掉窗口里其他章的页清单，
        // 而它们正是接下来要显示的内容。真机上因此出现"每跨一章都要重新等载入"，
        // 已经加载好的下一章也变成空占位。窗口的自洽性由 `rebuildItems` 里的
        // `sanitized` 兜住（放错侧的章会被挪回正确一侧）。
        val chapters = existing.copy(current = promoted)
        val items = rebuildItems(snapshot, chapters).second.items
        val newIndex = resolveLanding(
            newItems = items,
            preferredPageId = landingPage.pageId,
            transitionKey = from?.key,
            fallbackOffset = 0,
        )
        // 换章：让任何还在飞的窗口写入作废（见 chapterGeneration）。
        chapterGeneration++

        _state.update {
            it.copy(
                currentChapterIndex = index,
                chapters = chapters,
                items = items,
                currentPageIndex = newIndex.coerceIn(items.indices),
                pageHeights = emptyMap(),
            )
        }
        saveProgress()
        applyWindow()
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
        loadNeighbor(target.chapter, treeUri)
    }

    // ------------------------------------------------------------ 预取页字节

    /**
     * 把当前页附近 [ReaderSettings.preloadPages] 页的**图片字节**提前读进磁盘缓存。
     *
     * 与"加载章节"是两件事：加载章节是列页（一次目录枚举，得到页清单），预取是把每页的
     * 图像数据先读出来。真机上这两步都在翻页时被感觉到，而页源是 SAF，
     * 每页一次 `openInputStream` 的延迟并不小。
     *
     * 缓存**只放磁盘**、不放堆：真机上解码一页就已经吃过整图分配的亏（见
     * `ReaderImageView` 的说明），再往堆里压几页字节会把 OOM 重新引回来。
     *
     * 距离计算与窗口规划同一套规则：往后每跨一章先付一个过渡页，再付该章页数。
     */
    private fun warmPrefetch() {
        val prefetcher = prefetcher ?: return
        val snapshot = _state.value
        val chapters = snapshot.chapters ?: return
        val budget = snapshot.settings.preloadPages
        if (budget <= 0) return

        val current = chapters.current
        if (current.pages.isEmpty()) return
        val anchored = (snapshot.items.getOrNull(snapshot.currentPageIndex) as? ReaderItem.PageItem)
            ?.takeIf { it.chapter.chapterId == current.chapterId }
        val localOrdinal = anchored?.page?.ordinal ?: 0

        // 1. 当前章内部：页清单已在手上，按"离当前页的距离"排序。
        val ahead = ArrayList<PrefetchCandidate>()
        val behind = ArrayList<PrefetchCandidate>()
        for (page in current.pages) {
            when {
                page.ordinal > localOrdinal -> ahead += PrefetchCandidate(page, current.source)
                page.ordinal < localOrdinal -> behind += PrefetchCandidate(page, current.source)
            }
        }
        behind.reverse() // 由近及远

        // 2. 后续章节：由近及远，每跨一章先付一个过渡页。
        var remaining = budget
        for (chapter in chapters.nextWindow) {
            remaining -= 1
            if (remaining <= 0) break
            if (!chapter.isUsable) break
            for (page in chapter.pages) {
                if (ahead.size >= budget * 2) break
                ahead += PrefetchCandidate(page, chapter.source)
            }
            remaining -= chapter.pages.size
            if (remaining <= 0) break
        }

        // 3. 前面的章节：同样由近及远。
        var backwardRemaining = budget
        for (chapter in chapters.previousWindow.asReversed()) {
            backwardRemaining -= 1
            if (backwardRemaining <= 0) break
            if (!chapter.isUsable) break
            for (page in chapter.pages.asReversed()) {
                if (behind.size >= budget * 2) break
                behind += PrefetchCandidate(page, chapter.source)
            }
            backwardRemaining -= chapter.pages.size
            if (backwardRemaining <= 0) break
        }

        prefetcher.request(
            scopeKey = prefetchScopeKey(snapshot),
            ahead = ahead.take(budget),
            behind = behind.take(budget),
        )
    }

    /**
     * 预取范围的稳定标识。
     *
     * 只在"这部漫画 + 当前章 + 两侧窗口边界"变化时才需要清理旧缓存，**不随翻页变化**——
     * 否则每翻一页都要删掉整批缓存再重建，比不预取还慢。
     */
    private fun prefetchScopeKey(snapshot: ReaderUiState): String {
        val chapters = snapshot.chapters
        return buildString {
            append(mangaId)
            append('|').append(chapters?.current?.chapterId.orEmpty())
            append('|').append(chapters?.previousWindow?.firstOrNull()?.chapterId.orEmpty())
            append('|').append(chapters?.nextWindow?.lastOrNull()?.chapterId.orEmpty())
        }
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

    override fun onCleared() {
        super.onCleared()
        neighborJobs.values.forEach { it.cancel() }
        neighborJobs.clear()
        prefetcher?.cancelAll()
    }

    companion object {
        const val RESUME_CHAPTER = "resume"

        /** 没有指定起始页的哨兵值；见 [requestedStartPage]。 */
        const val NO_START_PAGE = -1

        /**
         * 单侧最多预载几章。
         *
         * 预算是页数，理论上"每章只有 1 页"的长篇会把整部作品拉进来；这个上限把最坏
         * 情况钉住。取 3 是因为真机样本里一章 29–106 页，而预算上限 60 页在 3 章内
         * 必然用完，再多也不会被预算选中。
         */
        const val MAX_PRELOAD_CHAPTERS_PER_SIDE = 3

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

/** 窗口里指定章的当前状态；不在窗口里时返回 null。 */
private fun ViewerChapters.chapterInWindow(chapterId: String): ViewerChapter? = when (chapterId) {
    current.chapterId -> current
    else -> (previousWindow + nextWindow).firstOrNull { it.chapterId == chapterId }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val mangaTitle: String = "",
    val sourceTreeUri: String? = null,
    /** 全部章节，仅用于换章与预载时定位相邻项。 */
    val chapterList: List<ChapterRecord> = emptyList(),
    val currentChapterIndex: Int = 0,
    val chapters: ViewerChapters? = null,
    /** 分页器/条带实际显示的项：窗口里的章页 + 章之间的过渡项。 */
    val items: List<ReaderItem> = emptyList(),
    /** 在 [items] 中的绝对下标。 */
    val currentPageIndex: Int = 0,
    /**
     * 控制栏是否可见。
     *
     * 默认**隐藏**：阅读器一打开就应该是内容，控制栏是"要看时才叫出来"的东西
     * （Mihon 的 `ReaderActivity` 同样以隐藏态进入）。之前默认可见，真机上表现为
     * "一进阅读器就被上下两条黑栏夹住"。
     */
    val chromeVisible: Boolean = false,
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
    val mangaOrientationOverride: ReaderOrientation? = null,
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

    /**
     * 窗口里是否有章还没加载完（含失败）。
     *
     * 用于区分"到底了"与"还在载入"两种过渡页：只有确认没有更远的章时才敢说到底了，
     * 否则读者会以为作品只有这么几章。
     */
    val windowHasPendingChapters: Boolean
        get() = chapters?.let { viewer ->
            (viewer.previousWindow + viewer.nextWindow).any { !it.isUsable }
        } ?: false

    /** 窗口里是否有章加载失败，供过渡页显示重试。 */
    val windowHasFailedChapter: Boolean
        get() = chapters?.let { viewer ->
            (viewer.previousWindow + viewer.nextWindow).any {
                it.state == ViewerChapter.LoadState.FAILED
            }
        } ?: false
}
