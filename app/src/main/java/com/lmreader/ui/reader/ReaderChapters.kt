package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage

/**
 * 一个可供阅读的章节：它的记录、页清单、页源与加载状态。
 *
 * 为什么要把它独立出来而不是直接放 `ChapterRecord`：阅读器需要的不只是"有哪些章"，
 * 还要**每一章的页清单与页源**。相邻章节必须同时可用才能在章末无缝翻进下一章
 * （Mihon 的 `ReaderChapter` 承载同样三样东西）。
 */
data class ViewerChapter(
    val chapter: ChapterRecord,
    val pages: List<ReaderPage>,
    val source: PageSource,
    val state: LoadState = LoadState.Loaded,
) {
    val chapterId: String get() = chapter.chapterId
    val title: String get() = chapter.title

    enum class LoadState {
        /** 尚未开始加载。 */
        WAITING,

        /** 正在列页。 */
        LOADING,

        /** 页清单可用。 */
        Loaded,

        /** 加载失败，UI 应显示原因与重试。 */
        FAILED,
    }
}

/**
 * 当前章节及其相邻章节。
 *
 * 三章同时持有是 Mihon 的做法，也是"章末接着翻"的必要条件：条漫要在条带尾部预置
 * 下一章的页，分页要在末页之后放一个章节过渡页。只有当前章时，读者永远会撞到一堵墙。
 */
data class ViewerChapters(
    val current: ViewerChapter,
    val previous: ViewerChapter? = null,
    val next: ViewerChapter? = null,
) {
    fun neighbor(forward: Boolean): ViewerChapter? = if (forward) next else previous
}

/**
 * 阅读器里的一个可显示项。
 *
 * Mihon 把"页"和"章节过渡"放在同一个适配器列表里（`PagerViewerAdapter.items`），
 * 于是翻页与过渡共享同一套位置寻址。我们照做——这也是为什么
 * [ReaderItem.Transition] 必须能算出自己的稳定 `key`。
 */
sealed interface ReaderItem {
    /** 稳定身份，用于分页器的 `key` 与滚动定位。 */
    val key: String

    /** 该项属于哪一章；过渡项归属它**出发**的那一章。 */
    val chapterId: String

    data class PageItem(
        val page: ReaderPage,
        val chapter: ViewerChapter,
    ) : ReaderItem {
        override val key: String get() = page.pageId
        override val chapterId: String get() = chapter.chapterId
    }

    /**
     * 章节过渡占位。
     *
     * [to] 为空表示"没有下一章"（或"没有上一章"）：Mihon 在这种情况下**仍然插入**
     * 过渡项，用来表达"到底了"。因此 UI 必须能显示一个"没有更多章节"的过渡，
     * 而不是把它当作错误。
     */
    data class Transition(
        val from: ViewerChapter?,
        val to: ViewerChapter?,
        val forward: Boolean,
    ) : ReaderItem {
        override val key: String =
            "transition:${forward}:${from?.chapterId.orEmpty()}->${to?.chapterId.orEmpty()}"
        override val chapterId: String get() = from?.chapterId ?: to?.chapterId.orEmpty()

        /** 没有目标章节：这是章节目录的端点。 */
        val isEnd: Boolean get() = to == null
    }
}

/**
 * 组装阅读器的项列表。
 *
 * 顺序（对照 Mihon `PagerViewerAdapter.setChapters`）：
 * 上一章页 → 上一章过渡 → 当前章页 → 下一章过渡 → 下一章页。
 *
 * [alwaysShowTransition] 对应 Mihon `always_show_chapter_transition`（默认 true）：
 * 关闭时只在相邻章**尚未加载完成**时才插入过渡——目的是不让读者看到一段空白，
 * 而不是省掉过渡本身。已经加载好的相邻章直接接上页，翻过去是连续的。
 *
 * @return 项列表，以及当前章第一项在列表中的下标（用于把"章节内第几页"换算成
 *   分页器的绝对位置）。
 */
internal fun buildReaderItems(chapters: ViewerChapters, alwaysShowTransition: Boolean): ReaderItems {
    val items = ArrayList<ReaderItem>()

    chapters.previous?.let { previous ->
        items += previous.pages.map { ReaderItem.PageItem(it, previous) }
        if (alwaysShowTransition || previous.state != ViewerChapter.LoadState.Loaded) {
            items += ReaderItem.Transition(from = previous, to = chapters.current, forward = true)
        }
    }

    val currentOffset = items.size
    items += chapters.current.pages.map { ReaderItem.PageItem(it, chapters.current) }

    if (alwaysShowTransition || chapters.next?.state != ViewerChapter.LoadState.Loaded) {
        items += ReaderItem.Transition(from = chapters.current, to = chapters.next, forward = true)
    }
    chapters.next?.let { next ->
        items += next.pages.map { ReaderItem.PageItem(it, next) }
    }

    return ReaderItems(items = items, currentChapterOffset = currentOffset)
}

/** [buildReaderItems] 的结果。 */
internal data class ReaderItems(
    val items: List<ReaderItem>,
    /** 当前章第一项在 [items] 中的下标。 */
    val currentChapterOffset: Int,
)
