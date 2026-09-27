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
 * - **窗口（这条线上有哪些章）只在快走到一端时滑动**，平时不变；
 * - 滑动窗口时按稳定身份重新锚定当前项，因此前端淘汰旧章、后端接入新章也不会跳页。
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
     * 从当前章所在的连续可读区间组装项列表。章与章之间的过渡页仍是普通的一项；
     * 相邻章载入中或失败时，只放一张指向该章的过渡页并停止，不能越过它接上更远的章。
     * 关闭过渡页时只移除这些过渡项，不改变章节连续性。
     */
    fun items(showTransitions: Boolean, isFinalChapter: (String) -> Boolean): List<ReaderItem> {
        val items = ArrayList<ReaderItem>()
        if (window.isEmpty()) return items
        // 落在失败章的过渡项时，currentIndex 属于失败章；仍保留前一章与过渡项。
        val anchor = if (window[currentIndex].isUsable) currentIndex else {
            (currentIndex - 1 downTo 0).firstOrNull { window[it].isUsable }
                ?: (currentIndex + 1..window.lastIndex).firstOrNull { window[it].isUsable }
                ?: return items
        }
        var first = anchor
        while (first > 0 && window[first - 1].isUsable) first--
        var last = anchor
        while (last < window.lastIndex && window[last + 1].isUsable) last++
        for (index in first..last) {
            val chapter = window[index]
            if (index > first && showTransitions) {
                items += ReaderItem.Transition(from = window[index - 1], to = chapter)
            }
            items += chapter.pages.map { ReaderItem.PageItem(it, chapter) }
        }
        if (showTransitions) {
            val blocked = window.getOrNull(last + 1)
            if (blocked != null && !blocked.isUsable) {
                items += ReaderItem.Transition(from = window[last], to = blocked)
            } else if (last == window.lastIndex && isFinalChapter(window[last].chapterId)) {
                items += ReaderItem.Transition(from = window[last], to = null)
            }
        }
        return items
    }

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

/** 两侧各自的预载预算：向阅读方向 N 格，反方向 N/2 格。 */
internal data class PrefetchBudget(val forward: Int, val backward: Int)

internal fun prefetchBudget(total: Int): PrefetchBudget = PrefetchBudget(
    forward = total.coerceAtLeast(0),
    backward = total.coerceAtLeast(0) / 2,
)

/**
 * 预载窗口：当前章前后各自要拿哪几章。
 *
 * ## 预算规则（用户定的）
 *
 * 「预载页数」默认 9。跨一章的代价 = **1 个过渡页 + 该章页数**——过渡页在阅读器里占一项，
 * 因此要算一页。预算耗尽即停。于是"预算 9 页而后一章只有 2 页"会得到 1 + 2 = 3，
 * 还剩 6 页，于是继续要下一章。
 *
 * 前后**各有独立预算**（[compute] 的 `budget` / `backBudget`）：预载的价值在于"从哪一页
 * 打开"，读者从第 37 页打开时前后都该有内容，否则往回翻就要现等。
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
            backBudget: Int = budget,
            transitionCost: Int = 1,
            pagesOf: (Int) -> Int? = { null },
        ): PreloadPlan {
            if (maxChapters <= 0 || chapterCount <= 0) return EMPTY
            if (currentIndex !in 0 until chapterCount) return EMPTY
            return PreloadPlan(
                previousIndices = walk(
                    chapterCount, currentIndex, backBudget, maxChapters, false, transitionCost, pagesOf,
                ),
                nextIndices = walk(
                    chapterCount, currentIndex, budget, maxChapters, true, transitionCost, pagesOf,
                ),
            )
        }

        private fun walk(
            chapterCount: Int,
            currentIndex: Int,
            budget: Int,
            maxChapters: Int,
            forward: Boolean,
            transitionCost: Int,
            pagesOf: (Int) -> Int?,
        ): List<Int> {
            val collected = ArrayList<Int>(maxChapters)
            if (budget <= 0) return collected
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
                // 开启过渡页时它与图片一样占一格；关闭时列表中没有这一项，代价为 0。
                val cost = transitionCost.coerceAtLeast(0) + known
                if (remaining < cost) {
                    if (remaining > 0) collected += index
                    break
                }
                remaining -= cost
                collected += index
                distance++
            }
            return collected
        }
    }
}

/**
 * 从当前章向两侧展开窗口：结果是**包含当前章的连续目录区间**，且只收规划覆盖、
 * `existing` 里**确实有**的章。
 *
 * ## 为什么必须连续
 *
 * 窗口是项列表的骨架。若允许缺口（例如 A、C 有页而中间的 B 没有），过渡页就会把 A
 * 直接接到 C——读者看到的"下一章"与目录里的下一章不是同一章；B 加载完成后又会插到
 * 中间，让已读位置整体平移。要求连续之后，缺口处的章到位时只是把区间**扩一格**，
 * 已有项的身份不变，[reanchorIndex] 照常把读者放回原处。
 *
 * ## 展开规则
 *
 * 从当前章出发，左右各一次扩一格，**在第一个收不进的格子停下**（不跨过它继续往外找）：
 * 目录里没有这个下标、规划没覆盖它、或 `existing` 里没有它——三者都算边界。
 * `FAILED` 的章也在 `existing` 里，因此**算作存在**（它是一个确定状态，跳过它反而
 * 会在窗口里挖出缺口）。
 *
 * @param existing 已经有页清单（或已明确失败）的章；只取当前规划覆盖的部分。走远的章
 *   不能永久保留，否则数千章漫画会让页清单与页源随阅读进度无界增长。
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
    val planned = sortedSetOf(currentIndex)
    planned += plan.nextIndices
    planned += plan.previousIndices

    // 能收进窗口的那一格；越出目录、规划未覆盖、existing 里没有——都返回 null。
    fun at(index: Int): ViewerChapter? {
        if (index !in chapterList.indices) return null
        if (index !in planned) return null
        return existing[chapterList[index].chapterId]
    }

    // 当前章本身不可用时不硬凑窗口：那会让 currentIndex 指向别的章。
    if (at(currentIndex) == null) return emptyList<ViewerChapter>() to 0
    var first = currentIndex
    while (at(first - 1) != null) first--
    var last = currentIndex
    while (at(last + 1) != null) last++
    val window = (first..last).mapNotNull { at(it) }
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
    // 关闭过渡页时，它从列表消失。优先落到目标章首页，再退到来源章末页。
    val old = oldItems.getOrNull(oldIndex)
    if (old is ReaderItem.Transition) {
        old.to?.chapterId?.let { nextChapter ->
            val nextPage = newItems.indexOfFirstPageOfChapter(nextChapter)
            if (nextPage >= 0) return nextPage
        }
        old.from?.chapterId?.let { previousChapter ->
            val previousPage = newItems.indexOfLast {
                it is ReaderItem.PageItem && it.chapterId == previousChapter
            }
            if (previousPage >= 0) return previousPage
        }
    }
    // 窗口收缩移除了旧项时，按旧阅读顺序找仍在新列表中的最近页面。
    for (distance in 1..oldItems.size) {
        val forward = oldItems.getOrNull(oldIndex + distance)?.key
        if (forward != null) {
            val found = newItems.indexOfFirst { it.key == forward }
            if (found >= 0) return found
        }
        val backward = oldItems.getOrNull(oldIndex - distance)?.key
        if (backward != null) {
            val found = newItems.indexOfFirst { it.key == backward }
            if (found >= 0) return found
        }
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
