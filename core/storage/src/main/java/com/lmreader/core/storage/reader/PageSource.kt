package com.lmreader.core.storage.reader

import android.graphics.BitmapFactory
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

data class ReaderPage(
    val pageId: String,
    val ordinal: Int,
    val displayName: String,
    val documentId: String,
)

/**
 * 一张页面的像素尺寸。
 *
 * 为什么需要它：连续模式（条漫）要先把每一页按原图比例算成条带里的一段高度，才能
 * 正确定位滚动位置与恢复页码。没有尺寸就只能等图片解码完再改布局，长章节滚动时会
 * 持续跳动。
 *
 * 这**不是** `ReaderPage` 的字段，因为它需要一次真实的解码才能得到，而列页阶段
 * （发现有多少页）不应该解码任何图片——那会把"打开章节"变成"解码整章"。因此按需探测
 * 并单独缓存。
 */
data class PageGeometry(
    val widthPx: Int,
    val heightPx: Int,
) {
    /** 宽高比；宽为 0 时返回 1 以避免除零，调用方按"方形占位"处理即可。 */
    val aspectRatio: Float get() = if (widthPx <= 0) 1f else heightPx.toFloat() / widthPx

    /** 是否比视口更宽（横向溢出），用于"宽图自动放大"与平移判定。 */
    val isLandscape: Boolean get() = widthPx > heightPx
}

/** 所有章节类型的统一页源契约（开发文档 12、15.2）。 */
interface PageSource {
    suspend fun pages(): List<ReaderPage>

    /** 每次返回新流；调用方负责关闭。 */
    suspend fun open(page: ReaderPage): InputStream

    /**
     * 探测页面像素尺寸。
     *
     * 契约要点：
     * - 只读图像头部，**不解码像素**（实现应使用 `inJustDecodeBounds` 之类的机制）；
     * - 同一页多次调用应命中缓存，不得每次重新打开文件；
     * - 探测失败返回 null，调用方按"尺寸未知"降级（例如用视口高度占位），
     *   **不得**因为探测失败就判定页面不可读。
     *
     * 返回 null 与抛异常的分工：null 表示"这张图读不到尺寸"，是页级问题；
     * 抛 [IOException] 表示整个页源不可用（章节被移动、授权失效），是章级问题。
     */
    suspend fun probe(page: ReaderPage): PageGeometry?
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

    /**
     * 已知页 ID 的集合，供 [open] 做 O(1) 归属校验。
     *
     * 上一版每次 `open` 都走 `pages().any { ... }`：一页要打开两次（读尺寸 + 交给引擎），
     * 每翻一页就是两次线性扫描；章节越长越明显。
     */
    private var cachedPageIds: Set<String> = emptySet()

    /**
     * 尺寸缓存。
     *
     * 用 `ConcurrentHashMap` 而不是普通 map：`probe` 会被连续模式的多个可见项并发的
     * 调用（每个待布局的页面各一次），而页源实例是共享的。
     */
    private val geometryCache = ConcurrentHashMap<String, PageGeometry>()

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
        }.also {
            cachedPages = it
            cachedPageIds = it.mapTo(HashSet()) { page -> page.pageId }
        }
    }

    override suspend fun open(page: ReaderPage): InputStream {
        if (!cachedPageIds.contains(page.pageId)) throw FileNotFoundException("页面已不在当前章节中")
        return treeAccess.openInputStream(treeUri, page.documentId)
            ?: throw FileNotFoundException("无法读取页面「${page.displayName}」")
    }

    /**
     * 只解码图像头部读尺寸。
     *
     * 用 `inJustDecodeBounds` 而不是解码整张图：条漫打开时需要一次探测所有可见页的
     * 尺寸，如果每页都完整解码，打开章节就会变成解码整章。`inJustDecodeBounds` 只读
     * 头部，代价与图片大小基本无关。
     */
    override suspend fun probe(page: ReaderPage): PageGeometry? {
        geometryCache[page.pageId]?.let { return it }
        val stream = try {
            open(page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notFound: FileNotFoundException) {
            throw notFound
        } catch (failure: Exception) {
            // 单页读不到尺寸是页级问题，按"尺寸未知"降级而不是让整章打不开。
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                PageGeometry(bounds.outWidth, bounds.outHeight).also {
                    geometryCache[page.pageId] = it
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
    }

    private fun com.lmreader.core.model.ChildNode.isSupportedImage(): Boolean {
        val extension = MimeTypes.extensionOf(name)
        if (extension != null && extension in MimeTypes.IMAGE_EXTENSIONS) return true
        val mime = mimeType?.lowercase(Locale.ROOT) ?: return false
        return mime.startsWith(MimeTypes.IMAGE_MIME_PREFIX)
    }
}
