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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
