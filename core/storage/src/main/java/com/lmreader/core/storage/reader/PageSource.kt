package com.lmreader.core.storage.reader

import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MimeTypes
import com.lmreader.core.model.StableId
import com.lmreader.core.storage.access.TreeAccess
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

data class ReaderPage(
    val pageId: String,
    val ordinal: Int,
    val displayName: String,
    val documentId: String,
)

/** 所有章节类型的统一页源契约（开发文档 12、15.2）。 */
interface PageSource {
    suspend fun pages(): List<ReaderPage>

    /** 每次返回新流；调用方负责关闭。 */
    suspend fun open(page: ReaderPage): InputStream
}

sealed interface PageSourceOpenResult {
    data class Ready(val source: PageSource) : PageSourceOpenResult
    data class Unsupported(val reason: String) : PageSourceOpenResult
}

class PageSourceFactory(
    private val treeAccess: TreeAccess,
) {
    fun open(treeUri: String, chapter: ChapterRecord): PageSourceOpenResult = when (chapter.kind) {
        ChapterKind.IMAGE_DIRECTORY -> PageSourceOpenResult.Ready(
            ImageDirectoryPageSource(treeAccess, treeUri, chapter),
        )
        ChapterKind.ARCHIVE -> PageSourceOpenResult.Unsupported(
            "归档与 PDF 阅读尚未接入，请选择图片目录章节",
        )
    }
}

internal class ImageDirectoryPageSource(
    private val treeAccess: TreeAccess,
    private val treeUri: String,
    private val chapter: ChapterRecord,
) : PageSource {
    private var cachedPages: List<ReaderPage>? = null

    override suspend fun pages(): List<ReaderPage> {
        cachedPages?.let { return it }
        val tree = treeAccess.openAt(treeUri, chapter.documentId)
            ?: throw IOException("无法打开章节目录，可能已被移动或授权失效")
        val images = tree.listChildren()
            .asSequence()
            .filter { !it.isDirectory && it.isSupportedImage() }
            .sortedWith { left, right ->
                NaturalOrder.compare(left.name, right.name)
                    .takeIf { it != 0 }
                    ?: left.documentId.compareTo(right.documentId)
            }
            .toList()
        if (images.isEmpty()) throw IOException("章节目录内没有受支持的图片")
        return images.mapIndexed { index, node ->
            ReaderPage(
                pageId = StableId.pageId(chapter.chapterId, node.documentId),
                ordinal = index,
                displayName = node.name,
                documentId = node.documentId,
            )
        }.also { cachedPages = it }
    }

    override suspend fun open(page: ReaderPage): InputStream {
        val known = pages().any { it.pageId == page.pageId && it.documentId == page.documentId }
        if (!known) throw FileNotFoundException("页面已不在当前章节中")
        return treeAccess.openInputStream(treeUri, page.documentId)
            ?: throw FileNotFoundException("无法读取页面「${page.displayName}」")
    }

    private fun com.lmreader.core.model.ChildNode.isSupportedImage(): Boolean {
        val extension = MimeTypes.extensionOf(name)
        if (extension != null && extension in MimeTypes.IMAGE_EXTENSIONS) return true
        val mime = mimeType?.lowercase(Locale.ROOT) ?: return false
        return mime.startsWith(MimeTypes.IMAGE_MIME_PREFIX)
    }
}
