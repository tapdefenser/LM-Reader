package com.lmreader.core.storage.scan

import com.lmreader.core.model.ChapterCountProbeTarget
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 滚动时的章节计数。
 *
 * 判据全部对着"与全量同步同口径"这一点：归档优先于子目录、只有直接图片算 1 章、
 * 单章节模式不读盘、目录读不到就是 null（会被记成"数过但没数出"）。
 */
class ChapterCounterTest {

    private fun image(id: String, name: String = "$id.jpg") = ChildNode(
        documentId = id,
        name = name,
        isDirectory = false,
        mimeType = "image/jpeg",
    )

    private fun archive(id: String, name: String) = ChildNode(
        documentId = id,
        name = name,
        isDirectory = false,
        mimeType = null,
    )

    private fun dir(id: String, name: String) = ChildNode(
        documentId = id,
        name = name,
        isDirectory = true,
        mimeType = null,
    )

    private class FakeTree(private val children: List<ChildNode>) : ContentTree {
        override val rootName: String = "root"
        override suspend fun listChildren(): List<ChildNode> = children
        override suspend fun hasDirectoryChildren(): Boolean = children.any { it.isDirectory }
        override suspend fun hasImageChild(): Boolean = children.any { !it.isDirectory }
        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private fun target(layoutMode: LayoutMode = LayoutMode.MULTI_CHAPTER) = ChapterCountProbeTarget(
        mangaId = "m1",
        anchorDocumentId = "anchor",
        layoutMode = layoutMode,
        sourceTreeUri = "tree",
    )

    private fun counter(children: List<ChildNode>?) = ChapterCounter(
        openTree = { _, _ -> children?.let { FakeTree(it) } },
    )

    @Test
    fun `多章节按子目录个数计数`() = runTest {
        val children = listOf(dir("c1", "第1话"), dir("c2", "第2话"), dir("c3", "第3话"))
        assertEquals(3, counter(children).count(target()))
    }

    @Test
    fun `归档优先于子目录（与全量同步同口径，不是相加）`() = runTest {
        // 混放：全量同步（StructureScanner）在"有归档"时把归档当全部章节、跳过子目录，
        // 因此这里必须也是 2 而不是 4，否则卡片数与详情页的章节表会对不上。
        val children = listOf(
            archive("a1", "01.cbz"),
            archive("a2", "02.zip"),
            dir("c1", "第1话"),
            dir("c2", "第2话"),
        )
        assertEquals(2, counter(children).count(target()))
    }

    @Test
    fun `只有直接图片时算 1 章`() = runTest {
        assertEquals(1, counter(listOf(image("p1"), image("p2"))).count(target()))
    }

    @Test
    fun `单章节模式不读盘就是 1 章`() = runTest {
        // openTree 返回 null（等于目录根本打不开），但单章节模式压根不该去开它。
        assertEquals(1, counter(null).count(target(LayoutMode.SINGLE_CHAPTER)))
    }

    @Test
    fun `目录打不开或空目录就是没有`() = runTest {
        assertNull(counter(null).count(target()))
        assertNull(counter(emptyList()).count(target()))
        // 只有非图片文件（例如 ComicInfo.xml）也算没有可读章节。
        assertNull(counter(listOf(ChildNode("x", "ComicInfo.xml", false, "text/xml"))).count(target()))
    }

    @Test
    fun `嵌套分组目录会按卷数计（已知偏差，写入测试以免被当成回归）`() = runTest {
        // Vol.1/Ch.1、Vol.1/Ch.2、Vol.2/Ch.1 —— 只列一层只能数出 2。
        // 这是"一次 listChildren 换掉 N 次目录列举"的代价；用户点过「更新章节」之后
        // chapterCountKnown 的准确值会优先显示。
        val children = listOf(dir("v1", "Vol.1"), dir("v2", "Vol.2"))
        assertEquals(2, counter(children).count(target()))
    }
}
