package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ReaderSettings
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

    /** 用户确认的设置语义：N 是阅读方向预算，反方向预算为 N/2。 */
    @Test
    fun `设置九页时往后九页往前四页`() {
        assertEquals(PrefetchBudget(forward = 9, backward = 4), prefetchBudget(9))
        assertEquals(PrefetchBudget(forward = 2, backward = 1), prefetchBudget(2))
    }

    @Test
    fun `前后预算彼此独立`() {
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

    @Test
    fun `隐藏过渡页后它不再消耗预载预算`() {
        val shown = PreloadPlan.compute(
            chapterCount = 20,
            currentIndex = 5,
            budget = 5,
            maxChapters = 5,
            backBudget = 0,
            transitionCost = 1,
            pagesOf = { 2 },
        )
        val hidden = PreloadPlan.compute(
            chapterCount = 20,
            currentIndex = 5,
            budget = 5,
            maxChapters = 5,
            backBudget = 0,
            transitionCost = 0,
            pagesOf = { 2 },
        )

        assertEquals(listOf(6, 7), shown.nextIndices)
        assertEquals(listOf(6, 7, 8), hidden.nextIndices)
    }

    @Test
    fun `单页章节按页预算跨过三章而不会被旧上限截断`() {
        val plan = PreloadPlan.compute(
            chapterCount = 100,
            currentIndex = 10,
            budget = 9,
            maxChapters = ReaderSettings.PRELOAD_PAGES_MAX,
            backBudget = 0,
            transitionCost = 1,
            pagesOf = { 1 },
        )

        // 每章代价 1 张图 + 1 张过渡页；9 格会触及第 5 个短章。
        assertEquals(listOf(11, 12, 13, 14, 15), plan.nextIndices)
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
    fun `前后都给一格时各取紧邻章节`() {
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

    /** 数千章漫画也只能把当前规划覆盖的页清单放进展示窗口，不能随阅读进度无界增长。 */
    @Test
    fun `窗口只保留规划覆盖的已加载章`() {
        val chapters = (0..3000).map { record("c$it") }
        val existing = mapOf(
            "c0" to chapter("c0", 1),
            "c1" to chapter("c1", 1),
            "c2" to chapter("c2", 1),
            "c3000" to chapter("c3000", 1),
        )

        val (window, _) = buildWindow(
            chapterList = chapters,
            currentChapterId = "c1",
            plan = PreloadPlan(previousIndices = listOf(0), nextIndices = listOf(2)),
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
