package com.lmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.Category
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.scan.ChapterSyncOutcome
import com.lmreader.core.storage.scan.MangaChapterSyncer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MangaDetailViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val shelfRepository: ShelfRepository,
    private val chapterSyncer: MangaChapterSyncer,
    private val progressRepository: ReadingProgressRepository,
    private val pageSourceFactory: PageSourceFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(MangaDetailUiState())
    val state: StateFlow<MangaDetailUiState> = _state.asStateFlow()

    /**
     * 页数回填只跑一次。
     *
     * 用一个字段而不是每次 `reload()` 都跑：`reload()` 会在每次回到详情页时触发
     * （含从阅读器返回），不能让它每次都去枚举目录。
     */
    private var pageBackfillStarted = false

    init {
        viewModelScope.launch {
            shelfRepository.ensureUncategorized()
            shelfRepository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            loadDetail(showLoading = _state.value.manga == null)
            backfillPageCounts()
        }
    }

    /**
     * 给还没有页数的章节补上页数。
     *
     * ## 为什么需要它
     *
     * 页数原本只在**打开过那一章**时回填（`ImageDirectoryPageSource.pages()` 之后由
     * `updateChapterPageInfo` 写入）。真机核查发现可读的 4704 个章节里**只有 6 个**
     * 有页数——于是详情页几乎看不到「共 X 页」，用户会以为「更新章节」坏了。
     *
     * ## 为什么不在扫描/同步时就全量补齐
     *
     * 那需要打开每一个章节目录并列出其子项。对一部 100 章的漫画就是 100 次目录枚举，
     * 会让扫描变得很慢，而且大多数章节用户根本不会打开。这里改成**按需、有上限**：
     * 每次打开详情页最多补 [PAGE_BACKFILL_LIMIT] 章，剩下的下次再补。
     *
     * ## 与「更新章节」的分工
     *
     * 那个按钮走 `MangaChapterSyncer.sync`，负责**章节集合本身**的变化（新增/消失）。
     * 这里只补页数，不改章节集合，因此不会与它冲突。
     */
    private fun backfillPageCounts() {
        if (pageBackfillStarted) return
        val chapters = _state.value.chapters
        val sourceTreeUri = _state.value.sourceTreeUri ?: return
        val pending = chapters.filter { it.pageCount == null }
        if (pending.isEmpty()) return
        pageBackfillStarted = true

        viewModelScope.launch {
            var filled = 0
            for (chapter in pending.take(PAGE_BACKFILL_LIMIT)) {
                val counted = runCatching {
                    when (val opened = pageSourceFactory.open(sourceTreeUri, chapter)) {
                        is PageSourceOpenResult.Unsupported -> null
                        is PageSourceOpenResult.Ready -> {
                            val pages = opened.source.pages()
                            // 空页清单不写：写了会显示"共 0 页"，比"未知"更糟。
                            if (pages.isEmpty()) null else pages.size to pages.first().documentId
                        }
                    }
                }.getOrNull() ?: continue

                val (pageCount, coverDocumentId) = counted
                runCatching {
                    mangaRepository.updateChapterPageInfo(
                        chapterId = chapter.chapterId,
                        pageCount = pageCount,
                        coverDocumentId = coverDocumentId,
                    )
                }
                filled++
            }
            if (filled > 0) {
                // 只在真的补到了东西时重载：否则每次进详情页都会多一次数据库往返。
                loadDetail(showLoading = false)
            }
        }
    }

    fun syncChapters() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, message = null) }
            try {
                when (val outcome = chapterSyncer.sync(mangaId)) {
                    is ChapterSyncOutcome.Failure -> _state.update {
                        it.copy(syncing = false, message = outcome.reason)
                    }
                    is ChapterSyncOutcome.Success -> {
                        loadDetail(showLoading = false)
                        _state.update {
                            it.copy(syncing = false, message = "章节已更新，共 ${outcome.chapters.size} 章")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(syncing = false, message = error.message ?: "更新章节失败")
                }
            }
        }
    }

    fun addToShelf(categoryId: Long) {
        viewModelScope.launch {
            try {
                shelfRepository.addToShelf(mangaId, categoryId)
                _state.update { it.copy(inShelf = true, message = "已加入书架") }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "加入书架失败") }
            }
        }
    }

    fun removeFromShelf() {
        viewModelScope.launch {
            try {
                shelfRepository.removeFromShelf(mangaId)
                _state.update { it.copy(inShelf = false, message = "已移出书架") }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "移出书架失败") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun loadDetail(showLoading: Boolean) {
        if (showLoading) _state.update { it.copy(loading = true, error = null) }
        try {
            val target = mangaRepository.getBackfillTarget(mangaId)
                ?: error("漫画或来源已不存在")
            val source = sourceRepository.getSource(target.manga.sourceId)
                ?: error("来源已被删除")
            val card = mangaRepository.getCards(listOf(mangaId)).firstOrNull()
            // 阅读进度要一起读出来：章节行要显示"读到第几页"，而且"继续阅读"要能从
            // 那一页打开。放在同一次加载里而不是让 UI 各自去查，避免两处显示不一致。
            val progress = progressRepository.get(mangaId)
            _state.update {
                it.copy(
                    loading = false,
                    manga = target.manga,
                    chapters = target.chapters,
                    sourceTreeUri = source.treeUri,
                    sourceDisplayPath = source.displayPath,
                    inShelf = card?.inShelf == true,
                    progress = progress,
                    error = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = error.message ?: "读取漫画详情失败") }
        }
    }

    companion object {
        fun factory(
            container: com.lmreader.di.AppContainer,
            mangaId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MangaDetailViewModel(
                    mangaId = mangaId,
                    mangaRepository = container.mangaRepository,
                    sourceRepository = container.sourceRepository,
                    shelfRepository = container.shelfRepository,
                    chapterSyncer = container.mangaChapterSyncer,
                    progressRepository = container.readingProgressRepository,
                    pageSourceFactory = container.pageSourceFactory,
                )
            }
        }
    }
}

private const val PAGE_BACKFILL_LIMIT = 8

data class MangaDetailUiState(
    val loading: Boolean = true,
    val manga: MangaRecord? = null,
    val chapters: List<ChapterRecord> = emptyList(),
    val sourceTreeUri: String? = null,
    val sourceDisplayPath: String? = null,
    val inShelf: Boolean = false,
    val categories: List<Category> = emptyList(),
    val syncing: Boolean = false,
    /** 这部漫画的阅读进度；null 表示还没读过。 */
    val progress: ReadingProgress? = null,
    val error: String? = null,
    val message: String? = null,
)
