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
            pagesOf: (Int) -> Int? = { null },
        ): PreloadPlan {
            if (maxChapters <= 0 || chapterCount <= 0) return EMPTY
            if (currentIndex !in 0 until chapterCount) return EMPTY
            return PreloadPlan(
                previousIndices = walk(chapterCount, currentIndex, backBudget, maxChapters, false, pagesOf),
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
                    //
                    // 页数进了数据库之后这一支越来越少见（「更新章节」与扫描都顺手数了），
                    // 只剩"从没同步过、也没打开过的章"。那时这一次枚举是唯一的信息来源。
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
 * 组装显示窗口：**已缓存的章，按目录顺序**，外加当前章在其中的下标。
 *
 * ## 为什么不再按"预载页数预算"切一段
 *
 * 预算驱动窗口有一个隐蔽的抖动：预算用的是**已知页数**，而一章的页数要等它加载完才
 * 知道。于是"边界章刚量到页数"会让窗口当场改变大小，跨一章就会**白重排两次**
 * （实测 `items 19->15` 与 `15->19` 相隔 4ms；位置没错，但每次跨章多一次全列表重建）。
 *
 * 现在窗口就是"已缓存的那几章"：它只随**加载完成**（追加）与**淘汰**
 * （见 `ReaderViewModel.evictLoadedChapters`）变化，不再依赖页数。副产品正好是我们要的——
 * 正常前进时窗口只增长，项的下标不平移，位置连"按身份重新解算"都不必发生。
 *
 * 预算从此只决定**加载哪几章**（`chaptersToLoad`）与**预取哪些字节**（`warmPrefetch`），
 * 也就是"加载"而不是"显示"——那才是它本来的职责。
 *
 * 窗口必须包含当前章前后各至少一格，否则过渡项不会生成、读者翻到章末会卡住；
 * 这一点由"加载规划总是带上当前章 ±1"保证（`planFor` 的边界章规则），
 * 而**淘汰**又永不动当前章及其相邻章，因此这条不变量不会被破坏。
 *
 * @param existing 已经枚举出页清单的章；窗口就是它们的子集
 * @return 新的窗口（按目录顺序）与当前章在其中的下标
 */
internal fun buildWindow(
    chapterList: List<ChapterRecord>,
    currentChapterId: String,
    existing: Map<String, ViewerChapter>,
): Pair<List<ViewerChapter>, Int> {
    val currentIndex = chapterList.indexOfFirst { it.chapterId == currentChapterId }
    if (currentIndex < 0) return emptyList<ViewerChapter>() to 0
    val window = chapterList.mapNotNull { record -> existing[record.chapterId] }
    val newCurrentIndex = window.indexOfFirst { it.chapterId == currentChapterId }
    return window to newCurrentIndex.coerceAtLeast(0)
}

/**
 * 项列表重建后按项身份把读者放回原来的位置。
 *
 * ⚠️ **这已经不是主路径了**：正常情况下请用 [indexOfKey]（身份定位）。
 * 本函数只在"身份在新列表里找不到"时兜底——那意味着窗口策略被破坏了
 * （窗口必须永远包含当前章及其相邻章），因此调用方**必须记日志**，不要让它静默发生。
 *
 * 兜底为什么危险：它保留**旧下标**（`coerceIn`），而窗口前滚让下标平移了一整章，
 * 于是它会静默把读者放到另一页——不报错、只是位置错了，是最难查的一类失败。
 *
 * 窗口扩张会把新章插到列表前面或后面，于是绝对下标平移；不重新定位读者就会跳到别的页。
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

/**
 * 按**项身份**在列表里定位；找不到返回 -1。
 *
 * 这是阅读器唯一正确的定位方式。窗口前滚会从列表前端放掉一整章，于是所有项的下标
 * 整体平移（平移量 = 那一章的页数 + 1 个过渡页），而身份不受影响。
 * 按下标"重锚"（[reanchorIndex]）只在身份确实消失时才允许作为兜底。
 */
internal fun List<ReaderItem>.indexOfKey(key: String): Int {
    if (key.isEmpty()) return -1
    return indexOfFirst { it.key == key }
}

/**
 * 落页到 [item] 时**真正进入了**哪一章；`null` = 没有换章。
 *
 * ## 为什么过渡项不算换章（这条判定的理由值得单独写下来）
 *
 * [ReaderItem.Transition] 的 `chapterId` 按设计取**后一章**（这样它在任何窗口下都算出同一个
 * `key`）。但"落到过渡页"并不等于"已经进入下一章"——它只是夹在两章之间的那一张。
 *
 * 用过渡项当换章判据会引发一个每帧一轮的正反馈环：落页回报 → 当前章被翻转 →
 * 窗口按当前章重算 → 项列表长度变化 → 分页器（右到左的 `reverseLayout`）的
 * "滚动偏移 ↔ 下标"映射抖一格 → 下一次落页回报指到相邻的另一章 → 再翻转……
 * 实测每秒重建 80 多个引擎视图、每次都整图解码，native heap 被顶到 300MB 以上。
 *
 * 不换章也不会把跨章读卡住：下一章本来就被"边界章"规则收进窗口并加载好了
 * （见 `ReaderViewModel.chaptersToLoad`），读者真正落到下一章第一页时才会换章。
 */
internal fun chapterEnteredBy(item: ReaderItem): String? =
    if (item is ReaderItem.PageItem) item.chapterId else null
