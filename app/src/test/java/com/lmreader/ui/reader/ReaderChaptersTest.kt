package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阅读器项列表与窗口的单元测试。
 *
 * 这些用例保护的是**静默出错**的行为：列表组装错了不会崩溃，只会让读者跳到别的页、
 * 卡在某一页翻不动、或者误以为作品已经读完。三者都极难靠手工回归发现。
 *
 * 分成四组：项列表组装、过渡页开关、预载预算、窗口与定位。
 */
class ReaderChaptersTest {

    private val source = object : PageSource {
        override suspend fun pages(): List<ReaderPage> = emptyList()
        override suspend fun open(page: ReaderPage): InputStream = error("测试不使用")
        override suspend fun probe(page: ReaderPage): PageGeometry? = null
    }

    private fun chapter(id: String, pageCount: Int): ViewerChapter = ViewerChapter(
        chapter = ChapterRecord(
            chapterId = id,
            mangaId = "manga",
            documentId = "/lib/$id",
            kind = ChapterKind.IMAGE_DIRECTORY,
            title = "第 $id 章",
            sortKey = id,
            pageCount = pageCount,
            coverDocumentId = null,
            contentRevision = 1,
            discoveredAt = 1,
        ),
        pages = (0 until pageCount).map { ordinal ->
            ReaderPage(
                pageId = "$id-p$ordinal",
                ordinal = ordinal,
                displayName = "$ordinal.jpg",
                documentId = "/lib/$id/$ordinal.jpg",
            )
        },
        source = source,
    )

    private fun record(id: String, pageCount: Int? = null): ChapterRecord = ChapterRecord(
        chapterId = id,
        mangaId = "manga",
        documentId = "/lib/$id",
        kind = ChapterKind.IMAGE_DIRECTORY,
        title = "第 $id 章",
        sortKey = id,
        pageCount = pageCount,
        coverDocumentId = null,
        contentRevision = 1,
        discoveredAt = 1,
    )

    /** 窗口便捷构造：只收录传进来的章，[current] 指定当前章。 */
    private fun window(current: String, vararg chapters: ViewerChapter): ViewerChapters {
        val list = chapters.toList()
        return ViewerChapters(window = list, currentIndex = list.indexOfFirst { it.chapterId == current })
    }

    /**
     * **窗口前端放掉一章，会让已有项的下标整体平移——这是"翻页后跳到别的页"的根源。**
     *
     * 参数完全按真机复现：**每章 3 页**、「预载页数」默认 9（往后 4 / 往前 5 格预算）。
     * 当前章从第 3 章推进到第 4 章时，往前预算只够覆盖 1 章多一点，于是窗口前端把第 1 章
     * 放掉、后端收进第 6 章：
     *
     * ```
     * current=c3 → [c1 c2 c3 c4 c5]      c4 第 0 页在下标 12
     * current=c4 → [c2 c3 c4 c5 c6]      c4 第 0 页在下标  8
     * ```
     *
     * 前端放掉的是「3 页 + 1 个过渡页 = **4 项**」，所以平移量正好是 4。
     * 分页器若还停在旧数字上就会**正好偏 4 格**——真机反馈的两种症状都是这个 4：
     *
     * - 往前偏 4：跨过过渡页之后不是进入下一章第 1 页，而是又一张过渡页 / 第 5 页附近；
     * - 往回偏 4：从下一章第 1 页"跳回上一章第 1 页"（−4 项正好是上一章首页）。
     *
     * 这条用例**不是在断言这种行为是对的**，而是把这个平移量钉成可检查的事实：
     * 位置一旦用"会平移的下标"表达，任何重排都可能让读者偏掉整整一章。
     */
    @Test
    fun `窗口前端放掉一章会让项下标整体平移 4 格`() {
        val records = (1..6).map { record("c$it", pageCount = 3) }
        val existing = (1..6).associate { "c$it" to chapter("c$it", 3) }

        // 与 planFor 对同样输入算出的计划一致（每章 3 页已知、预载 9 → 后 4 / 前 5）。
        fun planFor(currentIndex: Int) = PreloadPlan.compute(
            chapterCount = records.size,
            currentIndex = currentIndex,
            budget = 4,
            maxChapters = 3,
            backBudget = 5,
            pagesOf = { 3 },
        )

        fun itemsFor(currentIndex: Int): List<ReaderItem> {
            val plan = planFor(currentIndex)
            // 边界章规则：规划覆盖不到的那一章也要收进来。
            val next = plan.nextIndices.toMutableList()
            val previous = plan.previousIndices.toMutableList()
            val nextFrontier = (next.lastOrNull() ?: currentIndex) + 1
            val previousFrontier = (previous.firstOrNull() ?: currentIndex) - 1
            if (nextFrontier in records.indices && next.size < 3) next += nextFrontier
            if (previousFrontier in records.indices && previous.size < 3) previous += previousFrontier
            val (window, current) = buildWindow(
                chapterList = records,
                currentChapterId = records[currentIndex].chapterId,
                plan = PreloadPlan(previousIndices = previous, nextIndices = next),
                existing = existing,
            )
            return ViewerChapters(window = window, currentIndex = current)
                .items(showTransitions = true, isFinalChapter = { false })
        }

        val before = itemsFor(currentIndex = 2) // 当前 = c3
        val after = itemsFor(currentIndex = 3) // 当前 = c4

        assertEquals(5, before.count { it is ReaderItem.PageItem } / 3) // 窗口 5 章
        assertEquals(5, after.count { it is ReaderItem.PageItem } / 3)

        fun indexOf(items: List<ReaderItem>, key: String) = items.indexOfFirst { it.key == key }

        // 锚点一：c4 的**第一页**。12 → 8，偏 4。
        val firstPageKey = before.filterIsInstance<ReaderItem.PageItem>()
            .first { it.chapter.chapterId == "c4" }.key
        assertEquals(12, indexOf(before, firstPageKey))
        assertEquals(8, indexOf(after, firstPageKey))

        // 锚点二：c3→c4 那个**过渡项**（它的 chapterId 归后一章 c4，所以别用 chapterId 找它）。
        // 11 → 7，同样偏 4。分页器停在旧的 11 上时，新列表下标 11 处已经是**另一个过渡项**
        // （c4→c5）——这正是"同一张过渡页出现了两次"的由来：两张过渡页长得几乎一样。
        val transitionKey = before.first { it is ReaderItem.Transition && it.chapterId == "c4" }.key
        assertEquals(11, indexOf(before, transitionKey))
        assertEquals(7, indexOf(after, transitionKey))

        assertEquals(
            4,
            indexOf(before, firstPageKey) - indexOf(after, firstPageKey),
            "前端放掉「3 页 + 1 过渡页」= 4 项，下标就整体减 4；" +
                "分页器若停在旧下标上，读者会正好偏掉一格过渡页的量",
        )
    }

    /**
     * 落到**过渡项**不算换章。
     *
     * 过渡项的 `chapterId` 取的是后一章（为了 key 稳定），但"站在两章之间那一张上"不等于
     * "已经进入下一章"。少了这条判定，当前章会在边界上被反复翻转，而窗口内容依赖当前章，
     * 于是与分页器的一格抖动合成正反馈环——实测每秒重建 80 多个引擎视图、每次都整图解码，
     * 把 native heap 顶到 300MB 以上（详见 `chapterEnteredBy` 的说明）。
     */
    @Test
    fun `落到过渡项不算换章`() {
        val chapters = window("c1", chapter("c1", 3), chapter("c2", 3))
        val items = chapters.items(showTransitions = true, isFinalChapter = { false })

        val transition = items.first { it is ReaderItem.Transition }
        // 它的 chapterId 确实是后一章——所以只看 `chapterId` 会误判成"换章了"。
        assertEquals("c2", transition.chapterId)
        assertNull(chapterEnteredBy(transition))
    }

    /** 落到**页**才算进入它所属的那一章。 */
    @Test
    fun `落到页才算进入该章`() {
        val chapters = window("c1", chapter("c1", 3), chapter("c2", 3))
        val items = chapters.items(showTransitions = true, isFinalChapter = { false })

        val pageOfSecond = items.filterIsInstance<ReaderItem.PageItem>()
            .first { it.chapter.chapterId == "c2" }
        assertEquals("c2", chapterEnteredBy(pageOfSecond))
    }

    /** 默认把窗口里最后那一章当作整部的末章（"到底了"标记因此会出现）。 */
    private fun itemsOf(
        window: ViewerChapters,
        transitions: Boolean = true,
        finalChapterId: String? = window.window.lastOrNull()?.chapterId,
    ): List<String> =
        window.items(showTransitions = transitions) { id -> id == finalChapterId }
            .map { item ->
                when (item) {
                    is ReaderItem.PageItem -> "${item.chapterId}#${item.page.ordinal}"
                    is ReaderItem.Transition -> "T:${item.from?.chapterId}->${item.to?.chapterId ?: "END"}"
                }
            }

    // ------------------------------------------------------------ 项列表组装

    /**
     * 核心形状：一条直线，章与章之间恰好一个过渡项。
     *
     * 这就是"过渡页只是夹在中间的一张图"在代码里的样子——没有方向、没有特殊分支。
     */
    @Test
    fun `项列表是一条直线章与章之间恰好一个过渡页`() {
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 1)

        val items = itemsOf(window("c1", c1, c2))

        assertEquals(
            listOf("c1#0", "c1#1", "T:c1->c2", "c2#0", "T:c2->END"),
            items,
        )
    }

    @Test
    fun `单章作品首尾都不插多余的过渡页`() {
        val c1 = chapter("c1", 3)

        val items = itemsOf(window("c1", c1))

        // 只有一个"到底了"标记；前面没有"已是第一章"这种东西。
        assertEquals(listOf("c1#0", "c1#1", "c1#2", "T:c1->END"), items)
    }

    /**
     * 过渡项**没有方向**：同一个边界只有一个键，往前翻与往后翻遇到的是同一个项。
     *
     * 这是"过渡页就是夹在中间的一张图"的字面要求。上一版把方向编进键里，于是同一个
     * 边界在"前进"与"后退"时是两个不同的项，窗口一变就可能找不到。
     */
    @Test
    fun `过渡项的键只由前后两章决定不含方向`() {
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)

        val boundary = window("c1", c1, c2).items(showTransitions = true) { false }
            .filterIsInstance<ReaderItem.Transition>()
            .single { it.to?.chapterId == "c2" }

        assertEquals("transition:c1->c2", boundary.key)
        // 键里不应出现方向标记。
        assertFalse(boundary.key.contains(":f:"))
        assertFalse(boundary.key.contains(":b:"))
    }

    @Test
    fun `同一个边界的过渡键不随窗口变化`() {
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)
        val c3 = chapter("c3", 1)

        val narrow = window("c1", c1, c2).items(showTransitions = true) { false }
            .filterIsInstance<ReaderItem.Transition>().single { it.to?.chapterId == "c2" }
        val wide = window("c2", c1, c2, c3).items(showTransitions = true) { false }
            .filterIsInstance<ReaderItem.Transition>().single { it.to?.chapterId == "c2" }

        assertEquals(narrow.key, wide.key)
    }

    /**
     * 还没加载出页清单的章**完全不出现**。
     *
     * 否则会插出一个悬空的过渡页（甚至两个过渡页挨在一起）——读者会看到一个说不清
     * "通向哪里"的过渡页，而目标章其实还没准备好。
     */
    @Test
    fun `没有页清单的章既不贡献页也不贡献过渡页`() {
        val c1 = chapter("c1", 2)
        val unloaded = ViewerChapter(
            chapter = record("c2"),
            pages = emptyList(),
            source = source,
            state = ViewerChapter.LoadState.LOADING,
        )

        // 末章是 c2（未加载），因此不会有"到底了"标记——那属于整部的末章，
        // 而作品后面还有内容。
        val items = itemsOf(window("c1", c1, unloaded), finalChapterId = null)

        // c2 尚未加载：列表里只有 c1 的页，既没有 c1->c2 的过渡页，也没有终点标记。
        assertEquals(listOf("c1#0", "c1#1"), items)
    }

    @Test
    fun `加载完成后页与过渡页一起插入`() {
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 2)

        val items = itemsOf(window("c1", c1, c2))

        assertEquals(listOf("c1#0", "T:c1->c2", "c2#0", "c2#1", "T:c2->END"), items)
    }

    // ------------------------------------------------------------ 过渡页开关

    @Test
    fun `关掉过渡页时两章直接相接`() {
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 1)

        val items = itemsOf(window("c1", c1, c2), transitions = false)

        // 一次翻页就从 c1 末页到 c2 首页；连"到底了"标记也不插。
        assertEquals(listOf("c1#0", "c1#1", "c2#0"), items)
    }

    @Test
    fun `关掉过渡页不影响页本身`() {
        val c1 = chapter("c1", 3)
        val c2 = chapter("c2", 2)

        val withTransitions = itemsOf(window("c1", c1, c2), transitions = true)
        val without = itemsOf(window("c1", c1, c2), transitions = false)

        assertEquals(
            withTransitions.filterNot { it.startsWith("T:") },
            without,
        )
    }

    // ------------------------------------------------------------ 前后双向预载

    /**
     * 预算拆成"往后一半、往前多于一半"。余数给往前，因为往前读的第一步常常要先跨
     * 一个过渡页（它占一格）。
     *
     * 每章 1 页时跨一章付 2 格，因此 4 格恰好拿到 2 章；往前 5 格在拿到 2 章后还剩
     * 1 格，于是再收一章（"只够一格也要收"——见 `walk` 里的说明）。
     */
    @Test
    fun `预算拆成往后一半往前多于一半`() {
        val plan = PreloadPlan.compute(
            chapterCount = 20,
            currentIndex = 10,
            budget = 4,
            maxChapters = 5,
            backBudget = 5,
            pagesOf = { 1 },
        )

        assertEquals(listOf(11, 12), plan.nextIndices)
        assertEquals(listOf(9, 8, 7), plan.previousIndices)
    }

    /**
     * 用户点名的边界：**从某一章第一页打开**时，往前要能拿到上一章的末尾几页。
     *
     * 从第 2 章第 1 页打开时，往前的第 1 格是**过渡页**，再往前才是上一章的页。
     * 预算 5 → 1（过渡页）+ 4 页，覆盖"至少看到上一章最后 3 页"。
     */
    @Test
    fun `从某章第一页打开时往前会跨过过渡页拿到上一章`() {
        // 第 1 章 76 页、第 2 章 54 页；当前在第 2 章（下标 1）。
        val pages = mapOf(0 to 76, 1 to 54)
        val plan = PreloadPlan.compute(
            chapterCount = 2,
            currentIndex = 1,
            budget = 4,
            maxChapters = 5,
            backBudget = 5,
            pagesOf = { pages[it] },
        )

        // 往前走的第一步要付 1（过渡页）+ 76 页 = 77 > 5，但预算仍 > 0，
        // 因此上一章被收进窗口——**必须**是这样，否则从第 2 章第一页往回翻会撞到墙。
        assertEquals(listOf(0), plan.previousIndices)
        assertEquals(emptyList(), plan.nextIndices)
    }

    /**
     * 从**第 1 页**打开时往前没有内容，往前那份预算自然落空，**不挪给往后**。
     *
     * 不挪的理由：否则"预载 9"在首页表现为 9 页、在第 37 页表现为 4 页，行为不可预测。
     */
    @Test
    fun `首章首页往前无内容时往后预算不变`() {
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 0,
            budget = 4,
            maxChapters = 5,
            backBudget = 5,
            pagesOf = { 1 },
        )

        assertEquals(emptyList(), plan.previousIndices)
        // 往后仍是 4 格（每章 1 页 → 2 章），没有被往前那份预算放大。
        assertEquals(listOf(1, 2), plan.nextIndices)
    }

    @Test
    fun `末章时往后无内容往前仍按自己的预算取`() {
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 9,
            budget = 4,
            maxChapters = 5,
            backBudget = 5,
            pagesOf = { 1 },
        )

        assertEquals(emptyList(), plan.nextIndices)
        // 往前 5 格：每章 1 页付 2，拿两章后剩 1 格 → 再收一章。
        assertEquals(listOf(8, 7, 6), plan.previousIndices)
    }

    @Test
    fun `下限预算 2 时前后各一`() {
        // 预载下限 2 → 往后 1、往前 1：保证"往后翻一页"永远不用现读。
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 5,
            budget = 1,
            maxChapters = 5,
            backBudget = 1,
            pagesOf = { 1 },
        )

        assertEquals(listOf(4), plan.previousIndices)
        assertEquals(listOf(6), plan.nextIndices)
    }

    // ------------------------------------------------------------ 预载预算

    @Test
    fun `预算为零时不预载任何相邻章`() {
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 5,
            budget = 0,
            maxChapters = 3,
        )

        assertEquals(PreloadPlan.EMPTY, plan)
    }

    /**
     * 页数未知时也必须把紧邻的一章放进来当边界。
     *
     * 没有这条，第一部打开的漫画（数据库没有页数、目录也没枚举过）会把窗口算成空的，
     * 末页只能显示"已是最后一章"，而后面其实还有几十章。
     */
    @Test
    fun `页数未知时仍然预载紧邻的一章作为边界`() {
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 5,
            budget = 9,
            maxChapters = 3,
            pagesOf = { null },
        )

        assertEquals(listOf(4), plan.previousIndices)
        assertEquals(listOf(6), plan.nextIndices)
    }

    @Test
    fun `预算够一章时只预载紧邻的一章`() {
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 5,
            budget = 9,
            maxChapters = 3,
            pagesOf = { 10 },
        )

        // 跨一章要付 1 个过渡页 + 10 页 = 11 > 9，因此只拿到紧邻的一章。
        assertEquals(listOf(4), plan.previousIndices)
        assertEquals(listOf(6), plan.nextIndices)
    }

    /** 用户要求的那条规则：**过渡页也算一页**，因此后一章比预算短时要再要一章。 */
    @Test
    fun `后一章比预算短时继续往后一章`() {
        // 4 页一章：跨一章付 1 + 4 = 5，预算 9 因此能走两章（5 + 5 = 10 > 9 时停）。
        val plan = PreloadPlan.compute(
            chapterCount = 10,
            currentIndex = 5,
            budget = 9,
            maxChapters = 5,
            pagesOf = { 4 },
        )

        assertEquals(listOf(6, 7), plan.nextIndices)
        assertEquals(listOf(4, 3), plan.previousIndices)
    }

    @Test
    fun `单侧章数上限把长篇里的最坏情况钉住`() {
        // 每章 1 页：跨一章只付 2，预算足够拉进整部作品；上限必须生效。
        val plan = PreloadPlan.compute(
            chapterCount = 500,
            currentIndex = 250,
            budget = 60,
            maxChapters = 3,
            pagesOf = { 1 },
        )

        assertEquals(3, plan.nextIndices.size)
        assertEquals(3, plan.previousIndices.size)
    }

    @Test
    fun `列表端点处不会越界`() {
        val first = PreloadPlan.compute(3, 0, 9, 3) { 2 }
        assertEquals(emptyList(), first.previousIndices)
        assertEquals(listOf(1, 2), first.nextIndices)

        val last = PreloadPlan.compute(3, 2, 9, 3) { 2 }
        assertEquals(emptyList(), last.nextIndices)
        assertEquals(listOf(1, 0), last.previousIndices)
    }

    // ------------------------------------------------------------ 窗口与定位

    @Test
    fun `窗口按规划收章并保持目录顺序`() {
        val chapters = listOf(record("c0"), record("c1"), record("c2"), record("c3"), record("c4"))
        val existing = mapOf(
            "c1" to chapter("c1", 1),
            "c2" to chapter("c2", 1),
            "c3" to chapter("c3", 1),
        )

        val (window, currentIndex) = buildWindow(
            chapterList = chapters,
            currentChapterId = "c2",
            plan = PreloadPlan(previousIndices = listOf(1), nextIndices = listOf(3)),
            existing = existing,
        )

        assertEquals(listOf("c1", "c2", "c3"), window.map { it.chapterId })
        assertEquals(1, currentIndex)
    }

    /**
     * 关键不变量：**预载范围内的已加载章必须留在窗口里**，哪怕规划没有点名它。
     *
     * 上一版在换章时把窗口从零重建，丢掉了已经加载好的邻章页清单，于是明明已就绪的
     * 下一章显示成"正在载入"。真机上表现为每跨一章都要等一次加载。
     *
     * 这里 c0 不在规划里（规划只要求 c2），但它落在当前章的前一格内，仍必须保留。
     */
    @Test
    fun `补窗口时不会丢掉范围内的已加载章`() {
        val chapters = (0..5).map { record("c$it") }
        val existing = mapOf(
            "c0" to chapter("c0", 1),
            "c1" to chapter("c1", 1),
            "c2" to chapter("c2", 1),
            "c5" to chapter("c5", 1),
        )

        // 规划只点名 c2，但 c0 与 c1 也在范围内且已加载 → 必须保留；
        // c5 在范围之外 → 从窗口里放掉（页清单仍留在 `loaded`，往回翻不会重读）。
        val (window, _) = buildWindow(
            chapterList = chapters,
            currentChapterId = "c1",
            plan = PreloadPlan(previousIndices = emptyList(), nextIndices = listOf(2)),
            existing = existing,
        )

        assertEquals(listOf("c0", "c1", "c2"), window.map { it.chapterId })
    }

    /**
     * 窗口必须有界：范围之外的已加载章不再留在窗口里。
     *
     * 否则整部漫画读过多少章、项列表里就有多少章，"预载窗口"会退化成"全部已读章节"。
     * 这既让 `nearWindowEdge` 的边界判据（它假设窗口约等于预载范围那么大）永不触发，
     * 也让上千章的漫画把整个目录的页清单堆在内存里。放掉只是不显示，不是丢数据。
     */
    @Test
    fun `范围之外的已加载章不留在窗口里`() {
        val chapters = (0..9).map { record("c$it") }
        val existing = (0..9).associate { "c$it" to chapter("c$it", 1) }

        val (window, currentIndex) = buildWindow(
            chapterList = chapters,
            currentChapterId = "c5",
            plan = PreloadPlan(previousIndices = listOf(4), nextIndices = listOf(6)),
            existing = existing,
        )

        assertEquals(listOf("c4", "c5", "c6"), window.map { it.chapterId })
        assertEquals(1, currentIndex)
    }

    /**
     * 规划为空时窗口也至少含当前章的前后各一格。
     *
     * 过渡项只有"后一章也在窗口里"才会生成，因此范围两侧各留一格是**跨章翻页的必要条件**：
     * 少了它，读者翻到本章末页会直接卡住，既过不去也没有"到底了"的提示。
     */
    @Test
    fun `规划为空时窗口仍保留前后各一格`() {
        val chapters = (0..3).map { record("c$it") }
        val existing = chapters.associate { it.chapterId to chapter(it.chapterId, 1) }

        val (window, _) = buildWindow(
            chapterList = chapters,
            currentChapterId = "c1",
            plan = PreloadPlan.EMPTY,
            existing = existing,
        )

        assertEquals(listOf("c0", "c1", "c2"), window.map { it.chapterId })
    }

    @Test
    fun `窗口里没有当前章时返回空窗口而不是崩溃`() {
        val (window, index) = buildWindow(
            chapterList = listOf(record("c0")),
            currentChapterId = "不存在",
            plan = PreloadPlan.EMPTY,
            existing = emptyMap(),
        )

        assertTrue(window.isEmpty())
        assertEquals(0, index)
    }

    /**
     * 窗口扩张会把新章插到列表前面或后面，于是读者那一项的下标整体平移。
     * [reanchorIndex] 是"补窗口时画面不跳"的**唯一**保证。
     */
    @Test
    fun `窗口扩张后按项身份找回读者的位置`() {
        val c0 = chapter("c0", 1)
        val c1 = chapter("c1", 3)

        val before = window("c1", c1).items(showTransitions = true) { false }
        val after = window("c1", c0, c1).items(showTransitions = true) { false }

        // 前面插入了 c0 的一页与一个过渡页，因此原下标 1 会移到 3。
        val anchor = before[1]
        val moved = reanchorIndex(before, 1, after)

        assertEquals(anchor.key, after[moved].key)
        assertEquals(3, moved)
    }

    @Test
    fun `身份丢失时退回夹取后的原下标`() {
        val c1 = chapter("c1", 2)
        val items = window("c1", c1).items(showTransitions = true) { false }

        assertEquals(1, reanchorIndex(items, 1, items))
        // 越界的原下标被夹回范围内，而不是抛异常——越界会让分页器直接崩。
        assertEquals(items.lastIndex, reanchorIndex(items, 999, items))
        assertEquals(0, reanchorIndex(items, 1, emptyList()))
    }

    @Test
    fun `按章查第一页与查页都能定位`() {
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 1)
        val items = window("c1", c1, c2).items(showTransitions = true) { false }

        // 0=c1#0、1=c1#1、2=c1->c2 过渡页、3=c2 第一页
        assertEquals(3, items.indexOfFirstPageOfChapter("c2"))
        assertEquals(0, items.indexOfPage("c1-p0"))
        assertEquals(-1, items.indexOfPage("不存在"))
    }

    // ------------------------------------------------------------ 节流

    @Test
    fun `当前项属于哪一章可以直接读出`() {
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)
        val items = window("c1", c1, c2).items(showTransitions = true) { false }

        // 0=c1 的页、1=过渡页（归属 c2）、2=c2 的页
        assertEquals("c1", items.chapterIdAt(0))
        assertEquals("c2", items.chapterIdAt(1))
        assertEquals("c2", items.chapterIdAt(2))
        assertNull(items.chapterIdAt(99))
    }

    @Test
    fun `过渡页归属后一章`() {
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)

        val transition = window("c1", c1, c2).items(showTransitions = true) { false }
            .filterIsInstance<ReaderItem.Transition>().first()

        // 归属后一章：这样它在任何窗口下键都一样（归属前一章时，旧窗口里后一章还没
        // 加载出来，过渡项根本不存在，键就无从比较）。
        assertEquals("c2", transition.chapterId)
        assertEquals("c1", transition.from?.chapterId)
        assertEquals("c2", transition.to?.chapterId)
        assertFalse(transition.isEnd)
    }
}
