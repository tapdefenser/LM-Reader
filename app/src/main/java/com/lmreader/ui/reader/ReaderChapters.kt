package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ReaderSettings
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

    /** 页清单是否可用：状态为 [LoadState.Loaded] **且**确实有页。 */
    val isUsable: Boolean get() = state == LoadState.Loaded && pages.isNotEmpty()

    /** 已知页数；未知时为空。用于预载预算计算与 UI 显示。 */
    val knownPageCount: Int?
        get() = pages.size.takeIf { it > 0 } ?: chapter.pageCount?.takeIf { it > 0 }
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
 * 当前章节与其两侧的**窗口**。
 *
 * Mihon 的 `ViewerChapters` 只有 `prev/curr/next` 三格，因为它的预载量固定为一章。
 * 本项目把预载量做成可配置（`ReaderSettings.preloadPages`）：当预算大于一章的页数时
 * 必须继续往后拿——例如"预载 9 页而后一章只有 2 页"，扣掉 1 个过渡页与 2 页之后还剩
 * 6 页，于是要再拿一章。因此这里用**列表**表达窗口，长度由 [PreloadPlan] 算出。
 *
 * ## 为什么"紧邻章"是算出来的而不是存下来的
 *
 * 曾经把 `previous` / `next` 也当成构造参数，于是它们可能与列表不一致——真机上就出过
 * 一次：`applyWindow` 往列表里补了章，而 `next` 仍然是 null，末页因此显示"已是最后一章"，
 * 后面明明还有几十章。现在紧邻章**只由列表推导**，两者不可能再分叉。
 *
 * 列表顺序有约定，[previous] / [next] 依赖它：
 * - [previousWindow] 按"离当前章由远及近"排列，因此最后一格是紧邻的上一章；
 * - [nextWindow] 按"由近及远"排列，因此第一格是紧邻的下一章。
 */
data class ViewerChapters(
    val current: ViewerChapter,
    val previousWindow: List<ViewerChapter> = emptyList(),
    val nextWindow: List<ViewerChapter> = emptyList(),
) {
    /** 紧邻的上一章；窗口为空时为 null。 */
    val previous: ViewerChapter? get() = previousWindow.lastOrNull()

    /** 紧邻的下一章；窗口为空时为 null。 */
    val next: ViewerChapter? get() = nextWindow.firstOrNull()

    /** 窗口里是否包含这一章。 */
    fun contains(chapterId: String): Boolean =
        current.chapterId == chapterId ||
            previousWindow.any { it.chapterId == chapterId } ||
            nextWindow.any { it.chapterId == chapterId }

    fun neighbor(forward: Boolean): ViewerChapter? = if (forward) next else previous

    /**
     * 按窗口自身的顺序给出下标：上一章 → 当前章 → 下一章。
     *
     * 只作为 [sanitized] 的兜底取值方式（用于单元测试与"没有目录可查"的调用点）。
     * 它是**相对**顺序，修不了"某章根本不该在窗口里"；那种情况要靠调用方传入真实
     * 目录下标——`ReaderViewModel` 有 `chapterList`，所以它用真实下标。
     */
    fun selfIndexOf(chapterId: String): Int {
        previousWindow.forEachIndexed { position, chapter ->
            if (chapter.chapterId == chapterId) return position
        }
        if (chapterId == current.chapterId) return previousWindow.size
        nextWindow.forEachIndexed { position, chapter ->
            if (chapter.chapterId == chapterId) return previousWindow.size + 1 + position
        }
        return -1
    }

    /**
     * 往窗口里**补**一章（追加到对应一侧的尾部）。
     *
     * [forward] 决定放进哪一侧。顺序不必在这里维护——补齐之后统一调用
     * [sortedByChapterOrder] 校正，这样"补"与"排序"各管一件事，不会像追加式实现那样
     * 只在"按距离递增依次补入"时才碰巧正确。
     *
     * 已经是窗口成员时原样返回（幂等）。
     */
    fun withAdded(chapter: ViewerChapter, forward: Boolean): ViewerChapters = when {
        contains(chapter.chapterId) -> this
        forward -> copy(nextWindow = nextWindow + chapter)
        else -> copy(previousWindow = previousWindow + chapter)
    }

    /**
     * 把窗口修回自洽状态。
     *
     * 四件事：[indexOfChapter] 能查到、不含当前章、去重，并且**必须待在自己那一侧**
     * （下标小于当前章的归上一侧，大于的归下一侧），最后按目录顺序排好。
     *
     * 为什么不能只靠"写入时小心"：窗口会被三处异步代码改写（换章、邻章加载完成、设置变化），
     * 任何一处把章放错侧，`buildReaderItems` 就会把**某一页**画成过渡页，或让读者翻到
     * "已是最后一章"而后面其实还有章——真机上两种症状都出现过。与其逐处防御，
     * 不如在构建项列表之前统一修一遍。
     */
    fun sanitized(indexOfChapter: (String) -> Int): ViewerChapters {
        val currentIndex = indexOfChapter(current.chapterId)
        if (currentIndex < 0) return this
        val seen = mutableSetOf(current.chapterId)
        var previous = emptyList<ViewerChapter>()
        var next = emptyList<ViewerChapter>()
        // 先处理离当前章最近的，去重时才留下"更近"的那一份。
        for (chapter in nextWindow + previousWindow.asReversed()) {
            if (!seen.add(chapter.chapterId)) continue
            val index = indexOfChapter(chapter.chapterId)
            if (index < 0) continue
            if (index < currentIndex) previous = previous + chapter else next = next + chapter
        }
        return copy(
            previousWindow = previous.sortedBy { indexOfChapter(it.chapterId) },
            nextWindow = next.sortedBy { indexOfChapter(it.chapterId) },
        )
    }

    /**
     * 把两侧窗口按**章节目录里的位置**排好。
     *
     * [indexOfChapter] 给出章节在作品里的下标。两侧都用**升序**——注意这不是"距离"：
     * 目录里位置靠前的章排在前面，因此
     *
     * - `previousWindow` 的最后一格是位置最靠后的那个，也就是**紧邻的上一章**；
     * - `nextWindow` 的第一格是位置最靠前的那个，也就是**紧邻的下一章**。
     *
     * 两种顺序都由"哪些章在窗口里"唯一决定，所以调用方随便按什么顺序补入都可以。
     */
    fun sortedByChapterOrder(indexOfChapter: (String) -> Int): ViewerChapters = copy(
        previousWindow = previousWindow.sortedBy { indexOfChapter(it.chapterId) },
        nextWindow = nextWindow.sortedBy { indexOfChapter(it.chapterId) },
    )

    /**
     * 用加载好的状态**替换**窗口里同 ID 的那一格；不在窗口里时返回自身。
     *
     * 注意这是替换而不是新增——需要"补一章"时用 [withAdded]。真机上曾经因为把这两件事
     * 混在一起，导致规划出的章节被静默丢弃。
     *
     * @param force 当前章默认**不被**替换：`applyWindow` 每轮都会把规划出的章"补"进窗口，
     *   而当前章永远在规划结果之外，于是替换它等于把读者的页清单换成空占位——
     *   整章的页消失、`items` 只剩过渡项。只有"加载完成、确实拿到了新状态"时才允许
     *   用 `force = true` 覆盖当前章。
     */
    fun withChapter(replacement: ViewerChapter, force: Boolean = false): ViewerChapters = when {
        replacement.chapterId == current.chapterId ->
            if (force || !current.isUsable) copy(current = replacement) else this

        previousWindow.any { it.chapterId == replacement.chapterId } -> copy(
            previousWindow = previousWindow.map {
                if (it.chapterId == replacement.chapterId) replacement else it
            },
        )

        nextWindow.any { it.chapterId == replacement.chapterId } -> copy(
            nextWindow = nextWindow.map {
                if (it.chapterId == replacement.chapterId) replacement else it
            },
        )

        else -> this
    }
}

/**
 * 预载窗口的规划结果：当前章两侧各自应该拿到哪几章。
 *
 * 独立成数据类是为了可测——"预载 9 页而后一章只有 2 页时要再要一章"这条规则用纯函数
 * 钉住，比在加载流程里靠观察验证可靠得多。
 */
internal data class PreloadPlan(
    /** 要加载的上一章下标，由远及近排列。 */
    val previousIndices: List<Int>,
    /** 要加载的下一章下标，由近及远排列。 */
    val nextIndices: List<Int>,
) {
    companion object {
        val EMPTY = PreloadPlan(emptyList(), emptyList())

        /**
         * 按页预算挑选两侧各要加载的章节。
         *
         * ## 算法
         *
         * 逐章消耗预算：每跨过一章先扣**一个过渡页**（章节过渡页在阅读器里占一项，
         * 用户要求把它算作一页），再扣该章的页数。预算耗尽即停。因此"预载 9 页、
         * 后一章只有 2 页"会得到 1 + 2 = 3，还剩 6 页预算，于是继续要再下一章。
         *
         * ## 为什么必须有"边界章"
         *
         * 页数未知的章无法判断够不够预算，只能先去加载它——**不然就没有页数可用**。
         * 如果遇到未知就停，第一部漫画的窗口会永远为空，末页的过渡页只能显示
         * "已是最后一章"，而实际上后面还有几十章（真机上就是这样）。因此：
         *
         * 1. 能算的先用已知页数算到底；
         * 2. 遇到第一个**页数未知**的章时把它作为"边界"放进去，然后停。
         *
         * 边界章加载出页清单后，下一轮规划就有了真实页数，窗口继续向外扩张。
         * 一直无解的只有"数据库里没有页数、且目录还没枚举过"的章——那种情况本来就
         * 必须先枚举一次才知道，没有别的办法。
         *
         * @param chapterCount 全部章节数
         * @param currentIndex 当前章下标
         * @param budget 页预算；0 或负数表示不预载
         * @param maxChapters 单侧最多加载几章（防止每章只有 1 页的长篇里失控）
         * @param pagesOf 查询某章已知页数；未知返回 null
         */
        fun compute(
            chapterCount: Int,
            currentIndex: Int,
            budget: Int,
            maxChapters: Int,
            pagesOf: (Int) -> Int? = { null },
        ): PreloadPlan {
            if (budget <= 0 || maxChapters <= 0 || chapterCount <= 0) return EMPTY
            if (currentIndex !in 0 until chapterCount) return EMPTY
            val forward = walk(chapterCount, currentIndex, budget, maxChapters, true, pagesOf)
            val backward = walk(chapterCount, currentIndex, budget, maxChapters, false, pagesOf)
            // previousIndices 与 previousWindow 同序——"由远及近"，因此反向一侧收集完再翻转。
            return PreloadPlan(previousIndices = backward.asReversed(), nextIndices = forward)
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
                    // 边界章：放进去让调用方去枚举它，然后停下——它的页数出来之前
                    // 无法判断预算够不够继续。
                    collected += index
                    break
                }
                if (remaining <= 1) break
                // 跨过一章的代价：一个过渡页 + 该章页数。
                remaining -= 1 + known
                collected += index
                distance++
            }
            return collected
        }
    }
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
     *
     * [from] 为空表示"已到章节目录的端点"——没有更早的章可去。方向由 [forward] 表达，
     * 因此键里必须带上它：同一个 `from -> to` 对在双向都相邻时（结构上允许）键仍不同。
     */
    data class Transition(
        val from: ViewerChapter?,
        val to: ViewerChapter?,
        val forward: Boolean,
    ) : ReaderItem {
        override val key: String =
            "transition:${if (forward) "f" else "b"}:" +
                "${from?.chapterId.orEmpty()}->${to?.chapterId.orEmpty()}"
        override val chapterId: String get() = from?.chapterId ?: to?.chapterId.orEmpty()

        /** 没有目标章节：这是章节目录的端点。 */
        val isEnd: Boolean get() = to == null
    }
}

/**
 * 组装阅读器的项列表。
 *
 * 顺序（对照 Mihon `PagerViewerAdapter.setChapters`，但窗口可以不止一格）：
 *
 * ```
 * 上一章(远) … 上一章(近) [反向过渡] 当前章 [正向过渡] 下一章(近) … 下一章(远) [正向过渡]
 * ```
 *
 * 三条要点：
 *
 * 1. **每一个跨章边界恰好有一个过渡项，且它唯一属于某一章**：章 i 的**正向**过渡排在它
 *    自己页之后，且只在 i 之后还有章时存在；章 i 的**反向**过渡排在它自己页之前，且只在
 *    i 之前还有章时存在。于是"顺着阅读顺序往后翻"永远只经过正向过渡，不会撞上反向过渡。
 * 2. 窗口**从当前章向两侧展开**，因此落在窗口远端的所有过渡项一律归属"更近的那一章"
 *    （[previousWindow] 里某一格之前的过渡，属于它后面那一格）。这样同一个边界在两个
 *    不同窗口下算出的键完全一致，预载后重建列表才不会丢掉正在使用的过渡项。
 * 3. 窗口里的章可能还没加载完。过渡项**总是**插入，因为它是"下一章还在加载"时唯一的
 *    可见反馈；[ReaderSettings.pauseOnChapterTransition] 只决定到达之后停不停，
 *    不决定插不插。
 *
 * @return 项列表，以及当前章第一项在列表中的下标（用于把"章节内第几页"换算成
 *   分页器的绝对位置）。
 */
internal fun buildReaderItems(chapters: ViewerChapters): ReaderItems {
    val items = ArrayList<ReaderItem>()

    // 反向过渡只在"本章之前确实还有章"时插入。没有更早的章时不需要端点提示：
    // 当前章第一项就是列表第一项，往回翻会被分页器自然挡住。
    if (chapters.previousWindow.isNotEmpty()) {
        items += ReaderItem.Transition(
            from = chapters.previous,
            to = chapters.current,
            forward = false,
        )
    }

    for (chapter in chapters.previousWindow) {
        items += chapter.pages.map { ReaderItem.PageItem(it, chapter) }
        // 紧邻当前章的那一格后面不插过渡：那个边界由上面那个反向过渡承担。
        if (chapter.chapterId == chapters.previous?.chapterId) continue
        items += ReaderItem.Transition(
            from = chapter,
            to = chapters.nextAfter(chapter.chapterId),
            forward = true,
        )
    }

    val currentOffset = items.size
    items += chapters.current.pages.map { ReaderItem.PageItem(it, chapters.current) }

    for ((position, chapter) in chapters.nextWindow.withIndex()) {
        val previous = if (position == 0) chapters.current else chapters.nextWindow[position - 1]
        items += ReaderItem.Transition(from = previous, to = chapter, forward = true)
        items += chapter.pages.map { ReaderItem.PageItem(it, chapter) }
    }

    // 窗口远端之后没有更远的章时补一个"到底了"的过渡（Mihon 同样插入）。
    val farthest = chapters.nextWindow.lastOrNull()
    val beyondFarthest = if (farthest == null) chapters.next else chapters.nextAfter(farthest.chapterId)
    if (beyondFarthest == null) {
        items += ReaderItem.Transition(from = farthest ?: chapters.current, to = null, forward = true)
    }

    return ReaderItems(
        items = items,
        currentChapterOffset = currentOffset.takeIf { chapters.current.pages.isNotEmpty() },
    )
}

/** 窗口里排在 [chapterId] 之后的那一章；没有则 null。 */
private fun ViewerChapters.nextAfter(chapterId: String): ViewerChapter? {
    if (chapterId == current.chapterId) return nextWindow.firstOrNull()
    val position = nextWindow.indexOfFirst { it.chapterId == chapterId }
    if (position < 0) return null
    return nextWindow.getOrNull(position + 1)
}

/** [buildReaderItems] 的结果。 */
internal data class ReaderItems(
    val items: List<ReaderItem>,
    /** 当前章第一项在 [items] 中的下标；当前章没有可显示的页时为 null。 */
    val currentChapterOffset: Int?,
)

/**
 * 提升章节后应当落在哪一项。
 *
 * 单独抽出来是为了可测，也因为这里连续出过两次错：
 *
 * 1. 最初用"按项身份找回位置"，而那个身份**正是过渡项自身**，于是提升之后落回过期位置，
 *    读者被送回上一章、过渡页也看不到；
 * 2. 改成"过渡项的下一个位置"后又发现，预载窗口变化会让过渡项本身从新列表里消失——
 *    例如读者从第 2 章的过渡项进第 3 章时，新窗口里不再包含"第 2 章的正向过渡"。
 *
 * 因此现在按**优先级**依次尝试三种解法，而不是赌其中一种：
 *
 * 1. [preferredPageId]：目标页身份。最可靠——页身份与窗口无关；
 * 2. [transitionKey]：过渡项在新列表里仍在时取其后继，也就是目标章第一页；
 * 3. 目标章第一项的位置（[fallbackOffset]）。
 */
internal fun resolveLanding(
    newItems: List<ReaderItem>,
    preferredPageId: String?,
    transitionKey: String?,
    fallbackOffset: Int,
): Int {
    if (newItems.isEmpty()) return 0
    if (preferredPageId != null) {
        val byPage = newItems.indexOfFirst { it.key == preferredPageId }
        if (byPage >= 0) return byPage
    }
    if (transitionKey != null) {
        val transitionIndex = newItems.indexOfTransition(transitionKey)
        if (transitionIndex >= 0) return (transitionIndex + 1).coerceIn(newItems.indices)
    }
    return fallbackOffset.coerceIn(newItems.indices)
}

/**
 * 重建项列表后按项身份把读者放回原来的位置。
 *
 * 预载完成会把上一章的页插到列表前面，于是所有绝对下标整体后移；不重新定位读者就会
 * 突然跳到另一页。找不到身份时退回夹取后的原下标。
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

/** 过渡项在 [items] 里的下标；找不到返回 -1。 */
internal fun List<ReaderItem>.indexOfTransition(key: String): Int =
    indexOfFirst { it is ReaderItem.Transition && it.key == key }

/** 某一页在 [items] 里的下标；找不到返回 -1。 */
internal fun List<ReaderItem>.indexOfPage(pageId: String): Int =
    indexOfFirst { it is ReaderItem.PageItem && it.page.pageId == pageId }

/** 那一章第一页在 [items] 里的下标；该章不在列表里时返回 -1。 */
internal fun List<ReaderItem>.indexOfFirstPageOfChapter(chapterId: String): Int =
    indexOfFirst { it is ReaderItem.PageItem && it.chapter.chapterId == chapterId }
