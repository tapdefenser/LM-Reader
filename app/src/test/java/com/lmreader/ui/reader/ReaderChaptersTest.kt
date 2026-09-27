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
import kotlin.test.assertTrue

/**
 * 多章节编排的单元测试。
 *
 * 这些用例保护的是**静默出错**的行为：项列表组装错了不会崩溃，只会让读者在章末撞墙、
 * 或者预载完成后突然跳页。两者都极难靠手工回归发现，因此用测试把结构钉住。
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

    @Test
    fun `只有当前章时项的页部分就是它的页并带一个到底过渡`() {
        val current = chapter("c1", 3)

        val result = buildReaderItems(ViewerChapters(current = current), alwaysShowTransition = true)

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
    fun `有下一章时在末尾插入过渡项`() {
        val current = chapter("c1", 2)
        val next = chapter("c2", 2)

        val result = buildReaderItems(
            ViewerChapters(current = current, next = next),
            alwaysShowTransition = true,
        )

        // 当前章 2 页 + 过渡 + 下一章 2 页 = 5 项
        assertEquals(5, result.items.size)
        assertEquals(0, result.currentChapterOffset)
        val transition = assertIs<ReaderItem.Transition>(result.items[2])
        assertTrue(transition.forward)
        assertEquals("c2", transition.to?.chapterId)
        // 过渡之后紧接着就是下一章的页
        assertEquals("c2", (result.items[3] as ReaderItem.PageItem).chapterId)
    }

    @Test
    fun `有上一章时它排在前面且当前章偏移正确`() {
        val previous = chapter("c0", 2)
        val current = chapter("c1", 3)

        val result = buildReaderItems(
            ViewerChapters(current = current, previous = previous),
            alwaysShowTransition = true,
        )

        // 上一章 2 页 + 过渡 + 当前章 3 页 + 末尾过渡（没有下一章）
        assertEquals(7, result.items.size)
        // 当前章第一项的下标必须是 3（2 页 + 1 过渡），否则"章内第几页"会整体错位。
        assertEquals(3, result.currentChapterOffset)
        assertEquals("c1", result.items[3].chapterId)
        assertIs<ReaderItem.Transition>(result.items[2])
    }

    @Test
    fun `没有下一章时仍然插入过渡项作为到底的标志`() {
        val current = chapter("c1", 1)

        val result = buildReaderItems(ViewerChapters(current = current), alwaysShowTransition = true)

        val last = assertIs<ReaderItem.Transition>(result.items.last())
        assertTrue(last.isEnd, "没有目标章节的过渡项必须能表示'到底了'")
    }

    @Test
    fun `关闭始终显示过渡时不插当前章的过渡项`() {
        val current = chapter("c1", 2)
        val next = chapter("c2", 2).copy(state = ViewerChapter.LoadState.Loaded)

        val result = buildReaderItems(
            ViewerChapters(current = current, next = next),
            alwaysShowTransition = false,
        )

        // 下一章已经加载好 → 直接接上页，不插过渡（Mihon forceTransition 同义）。
        assertEquals(4, result.items.size)
        assertTrue(result.items.all { it is ReaderItem.PageItem })
    }

    @Test
    fun `关闭始终显示过渡但目标章未加载时仍插入过渡`() {
        val current = chapter("c1", 2)
        val next = chapter("c2", 0).copy(state = ViewerChapter.LoadState.LOADING)

        val result = buildReaderItems(
            ViewerChapters(current = current, next = next),
            alwaysShowTransition = false,
        )

        // 目的是不让读者看到空白，因此未加载完时必须保留过渡页。
        assertTrue(result.items.any { it is ReaderItem.Transition })
    }

    @Test
    fun `项身份在列表平移后仍然稳定`() {
        val previous = chapter("c0", 2)
        val current = chapter("c1", 3)

        val before = buildReaderItems(ViewerChapters(current = current), alwaysShowTransition = true)
        val after = buildReaderItems(
            ViewerChapters(current = current, previous = previous),
            alwaysShowTransition = true,
        )

        // 预载把上一章的页插到前面，下标整体后移；但同一页的 key 不变，
        // 因此可以按身份找回位置（`reanchorIndex` 的契约）。
        val anchorKey = before.items[1].key
        val foundAt = after.items.indexOfFirst { it.key == anchorKey }
        assertEquals(4, foundAt, "预载后原第 1 页应移到下标 4")
    }

    @Test
    fun `过渡项的键唯一且不含页身份`() {
        val current = chapter("c1", 1)
        val next = chapter("c2", 1)
        val previous = chapter("c0", 1)

        val result = buildReaderItems(
            ViewerChapters(current = current, previous = previous, next = next),
            alwaysShowTransition = true,
        )

        val keys = result.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "键必须唯一，否则分页器会串项")
        val transitionKeys = result.items.filterIsInstance<ReaderItem.Transition>().map { it.key }
        assertEquals(2, transitionKeys.size)
        assertTrue(transitionKeys.all { it.startsWith("transition:") })
    }

    @Test
    fun `相邻章查询按方向取`() {
        val previous = chapter("c0", 1)
        val current = chapter("c1", 1)
        val next = chapter("c2", 1)
        val chapters = ViewerChapters(current = current, previous = previous, next = next)

        assertEquals("c2", chapters.neighbor(forward = true)?.chapterId)
        assertEquals("c0", chapters.neighbor(forward = false)?.chapterId)
        // 只有当前章时两个方向都没有邻章。
        val alone = ViewerChapters(current = current)
        assertEquals(null, alone.neighbor(forward = true))
        assertEquals(null, alone.neighbor(forward = false))
    }

    // ------------------------------------------------------------ 跨章落点

    @Test
    fun `翻过过渡项后落在目标章第一页而不是上一章的页`() {
        val issue1 = chapter("c1", 3)
        val issue2 = chapter("c2", 2)
        // 读者在 Issue 1 末尾点"下一页"，落到了过渡项上。
        val before = buildReaderItems(ViewerChapters(current = issue1), alwaysShowTransition = true)
        val transition = before.items.filterIsInstance<ReaderItem.Transition>().single()
        assertEquals(3, before.items.indexOfFirst { it.key == transition.key })

        // 提升后 Issue 2 成为当前章，Issue 1 变成上一章。
        val after = buildReaderItems(
            ViewerChapters(current = issue2, previous = issue1),
            alwaysShowTransition = true,
        )

        val landing = landingAfterTransition(
            after.items,
            transition.key,
            fallback = after.currentChapterOffset,
        )

        // 关键断言：落点必须是 Issue 2 的第一页，而不是过渡项自身、也不是 Issue 1 的页。
        // 这里曾经用"按项身份找回位置"，而那个身份正是过渡项自身，
        // 于是提升后落回过期位置，读者被送回上一章。
        val landed = assertIs<ReaderItem.PageItem>(after.items[landing])
        assertEquals("c2", landed.chapterId)
        assertEquals(0, landed.page.ordinal)
        assertEquals(after.currentChapterOffset, landing)
    }

    @Test
    fun `反向翻落在上一章的最后一页`() {
        val issue1 = chapter("c1", 2)
        val issue2 = chapter("c2", 2)

        // 反向翻**不经过过渡项**：过渡项只插入在当前章之后（其 `from` 是当前章），
        // 因此往回翻的落点是上一章的最后一页，是一个 PageItem。
        // 这个用例把这条真实结构钉住——它同时说明 `settleOn` 里的过渡项分支只需要
        // 处理向前的情形，不需要（也无法）处理向后。
        val before = buildReaderItems(
            ViewerChapters(current = issue2, previous = issue1),
            alwaysShowTransition = true,
        )
        assertTrue(
            before.items.filterIsInstance<ReaderItem.Transition>().all { it.forward },
            "过渡项永远是从当前章指向下一章",
        )
        val landedBefore = assertIs<ReaderItem.PageItem>(before.items[1])
        assertEquals("c1", landedBefore.chapterId)
        assertEquals(1, landedBefore.page.ordinal, "往回翻落在上一章的最后一页")

        // 提升上一章为当前章后，同一个页面身份仍能被找回（这正是 settleOn 的
        // `else` 分支所做的事：按项身份定位，而不是猜章首）。
        val after = buildReaderItems(
            ViewerChapters(current = issue1, next = issue2),
            alwaysShowTransition = true,
        )
        val anchor = before.items[1].key
        val found = after.items.indexOfFirst { it.key == anchor }
        assertTrue(found >= 0, "页面身份在新列表里必须仍然存在")
        val landedAfter = assertIs<ReaderItem.PageItem>(after.items[found])
        assertEquals("c1", landedAfter.chapterId)
        assertEquals(1, landedAfter.page.ordinal)
    }

    @Test
    fun `过渡项在新列表里找不到时退回兜底位置`() {
        val current = chapter("c1", 2)
        val items = buildReaderItems(
            ViewerChapters(current = current),
            alwaysShowTransition = true,
        ).items

        val landing = landingAfterTransition(items, transitionKey = "transition:不存在", fallback = 1)

        assertEquals(1, landing)
    }

    @Test
    fun `落点始终被夹在合法下标内`() {
        val current = chapter("c1", 1)
        val items = buildReaderItems(
            ViewerChapters(current = current),
            alwaysShowTransition = true,
        ).items

        // 兜底值越界时不抛异常而是夹回范围——越界会让分页器直接崩。
        assertEquals(0, landingAfterTransition(items, "transition:无", fallback = -5))
        assertEquals(items.lastIndex, landingAfterTransition(items, "transition:无", fallback = 999))
        assertEquals(0, landingAfterTransition(emptyList(), "transition:无", fallback = 3))
    }
}
