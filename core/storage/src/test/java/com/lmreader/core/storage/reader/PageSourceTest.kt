package com.lmreader.core.storage.reader

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.StableId
import com.lmreader.core.storage.access.TreeAccess
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PageSourceTest {
    @Test
    fun `image directory pages use natural order and stable ids`() = runTest {
        val treeAccess = mockk<TreeAccess>()
        every { treeAccess.openAt(TREE_URI, CHAPTER_DOCUMENT_ID) } returns StaticTree(
            listOf(
                image("10.jpg"),
                image("2.png"),
                image("1.webp"),
                ChildNode("$CHAPTER_DOCUMENT_ID/nested", "nested", true, null),
                ChildNode("$CHAPTER_DOCUMENT_ID/readme.txt", "readme.txt", false, "text/plain"),
            ),
        )
        val source = assertIs<PageSourceOpenResult.Ready>(
            PageSourceFactory(treeAccess).open(TREE_URI, chapter()),
        ).source

        val first = source.pages()
        val second = source.pages()

        assertEquals(listOf("1.webp", "2.png", "10.jpg"), first.map { it.displayName })
        assertEquals(listOf(0, 1, 2), first.map { it.ordinal })
        assertEquals(
            StableId.pageId(CHAPTER_ID, "$CHAPTER_DOCUMENT_ID/1.webp"),
            first.first().pageId,
        )
        assertEquals(first, second)
        verify(exactly = 1) { treeAccess.openAt(TREE_URI, CHAPTER_DOCUMENT_ID) }
    }

    @Test
    fun `opening page delegates to tree access with fresh stream`() = runTest {
        val treeAccess = mockk<TreeAccess>()
        val bytes = byteArrayOf(1, 2, 3)
        every { treeAccess.openAt(TREE_URI, CHAPTER_DOCUMENT_ID) } returns StaticTree(listOf(image("1.jpg")))
        every { treeAccess.openInputStream(TREE_URI, "$CHAPTER_DOCUMENT_ID/1.jpg") } answers {
            ByteArrayInputStream(bytes)
        }
        val source = assertIs<PageSourceOpenResult.Ready>(
            PageSourceFactory(treeAccess).open(TREE_URI, chapter()),
        ).source
        val page = source.pages().single()

        val first = source.open(page).use { it.readBytes() }
        val second = source.open(page).use { it.readBytes() }

        assertContentEquals(bytes, first)
        assertContentEquals(bytes, second)
    }

    /**
     * 尺寸探测失败必须是"尺寸未知"而不是"页面不可读"。
     *
     * 这个用例在普通 JVM 单元测试里跑，`BitmapFactory` 是桩实现、解不出任何尺寸，
     * 因此正好覆盖"图像头部读不出宽高"这条路径。真实的尺寸解码由真机验证覆盖
     * （Android 的 `BitmapFactory` 在 JVM 单测里没有可用实现）。
     */
    @Test
    fun `probe 对无法解码的内容返回 null 而不是抛异常`() = runTest {
        val treeAccess = mockk<TreeAccess>()
        every { treeAccess.openAt(TREE_URI, CHAPTER_DOCUMENT_ID) } returns StaticTree(listOf(image("1.jpg")))
        every { treeAccess.openInputStream(TREE_URI, "$CHAPTER_DOCUMENT_ID/1.jpg") } answers {
            ByteArrayInputStream(byteArrayOf(0, 1, 2, 3))
        }
        val source = assertIs<PageSourceOpenResult.Ready>(
            PageSourceFactory(treeAccess).open(TREE_URI, chapter()),
        ).source

        val geometry = source.probe(source.pages().single())

        assertEquals(null, geometry, "读不到尺寸应降级为 null，让调用方用占位高度")
    }

    /** 页面已不在章节里仍然要抛 [java.io.FileNotFoundException]：那是章级问题，不是尺寸问题。 */
    @Test
    fun `probe 对不属于本章的页面抛出文件未找到`() = runTest {
        val treeAccess = mockk<TreeAccess>()
        every { treeAccess.openAt(TREE_URI, CHAPTER_DOCUMENT_ID) } returns StaticTree(listOf(image("1.jpg")))
        val source = assertIs<PageSourceOpenResult.Ready>(
            PageSourceFactory(treeAccess).open(TREE_URI, chapter()),
        ).source

        assertFailsWith<FileNotFoundException> {
            source.probe(
                ReaderPage(
                    pageId = "p_stale",
                    ordinal = 0,
                    displayName = "gone.jpg",
                    documentId = "$CHAPTER_DOCUMENT_ID/gone.jpg",
                ),
            )
        }
    }

    private fun chapter() = ChapterRecord(
        chapterId = CHAPTER_ID,
        mangaId = "manga",
        documentId = CHAPTER_DOCUMENT_ID,
        kind = ChapterKind.IMAGE_DIRECTORY,
        title = "Chapter",
        sortKey = "chapter",
        pageCount = null,
        coverDocumentId = null,
        contentRevision = 1,
        discoveredAt = 1,
    )

    private fun image(name: String) = ChildNode(
        documentId = "$CHAPTER_DOCUMENT_ID/$name",
        name = name,
        isDirectory = false,
        mimeType = "image/jpeg",
    )

    private class StaticTree(private val children: List<ChildNode>) : ContentTree {
        override val rootName = "Chapter"
        override suspend fun listChildren() = children
        override suspend fun hasDirectoryChildren() = false
        override suspend fun hasImageChild() = true
        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private companion object {
        const val TREE_URI = "content://test/tree/library"
        const val CHAPTER_ID = "chapter"
        const val CHAPTER_DOCUMENT_ID = "/library/manga/chapter"
    }
}
