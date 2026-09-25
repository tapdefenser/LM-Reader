package com.lmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.reader.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReaderViewModel(
    private val mangaId: String,
    private val requestedChapterId: String,
    private val mangaRepository: MangaRepository,
    private val progressRepository: ReadingProgressRepository,
    private val pageSourceFactory: PageSourceFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var savedProgress: ReadingProgress? = null
    private val progressSaveMutex = Mutex()

    init {
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
                val restorePage = savedProgress
                    ?.takeIf { requestedChapterId == RESUME_CHAPTER && it.chapterId == target.chapters[chapterIndex].chapterId }
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

    fun openPreviousChapter() = moveChapter(-1)

    fun openNextChapter() = moveChapter(1)

    fun onPageChanged(pageIndex: Int) {
        val current = _state.value
        if (pageIndex !in current.pages.indices || pageIndex == current.currentPageIndex) return
        _state.update { it.copy(currentPageIndex = pageIndex) }
        saveProgress()
    }

    fun toggleChrome() {
        _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    fun saveProgress() {
        val current = _state.value
        val chapter = current.currentChapter ?: return
        if (current.pages.isEmpty()) return
        val progress = ReadingProgress(
            mangaId = mangaId,
            chapterId = chapter.chapterId,
            pageOrdinal = current.currentPageIndex.coerceIn(current.pages.indices),
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

    private fun moveChapter(delta: Int) {
        val target = _state.value.currentChapterIndex + delta
        if (target !in _state.value.chapters.indices || _state.value.loading) return
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
                        error = null,
                    )
                }
                saveProgress()
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
    val error: String? = null,
) {
    val currentChapter: ChapterRecord? get() = chapters.getOrNull(currentChapterIndex)
    val hasPreviousChapter: Boolean get() = currentChapterIndex > 0
    val hasNextChapter: Boolean get() = currentChapterIndex < chapters.lastIndex
}
