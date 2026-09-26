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
}
