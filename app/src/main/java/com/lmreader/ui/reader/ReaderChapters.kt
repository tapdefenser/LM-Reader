package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage

/**
 * 一个可供阅读的章节：它的记录、页清单、页源与加载状态。
 *
 * 为什么要把它独立出来而不是直接放 `ChapterRecord`：阅读器需要的不只是"有哪些章"，
 * 还要**每一章的页清单与页源**。
 */
data class ViewerChapter(
    val chapter: ChapterRecord,
    val pages: List<ReaderPage>,
    val source: PageSource,
    val state: LoadState = LoadState.Loaded,
) {
    val chapterId: String get() = chapter.chapterId
    val title: String get() = chapter.title

    /** 页清单是否可用：状态为 [LoadState.Loaded] **且**确实有页。 */
    val isUsable: Boolean get() = state == LoadState.Loaded && pages.isNotEmpty()

    enum class LoadState {
        /** 正在列页。 */
        LOADING,

        /** 页清单可用。 */
        Loaded,

        /** 加载失败。 */
        FAILED,
    }
}

/**
 * 阅读器的一条直线。
 *
 * ## 这是本设计的核心，请先读这一段
 *
 * 阅读器要展示的东西就是一条**线性的项列表**：
 *
 * ```
 * … issue1 的页、[过渡页]、issue2 的页、[过渡页]、issue3 的页 …
 * ```
 *
 * 过渡页只是这条线上的**一个普通位置**，和"一页图"没有本质区别——它显示的不是图片而是
 * "已读完 issue1 / 下一章 issue2"。因此：
 *
 * - **翻页就只是在这条线上前后走一格**，没有"跨章"这个特殊动作；
 * - **窗口（这条线上有哪些章）在阅读过程中固定不变**，只在快走到一端时才向那一端补；
 * - 因此**所有项的下标在阅读过程中不变**，"翻页跳到别的页"在结构上不可能发生。
 *
 * 这条设计是踩过坑之后收敛出来的。上一版把"读者一碰到过渡页"当成"跨章事件"，去提升章节、
 * 重建整个列表，于是：下标整体变了而分页器还停在旧下标上 → 跳页；重建时丢掉已加载的页
 * → 明明已就绪却显示"正在载入"；提升与异步加载互相覆盖 → 有时进下一章、有时被送回上一章。
 * 把跨章从"事件"降级为"位置"之后，这些全部消失。
 */
data class ViewerChapters(
    /** 窗口里的章，按目录顺序排列。 */
    val window: List<ViewerChapter>,
    /** 读者当前所在章在 [window] 里的下标。 */
    val currentIndex: Int,
) {
    val current: ViewerChapter get() = window[currentIndex]

    /** 当前章是这一部里的第几章（用于页码指示与进度落库）。 */
    val currentChapterId: String get() = current.chapterId

    /**
     * 整条直线的项列表。
     *
     * ## 只收录"已知页数"的章
     *
     * 还没加载完的章**完全不出现**：它既不贡献页，也不贡献过渡页。理由是过渡页的语义是
     * "上一章读完了、下面是下一章"，若下一章还没加载出来就插入过渡页，读者会看到一个
     * 悬空的过渡页（甚至两个过渡页挨在一起）。
     *
     * 加载完成后它的页与过渡页一起插入，此时按页身份重新定位，画面不跳。
     *
     * ## 过渡页可以关掉
     *
     * [showTransitions] 为 false 时章与章直接相接，一次翻页就从上一章末页到下一章首页。
     * 它与翻页逻辑无关——只是"组装列表时插不插这一项"。
     */
    /**
     * 整条直线的项列表。
     *
     * ## 只收录"已知页数"的章
     *
     * 还没加载完的章**完全不出现**：它既不贡献页，也不贡献过渡页。理由是过渡页的语义是
     * "上一章读完了、下面是下一章"，若下一章还没加载出来就插入过渡页，读者会看到一个
     * 悬空的过渡页（甚至两个过渡页挨在一起）。
     *
     * 加载完成后它的页与过渡页一起插入，此时按页身份重新定位，画面不跳。
     *
     * ## 过渡页没有方向
     *
     * 一个过渡项就是"前一章与后一章之间那一张"。往前翻会遇到它、往后翻会遇到同一个它，
     * 不是两个不同的项——这正是"过渡页就是夹在中间的一张图"的字面含义。
     *
     * @param isFinalChapter 给定的章是否是整部的最后一章；是则在它后面补一个终点标记
     */
    fun items(showTransitions: Boolean, isFinalChapter: (String) -> Boolean): List<ReaderItem> {
        val items = ArrayList<ReaderItem>()
        window.forEachIndexed { index, chapter ->
            if (!chapter.isUsable) return@forEachIndexed
            // 章与章之间恰好一个过渡项，且它**归属后一章**（这样它在任何窗口下键都一样）。
            if (index > 0 && showTransitions) {
                items += ReaderItem.Transition(from = previousUsable(index), to = chapter)
            }
            items += chapter.pages.map { ReaderItem.PageItem(it, chapter) }
        }
        // 末尾的"到底了"标记：窗口里最后那一章就是整部的最后一章时才加。
        val last = window.lastOrNull()
        if (showTransitions && last != null && last.isUsable && isFinalChapter(last.chapterId)) {
            items += ReaderItem.Transition(from = last, to = null)
        }
        return items
    }

    /** [index] 之前最近的一个"已知页数"的章；用于给过渡项标注它从哪一章来。 */
    private fun previousUsable(index: Int): ViewerChapter? =
        window.subList(0, index).lastOrNull { it.isUsable }

    /** 用加载好的状态**替换**窗口里同 ID 的那一格；不在窗口里时返回自身。 */
    fun withChapter(replacement: ViewerChapter): ViewerChapters {
        val position = window.indexOfFirst { it.chapterId == replacement.chapterId }
        if (position < 0) return this
        return copy(window = window.toMutableList().also { it[position] = replacement })
    }

    /** 窗口里这一章是第几章（在 [window] 里的下标）；不在窗口里返回 -1。 */
    fun indexOf(chapterId: String): Int = window.indexOfFirst { it.chapterId == chapterId }
}

/**
 * 阅读器里的一个可显示项。
 *
 * Mihon 把"页"和"章节过渡"放在同一个适配器列表里（`PagerViewerAdapter.items`），
 * 于是翻页与过渡共享同一套位置寻址。我们照做——这正是"过渡页只是线上的一个位置"的由来。
 */
sealed interface ReaderItem {
    /** 稳定身份，用于分页器的 `key` 与滚动定位。 */
    val key: String

    /** 该项属于哪一章。 */
    val chapterId: String

    data class PageItem(
        val page: ReaderPage,
        val chapter: ViewerChapter,
    ) : ReaderItem {
        override val key: String get() = page.pageId
        override val chapterId: String get() = chapter.chapterId
    }

    /**
     * 章节过渡占位：夹在两章之间的一张"图"。
     *
     * [to] 为空表示"没有更多章节了"，用于在末章之后给出明确的终点（Mihon 同样插入）。
     * [from] 是它**归属**的那一章——归属后一章而不是前一章，是为了让同一个边界在任何
     * 窗口下都算出同一个 `key`。
     *
     * **没有方向**：往前翻与往后翻遇到的是同一个过渡项。
     */
    data class Transition(
        val from: ViewerChapter?,
        val to: ViewerChapter?,
    ) : ReaderItem {
        override val key: String =
            "transition:${from?.chapterId.orEmpty()}->${to?.chapterId.orEmpty()}"
        override val chapterId: String get() = to?.chapterId ?: from?.chapterId.orEmpty()

        /** 没有目标章节：这是章节目录的终点。 */
        val isEnd: Boolean get() = to == null
    }
}

/**
 * 预载窗口：当前章两侧各自要拿哪几章。
 *
 * ## 预算规则（用户定的）
 *
 * 「预载页数」默认 9。跨一章的代价 = **1 个过渡页 + 该章页数**——过渡页在阅读器里占一项，
 * 因此要算一页。预算耗尽即停。于是"预算 9 页而后一章只有 2 页"会得到 1 + 2 = 3，
 * 还剩 6 页，于是继续要下一章。
 *
 * 页数未知的章是**边界章**：它必须放进来并要求枚举一次，否则第一部打开的漫画会因为
 * "谁都不知道有几页"而把窗口算成空的，末页只能显示"已是最后一章"而后头还有几十章。
 */
internal data class PreloadPlan(
    /** 要收进窗口的上一章下标，由远及近。 */
    val previousIndices: List<Int>,
    /** 要收进窗口的下一章下标，由近及远。 */
    val nextIndices: List<Int>,
) {
    companion object {
        val EMPTY = PreloadPlan(emptyList(), emptyList())

        fun compute(
            chapterCount: Int,
            currentIndex: Int,
            budget: Int,
            maxChapters: Int,
            pagesOf: (Int) -> Int? = { null },
        ): PreloadPlan {
            if (budget <= 0 || maxChapters <= 0 || chapterCount <= 0) return EMPTY
            if (currentIndex !in 0 until chapterCount) return EMPTY
            return PreloadPlan(
                previousIndices = walk(chapterCount, currentIndex, budget, maxChapters, false, pagesOf),
                nextIndices = walk(chapterCount, currentIndex, budget, maxChapters, true, pagesOf),
            )
        }

        private fun walk(
            chapterCount: Int,
            currentIndex: Int,
            budget: Int,
            maxChapters: Int,
            forward: Boolean,
            pagesOf: (Int) -> Int?,
        ): List<Int> {
            val collected = ArrayList<Int>(maxChapters)
            var remaining = budget
            var distance = 1
            while (collected.size < maxChapters) {
                val index = if (forward) currentIndex + distance else currentIndex - distance
                if (index !in 0 until chapterCount) break
                val known = pagesOf(index)
                if (known == null) {
                    // 边界章：放进来让调用方去枚举它，然后停下——它的页数出来之前
                    // 无法判断预算够不够继续。
                    collected += index
                    break
                }
                // 跨过一章的代价：一个过渡页 + 该章页数。
                if (remaining < 1 + known) {
                    if (remaining > 0) collected += index
                    break
                }
                remaining -= 1 + known
                collected += index
                distance++
            }
            return collected
        }
    }
}

/**
 * 从当前章向两侧展开窗口，补齐还没在窗口里的章。
 *
 * @param existing 已经有页清单的章；它们的页数用于预算计算，且**必须留在窗口里**——
 *   把已加载的章丢掉再重新加载，正是上一版"明明已就绪却显示正在加载"的原因。
 * @return 新的窗口（按目录顺序）与当前章在其中的下标
 */
internal fun buildWindow(
    chapterList: List<ChapterRecord>,
    currentChapterId: String,
    plan: PreloadPlan,
    existing: Map<String, ViewerChapter>,
): Pair<List<ViewerChapter>, Int> {
    val currentIndex = chapterList.indexOfFirst { it.chapterId == currentChapterId }
    if (currentIndex < 0) return emptyList<ViewerChapter>() to 0
    val wanted = sortedSetOf(currentIndex)
    wanted += plan.nextIndices
    wanted += plan.previousIndices
    // 已在窗口里的章一律保留：丢掉它们的页清单会让已经预载好的内容白费。
    chapterList.forEachIndexed { index, record ->
        if (existing.containsKey(record.chapterId)) wanted += index
    }
    val window = wanted.mapNotNull { index ->
        val record = chapterList.getOrNull(index) ?: return@mapNotNull null
        existing[record.chapterId]
    }
    val newCurrentIndex = window.indexOfFirst { it.chapterId == currentChapterId }
    return window to newCurrentIndex.coerceAtLeast(0)
}

/**
 * 项列表重建后按项身份把读者放回原来的位置。
 *
 * 窗口扩张会把新章插到列表前面或后面，于是绝对下标平移；不重新定位读者就会跳到别的页。
 * **这是"补窗口时画面不跳"的唯一保证**，因此每次重建都必须经过它。
 */
internal fun reanchorIndex(
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

/** 某一页在 [items] 里的下标；找不到返回 -1。 */
internal fun List<ReaderItem>.indexOfPage(pageId: String): Int =
    indexOfFirst { it is ReaderItem.PageItem && it.page.pageId == pageId }

/** 那一章第一页在 [items] 里的下标；该章不在列表里时返回 -1。 */
internal fun List<ReaderItem>.indexOfFirstPageOfChapter(chapterId: String): Int =
    indexOfFirst { it is ReaderItem.PageItem && it.chapter.chapterId == chapterId }

/** 当前项属于哪一章；列表为空时返回 null。 */
internal fun List<ReaderItem>.chapterIdAt(index: Int): String? =
    getOrNull(index)?.chapterId
