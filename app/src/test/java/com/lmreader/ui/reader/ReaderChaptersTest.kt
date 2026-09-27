package com.lmreader.ui.reader

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多章节编排的单元测试。
 *
 * 这些用例保护的是**静默出错**的行为：项列表组装错了不会崩溃，只会让读者在章末撞墙、
 * 预载完成后突然跳页、或者换章后回到第一页。三者都极难靠手工回归发现，因此用测试把
 * 结构钉住。
 */
class ReaderChaptersTest {

    private val source = object : PageSource {
        override suspend fun pages(): List<ReaderPage> = emptyList()
        override suspend fun open(page: ReaderPage): InputStream = error("测试不使用")
        override suspend fun probe(page: ReaderPage): PageGeometry? = null
    }

    private fun chapter(id: String, pageCount: Int, knownPageCount: Int? = null): ViewerChapter =
        ViewerChapter(
            chapter = ChapterRecord(
                chapterId = id,
                mangaId = "manga",
                documentId = "/lib/$id",
                kind = ChapterKind.IMAGE_DIRECTORY,
                title = "第 $id 章",
                sortKey = id,
                pageCount = knownPageCount,
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

    /** 窗口便捷构造：[previous] 由近及远给出，内部按"由远及近"存放。 */
    private fun window(
        current: ViewerChapter,
        previous: List<ViewerChapter> = emptyList(),
        next: List<ViewerChapter> = emptyList(),
    ): ViewerChapters = ViewerChapters(
        current = current,
        previousWindow = previous.asReversed(),
        nextWindow = next,
    )

    // ------------------------------------------------------------ 项列表组装

    @Test
    fun `只有当前章时项的页部分就是它的页并带一个到底过渡`() {
        val current = chapter("c1", 3)

        val result = buildReaderItems(ViewerChapters(current = current))

        // 3 页 + 末尾过渡（没有下一章，用来表达"到底了"，不是错误状态）。
        assertEquals(4, result.items.size)
        assertEquals(0, result.currentChapterOffset)
        assertEquals(
            listOf("c1-p0", "c1-p1", "c1-p2"),
            result.items.filterIsInstance<ReaderItem.PageItem>().map { it.page.pageId },
        )
        assertTrue(assertIs<ReaderItem.Transition>(result.items.last()).isEnd)
    }

    @Test
    fun `有下一章时在末尾插入过渡项且过渡后紧接着下一章的页`() {
        val current = chapter("c1", 2)
        val next = chapter("c2", 2)

        val result = buildReaderItems(window(current, next = listOf(next)))

        // 当前章 2 页 + 过渡 + 下一章 2 页 + 到底过渡 = 6 项
        assertEquals(0, result.currentChapterOffset)
        val transition = assertIs<ReaderItem.Transition>(result.items[2])
        assertTrue(transition.forward)
        assertEquals("c2", transition.to?.chapterId)
        assertEquals("c2", (result.items[3] as ReaderItem.PageItem).chapterId)
    }

    @Test
    fun `有上一章时它排在前面且当前章偏移正确`() {
        val previous = chapter("c0", 2)
        val current = chapter("c1", 3)

        val result = buildReaderItems(window(current, previous = listOf(previous)))

        // 反向过渡 + 上一章 2 页 + 当前章 3 页 + 到底过渡
        assertEquals(7, result.items.size)
        // 当前章第一项的下标必须是 3（1 个反向过渡 + 2 页），否则"章内第几页"会整体错位。
        assertEquals(3, result.currentChapterOffset)
        assertEquals("c1", result.items[3].chapterId)
        val backward = assertIs<ReaderItem.Transition>(result.items[0])
        assertTrue(!backward.forward)
        assertEquals("c0", backward.from?.chapterId)
        assertEquals("c1", backward.to?.chapterId)
    }

    @Test
    fun `没有上一章时不插入反向过渡`() {
        val current = chapter("c1", 1)

        val result = buildReaderItems(ViewerChapters(current = current))

        // 没有更早的章可去，因此不需要端点提示：当前章第一项就是列表第一项。
        assertTrue(result.items.none { it is ReaderItem.Transition && !it.forward })
        assertEquals("c1", result.items.first().chapterId)
    }

    @Test
    fun `多章窗口里每个跨章边界恰好一个过渡项`() {
        val c0 = chapter("c0", 2)
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 2)
        val c3 = chapter("c3", 2)

        val result = buildReaderItems(window(c1, previous = listOf(c0), next = listOf(c2, c3)))

        val transitions = result.items.filterIsInstance<ReaderItem.Transition>()
        // c0→c1（反向）、c1→c2、c2→c3、c3 之后到底 = 4 个
        assertEquals(4, transitions.size)
        assertEquals(
            listOf("transition:b:c0->c1", "transition:f:c1->c2", "transition:f:c2->c3", "transition:f:c3->"),
            transitions.map { it.key },
        )
        // 反向过渡必须真的存在：它是"往回翻能回到上一章"的唯一入口。
        assertEquals(1, transitions.count { !it.forward })
        // 每个边界的过渡项必须紧贴它连接的两章之间，不能串位。
        val order = result.items.map { it.chapterId }
        assertEquals(
            listOf("c0", "c0", "c0", "c1", "c1", "c1", "c2", "c2", "c2", "c3", "c3", "c3"),
            order,
        )
    }

    @Test
    fun `过渡项的键唯一且不含页身份`() {
        val c0 = chapter("c0", 1)
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)

        val result = buildReaderItems(window(c1, previous = listOf(c0), next = listOf(c2)))

        val keys = result.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "键必须唯一，否则分页器会串项")
        val transitionKeys = result.items.filterIsInstance<ReaderItem.Transition>().map { it.key }
        assertEquals(3, transitionKeys.size)
        assertTrue(transitionKeys.all { it.startsWith("transition:") })
    }

    /**
     * 同一个跨章边界在**不同窗口**下必须算出同一个键。
     *
     * 这是预载窗口能安全变化的前提：窗口一变，项列表整体重建，
     * [resolveLanding] 要靠过渡项的键在旧列表里找到它；键若随窗口变化就永远找不到，
     * 读者会被送回章首。
     */
    @Test
    fun `同一个跨章边界的过渡键不随窗口变化`() {
        val c0 = chapter("c0", 2)
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 2)

        val narrow = buildReaderItems(window(c1, next = listOf(c2)))
        val wide = buildReaderItems(window(c1, previous = listOf(c0), next = listOf(c2)))

        val narrowKey = narrow.items.filterIsInstance<ReaderItem.Transition>()
            .single { it.to?.chapterId == "c2" }.key
        val wideKey = wide.items.filterIsInstance<ReaderItem.Transition>()
            .single { it.to?.chapterId == "c2" }.key
        assertEquals(narrowKey, wideKey)
    }

    @Test
    fun `项身份在列表平移后仍然稳定`() {
        val previous = chapter("c0", 2)
        val current = chapter("c1", 3)

        val before = buildReaderItems(ViewerChapters(current = current))
        val after = buildReaderItems(window(current, previous = listOf(previous)))

        // 预载把上一章插到前面，下标整体后移；但同一页的 key 不变，
        // 因此可以按身份找回位置（`reanchorIndex` 的契约）。
        val anchorKey = before.items[1].key
        val foundAt = after.items.indexOfFirst { it.key == anchorKey }
        assertEquals(4, foundAt, "预载后原第 1 页应移到下标 4")
    }

    @Test
    fun `相邻章查询按方向取`() {
        val c0 = chapter("c0", 1)
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)
        val chapters = window(c1, previous = listOf(c0), next = listOf(c2))

        assertEquals("c2", chapters.neighbor(forward = true)?.chapterId)
        assertEquals("c0", chapters.neighbor(forward = false)?.chapterId)
        // 只有当前章时两个方向都没有邻章。
        val alone = ViewerChapters(current = c1)
        assertNull(alone.neighbor(forward = true))
        assertNull(alone.neighbor(forward = false))
    }

    @Test
    fun `窗口远端是紧邻的那一章`() {
        val c0 = chapter("c0", 1)
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)
        val c3 = chapter("c3", 1)

        val chapters = window(c1, previous = listOf(c2, c0), next = listOf(c2, c3))

        // previousWindow 由远及近：最早的一章排在前面，紧邻的排最后。
        assertEquals(listOf("c0", "c2"), chapters.previousWindow.map { it.chapterId })
        assertEquals("c2", chapters.previous?.chapterId)
        assertEquals(listOf("c2", "c3"), chapters.nextWindow.map { it.chapterId })
        assertEquals("c2", chapters.next?.chapterId)
    }

    /** 作品里的章节顺序：c0 < c1 < c2 < c3。 */
    private val order = mapOf("c0" to 0, "c1" to 1, "c2" to 2, "c3" to 3, "c4" to 4)

    @Test
    fun `补章节必须真的进窗口而不是被当成替换`() {
        val c1 = chapter("c1", 3)
        val c2 = chapter("c2", 3)
        val c3 = chapter("c3", 3)
        val placeholder = { c: ViewerChapter ->
            ViewerChapter(
                chapter = c.chapter,
                pages = emptyList(),
                source = source,
                state = ViewerChapter.LoadState.LOADING,
            )
        }

        // 真机上在这里错过一次：把"补一章"写成"按 ID 替换"，而新章还不在窗口里，
        // 于是替换静默失败、`next` 仍为 null，末页显示"已是最后一章"，
        // 而后面明明还有几十章。
        val grown = ViewerChapters(current = c1)
            .withAdded(placeholder(c2), forward = true)
            .withAdded(placeholder(c3), forward = true)

        assertEquals(listOf("c2", "c3"), grown.nextWindow.map { it.chapterId })
        assertEquals("c2", grown.next?.chapterId, "紧邻的下一章必须能从列表推出来")
        assertTrue(grown.contains("c3"))
    }

    @Test
    fun `排序把上一章窗口排成由远及近`() {
        val c0 = chapter("c0", 1)
        val c2 = chapter("c2", 1)
        val c3 = chapter("c3", 1)

        // 乱序补入：只有排序之后 previousWindow 才满足"由远及近"的约定，
        // 而 previous（紧邻的上一章）依赖这个约定。当前章是 c3，因此更近的是 c2。
        val grown = ViewerChapters(current = c3)
            .withAdded(c2, forward = false)
            .withAdded(c0, forward = false)
            .withAdded(c0, forward = false) // 重复补入必须幂等
            .sortedByChapterOrder { order.getValue(it) }

        assertEquals(listOf("c0", "c2"), grown.previousWindow.map { it.chapterId })
        assertEquals("c2", grown.previous?.chapterId, "紧邻的上一章是离当前章最近的那个")
    }

    @Test
    fun `补章节是幂等的`() {
        val c1 = chapter("c1", 1)
        val c2 = chapter("c2", 1)

        val once = ViewerChapters(current = c1).withAdded(c2, forward = true)
        val twice = once.withAdded(c2, forward = true)

        assertEquals(once, twice)
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
     * 最关键的一条：**页数未知时也必须把紧邻的一章放进来**。
     *
     * 没有这条，第一部打开的漫画（数据库里没有 pageCount、目录也没枚举过）会把窗口算成
     * 空的，于是末页的过渡页显示"已是最后一章"，而后面其实还有几十章。真机上就是这样。
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

        // 一章要付 1 个过渡页 + 10 页 = 11 > 9，因此只拿到紧邻的一章。
        assertEquals(listOf(4), plan.previousIndices)
        assertEquals(listOf(6), plan.nextIndices)
    }

    /**
     * 用户要求的那条规则：**章节过渡页也算一页**，因此后一章比预算短时要再要一章。
     */
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
        // previousIndices 与 previousWindow 同序——"由远及近"，因此 3 排在 4 前面。
        assertEquals(listOf(3, 4), plan.previousIndices)
    }

    @Test
    fun `预算是按照每章扣一个过渡页来算的`() {
        // 每章 2 页：跨一章付 3，预算 9 → 三章（3+3+3 = 9，刚好用完即停）。
        val plan = PreloadPlan.compute(
            chapterCount = 20,
            currentIndex = 10,
            budget = 9,
            maxChapters = 6,
            pagesOf = { 2 },
        )

        assertEquals(listOf(11, 12, 13), plan.nextIndices)
    }

    /**
     * 短章之后紧跟未知章：未知章是边界，必须放进来而不能被预算挡掉。
     */
    @Test
    fun `短章后面的未知章仍会作为边界被预载`() {
        val plan = PreloadPlan.compute(
            chapterCount = 20,
            currentIndex = 10,
            budget = 9,
            maxChapters = 6,
            // 11 章 2 页，12 章起未知。
            pagesOf = { index -> if (index == 11) 2 else null },
        )

        // 11 章花掉 1 + 2 = 3，剩 6；12 章未知 → 作为边界放进来后停。
        assertEquals(listOf(11, 12), plan.nextIndices)
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
        val first = PreloadPlan.compute(
            chapterCount = 3,
            currentIndex = 0,
            budget = 9,
            maxChapters = 3,
            pagesOf = { 2 },
        )
        // 没有上一章可取。
        assertEquals(emptyList(), first.previousIndices)
        assertEquals(listOf(1, 2), first.nextIndices)

        val last = PreloadPlan.compute(
            chapterCount = 3,
            currentIndex = 2,
            budget = 9,
            maxChapters = 3,
            pagesOf = { 2 },
        )
        assertEquals(emptyList(), last.nextIndices)
        // 由远及近：0 排在 1 前面。
        assertEquals(listOf(0, 1), last.previousIndices)
    }

    @Test
    fun `页数确定时预载规划可重复`() {
        // 同样的输入必须给出同样的结果：规划若会因"某章加载完了"而漂移，
        // 窗口就会反复重建，读者看到的是持续抖动。
        val first = PreloadPlan.compute(10, 5, 9, 3) { 4 }
        val second = PreloadPlan.compute(10, 5, 9, 3) { 4 }

        assertEquals(first, second)
    }

    // ------------------------------------------------------------ 跨章落点

    @Test
    fun `按页身份定位优先于过渡项后继`() {
        val c1 = chapter("c1", 3)
        val c2 = chapter("c2", 3)
        val items = buildReaderItems(window(c2, previous = listOf(c1)))

        // 页身份在列表里，直接命中。
        val landing = resolveLanding(
            newItems = items.items,
            preferredPageId = "c2-p2",
            transitionKey = "transition:f:c1->c2",
            fallbackOffset = 0,
        )

        val landed = assertIs<ReaderItem.PageItem>(items.items[landing])
        assertEquals("c2", landed.chapterId)
        assertEquals(2, landed.page.ordinal)
    }

    /**
     * 关键回归：预载窗口变化会让**过渡项本身**从新列表里消失。
     *
     * 读者在第 2 章的过渡页上进入第 3 章时，新窗口以第 3 章为当前章、不再包含
     * "第 2 章的正向过渡"，于是键查不到。此时必须靠页身份落到第 3 章第一页，
     * 而不是退回某个下标。
     */
    @Test
    fun `过渡项在新列表里消失时仍靠页身份落在目标章第一页`() {
        val c2 = chapter("c2", 2)
        val c3 = chapter("c3", 4)
        val c4 = chapter("c4", 4)

        // 进入之前：c2 是当前章，它的正向过渡指向 c3。
        val before = buildReaderItems(window(c2, next = listOf(c3, c4)))
        val transition = before.items.filterIsInstance<ReaderItem.Transition>()
            .single { it.from?.chapterId == "c2" && it.to?.chapterId == "c3" }

        // 提升之后：c3 成为当前章。新窗口的 chapterCount 小到不再包含 c2，
        // 因此那个过渡项不存在了。
        val after = buildReaderItems(window(c3, next = listOf(c4)))
        assertEquals(-1, after.items.indexOfTransition(transition.key), "该过渡项应当已不在新列表里")

        val landing = resolveLanding(
            newItems = after.items,
            preferredPageId = c3.pages.first().pageId,
            transitionKey = transition.key,
            fallbackOffset = 0,
        )

        val landed = assertIs<ReaderItem.PageItem>(after.items[landing])
        assertEquals("c3", landed.chapterId)
        assertEquals(0, landed.page.ordinal, "必须落在目标章第一页")
    }

    @Test
    fun `没有页身份时退回过渡项的后继`() {
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 3)
        val items = buildReaderItems(window(c2, previous = listOf(c1)))

        // 反向过渡排在上一章页之前，因此它的后继是上一章第一页——这就是"往回翻一页"
        // 的落点。键必须与 `buildReaderItems` 生成的完全一致，否则这条兜底路径失效。
        val backwardKey = "transition:b:${c1.chapterId}->${c2.chapterId}"
        assertEquals(0, items.items.indexOfTransition(backwardKey))

        val landing = resolveLanding(
            newItems = items.items,
            preferredPageId = null,
            transitionKey = backwardKey,
            fallbackOffset = 99,
        )

        val landed = assertIs<ReaderItem.PageItem>(items.items[landing])
        assertEquals("c1", landed.chapterId)
        assertEquals(0, landed.page.ordinal)
    }

    @Test
    fun `两种依据都失效时退回兜底位置`() {
        val current = chapter("c1", 2)
        val items = buildReaderItems(ViewerChapters(current = current)).items

        assertEquals(1, resolveLanding(items, null, "transition:不存在", fallbackOffset = 1))
        assertEquals(0, resolveLanding(items, "p-不存在", null, fallbackOffset = 0))
    }

    @Test
    fun `落点始终被夹在合法下标内`() {
        val current = chapter("c1", 1)
        val items = buildReaderItems(ViewerChapters(current = current)).items

        // 兜底值越界时不抛异常而是夹回范围——越界会让分页器直接崩。
        assertEquals(0, resolveLanding(items, null, null, fallbackOffset = -5))
        assertEquals(items.lastIndex, resolveLanding(items, null, null, fallbackOffset = 999))
        assertEquals(0, resolveLanding(emptyList(), "p", "t", fallbackOffset = 3))
    }

    @Test
    fun `往回翻先经过反向过渡再落在上一章开头`() {
        val c1 = chapter("c1", 2)
        val c2 = chapter("c2", 2)

        // 阅读顺序上的"前一页"不等同于"上一章的最后一页"：从 c2 第一页往回翻，
        // 落在 c1 与 c2 之间那个**反向过渡**上（列表里它排在 c1 的页之前），
        // 再往前翻才是 c1 的页。这个用例把这条真实结构钉住——它说明 settleOn 的
        // 过渡项分支必须能处理 backward，而不能假定过渡项一律"从当前章指向下一章"。
        val before = buildReaderItems(window(c2, previous = listOf(c1)))
        val backward = assertIs<ReaderItem.Transition>(before.items[0])
        assertTrue(!backward.forward)
        assertEquals("c1-p0", (before.items[1] as ReaderItem.PageItem).page.pageId)

        // 提升上一章为当前章后，同一个页面身份仍能被找回。
        val after = buildReaderItems(window(c1, next = listOf(c2)))
        val anchor = before.items[1].key
        val found = after.items.indexOfFirst { it.key == anchor }
        assertTrue(found >= 0, "页面身份在新列表里必须仍然存在")
        val landed = assertIs<ReaderItem.PageItem>(after.items[found])
        assertEquals("c1", landed.chapterId)
        assertEquals(0, landed.page.ordinal)
    }

    @Test
    fun `重新规划窗口不得把当前章的页清空`() {
        val c1 = chapter("c1", 3)
        val c2 = chapter("c2", 2)
        val loaded = window(c1, next = listOf(c2))

        // `applyWindow` 每轮都会把规划出的章"补"进窗口，而规划永远不包含当前章；
        // 若 withChapter 无条件替换同 ID 的那一格，当前章的页清单会被换成空占位，
        // 于是整章的页从列表里消失、只剩过渡项。真机上表现为"翻页后页面全没了"。
        val reApplied = loaded.withChapter(
            ViewerChapter(c1.chapter, pages = emptyList(), source = source, state = ViewerChapter.LoadState.LOADING),
        )

        assertEquals(loaded, reApplied)

        // 真拿到新状态时（加载完成）才允许覆盖，且必须显式要求。
        val reloaded = chapter("c1", 5)
        assertEquals(5, loaded.withChapter(reloaded, force = true).current.pages.size)
    }

    // ------------------------------------------------------------ 条漫

    @Test
    fun `当前章没有页时章内偏移为空而不是零`() {
        val current = chapter("c1", 0)

        val result = buildReaderItems(ViewerChapters(current = current))

        // 条漫在窗口里的章还没加载完时会拼出"只有过渡项"的列表；此时若把偏移当成 0，
        // 恢复进度就会落到过渡页上。
        assertNull(result.currentChapterOffset)
        assertNotNull(result.items.filterIsInstance<ReaderItem.Transition>().firstOrNull())
    }
}
