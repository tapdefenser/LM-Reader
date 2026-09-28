package com.lmreader.core.storage.cover

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.CoverProbeTarget
import com.lmreader.core.model.LayoutMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面解析（图库滚动懒加载与「更新章节」共用这一份规则）。
 *
 * 覆盖的是真机上真实存在的形状：
 * - 多章节：第一章是图片目录 → 取它自然序第一张图；
 * - 多章节：第一章是归档（zip）→ 往下试；
 * - 单章节：锚点目录本身就是章节；
 * - 空目录 / 目录打不开 → 返回 null（调用方记成"探测过但没有"，不再重试）。
 */
class CoverResolverTest {

    private fun node(id: String, name: String, dir: Boolean = false) = ChildNode(
        documentId = id,
        name = name,
        isDirectory = dir,
        mimeType = if (dir) null else "image/jpeg",
    )

    private class FakeTree(private val children: List<ChildNode>) : ContentTree {
        override val rootName: String = "root"
        override suspend fun listChildren(): List<ChildNode> = children
        override suspend fun hasDirectoryChildren(): Boolean = children.any { it.isDirectory }
        override suspend fun hasImageChild(): Boolean = children.any { !it.isDirectory }
        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private fun chapter(id: String, documentId: String, kind: ChapterKind = ChapterKind.IMAGE_DIRECTORY) =
        ChapterRecord(
            chapterId = id,
            mangaId = "m1",
            documentId = documentId,
            kind = kind,
            title = documentId.substringAfterLast('/'),
            sortKey = documentId,
            pageCount = null,
            coverDocumentId = null,
            contentRevision = 0,
            discoveredAt = 0,
        )

    private fun target(
        layoutMode: LayoutMode = LayoutMode.MULTI_CHAPTER,
        anchor: String = "/root/Manga",
        firstChapter: ChapterRecord? = chapter("c1", "/root/Manga/Chapter 1"),
    ) = CoverProbeTarget(
        mangaId = "m1",
        anchorDocumentId = anchor,
        layoutMode = layoutMode,
        sourceTreeUri = "content://tree",
        firstChapter = firstChapter,
    )

    @Test
    fun `多章节取第一章的自然序首图`() = runTest {
        val trees = mapOf(
            // 枚举顺序刻意打乱：封面必须按自然序取，而不是"碰巧第一个"（验收 A12）。
            "/root/Manga/Chapter 1" to FakeTree(
                listOf(
                    node("/root/Manga/Chapter 1/010.jpg", "010.jpg"),
                    node("/root/Manga/Chapter 1/002.jpg", "002.jpg"),
                    node("/root/Manga/Chapter 1/001.jpg", "001.jpg"),
                ),
            ),
        )
        val resolver = CoverResolver { _, documentId -> trees[documentId] }

        val cover = resolver.resolve(target())

        assertEquals("/root/Manga/Chapter 1/001.jpg", cover?.coverDocumentId)
        assertEquals("c1", cover?.coverChapterId)
    }

    @Test
    fun `子目录不算图片`() = runTest {
        val trees = mapOf(
            "/root/Manga/Chapter 1" to FakeTree(
                listOf(
                    node("/root/Manga/Chapter 1/sub", "sub", dir = true),
                    node("/root/Manga/Chapter 1/001.jpg", "001.jpg"),
                ),
            ),
        )
        val resolver = CoverResolver { _, documentId -> trees[documentId] }

        assertEquals("/root/Manga/Chapter 1/001.jpg", resolver.resolve(target())?.coverDocumentId)
    }

    @Test
    fun `第一章是归档时往下试第二章`() = runTest {
        val trees = mapOf(
            "/root/Manga/Chapter 2" to FakeTree(listOf(node("/root/Manga/Chapter 2/001.jpg", "001.jpg"))),
        )
        val opened = mutableListOf<String>()
        val resolver = CoverResolver { _, documentId ->
            opened += documentId
            trees[documentId]
        }

        val cover = resolver.resolve(
            target(firstChapter = chapter("c2", "/root/Manga/Chapter 2")),
        )

        assertEquals("/root/Manga/Chapter 2/001.jpg", cover?.coverDocumentId)
        // 归档章节连打开都不该尝试：本步不解析归档内部成员。
        assertEquals(listOf("/root/Manga/Chapter 2"), opened)
    }

    @Test
    fun `单章节用锚点目录本身`() = runTest {
        val trees = mapOf(
            "/root/Single" to FakeTree(listOf(node("/root/Single/001.jpg", "001.jpg"))),
        )
        val resolver = CoverResolver { _, documentId -> trees[documentId] }

        val cover = resolver.resolve(
            target(
                layoutMode = LayoutMode.SINGLE_CHAPTER,
                anchor = "/root/Single",
                firstChapter = chapter("c9", "/root/Single"),
            ),
        )

        assertEquals("/root/Single/001.jpg", cover?.coverDocumentId)
        assertEquals("c9", cover?.coverChapterId)
    }

    @Test
    fun `空目录返回 null —— 调用方据此记为已探测`() = runTest {
        val trees = mapOf("/root/Manga/Chapter 1" to FakeTree(emptyList()))
        val resolver = CoverResolver { _, documentId -> trees[documentId] }

        assertNull(resolver.resolve(target()))
    }

    @Test
    fun `目录打不开返回 null 而不是抛异常`() = runTest {
        val resolver = CoverResolver { _, _ -> null }

        assertNull(resolver.resolve(target()))
    }

    @Test
    fun `章节表为空的多章节不算封面`() = runTest {
        val resolver = CoverResolver { _, _ -> error("不该打开任何目录") }

        assertNull(resolver.resolve(target(firstChapter = null)))
    }

    @Test
    fun `最多往下试三个章节`() = runTest {
        val opened = mutableListOf<String>()
        val resolver = CoverResolver { _, documentId ->
            opened += documentId
            // 目录读得到、但里面没有受支持的图片（例如只有文本与 XML）：
            // 必须停下来，不能把整部漫画的章节枚举一遍。
            FakeTree(
                listOf(
                    ChildNode("$documentId/ComicInfo.xml", "ComicInfo.xml", false, "text/xml"),
                    ChildNode("$documentId/readme.txt", "readme.txt", false, "text/plain"),
                ),
            )
        }

        assertNull(resolver.resolve(target()))
        assertEquals(1, opened.size)
    }
}
