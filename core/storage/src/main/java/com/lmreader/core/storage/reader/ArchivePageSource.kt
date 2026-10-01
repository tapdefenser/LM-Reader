package com.lmreader.core.storage.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MimeTypes
import com.lmreader.core.model.StableId
import com.lmreader.core.storage.access.TreeAccess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.math.sqrt

/** ZIP/CBZ members are pages; only safe image paths are exposed to the reader. */
internal class ZipChapterPageSource(
    private val access: TreeAccess,
    private val treeUri: String,
    private val chapter: ChapterRecord,
    private val cacheRoot: File?,
) : PageSource {
    private var cachedPages: List<ReaderPage>? = null
    private var namesByPageId: Map<String, String> = emptyMap()

    override suspend fun pages(): List<ReaderPage> {
        cachedPages?.let { return it }
        val entries = mutableListOf<ZipEntry>()
        ZipFile(localFile()).use { zip ->
            val members = zip.entries()
            var totalUncompressed = 0L
            var count = 0
            val seen = HashSet<String>()
            while (members.hasMoreElements()) {
                val entry = members.nextElement()
                count++
                if (count > MAX_ENTRIES) throw IOException("压缩包条目过多")
                if (entry.isDirectory || !safeImagePath(entry.name)) continue
                if (entry.size < 0 || entry.size > MAX_PAGE_BYTES) throw IOException("压缩包图片大小未知或超过上限")
                totalUncompressed += entry.size
                if (totalUncompressed > MAX_TOTAL_BYTES) throw IOException("压缩包解压体积超过上限")
                if (!seen.add(entry.name)) throw IOException("压缩包包含重复图片路径")
                entries += entry
            }
        }
        if (entries.isEmpty()) throw IOException("压缩包内没有受支持的图片")
        val result = entries.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .mapIndexed { index, entry ->
                ReaderPage(StableId.pageId(chapter.chapterId, entry.name), index,
                    entry.name.substringAfterLast('/'), entry.name)
            }
        cachedPages = result
        namesByPageId = result.associate { it.pageId to it.documentId }
        return result
    }

    override suspend fun open(page: ReaderPage): InputStream {
        if (namesByPageId[page.pageId] != page.documentId) throw FileNotFoundException("页面不属于当前压缩包")
        val zip = ZipFile(localFile())
        return try {
            val entry = zip.getEntry(page.documentId) ?: throw FileNotFoundException("压缩包图片已不存在")
            require(!entry.isDirectory && safeImagePath(entry.name) && entry.size in 0..MAX_PAGE_BYTES)
            object : FilterInputStream(zip.getInputStream(entry)) {
                private var bytes = 0L
                override fun read(): Int {
                    val value = super.read()
                    if (value >= 0) checkLimit(1)
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0) checkLimit(count)
                    return count
                }
                private fun checkLimit(count: Int) {
                    bytes += count
                    if (bytes > MAX_PAGE_BYTES) throw IOException("压缩包图片超过 64 MB 上限")
                }
                override fun close() { try { super.close() } finally { zip.close() } }
            }
        } catch (failure: Throwable) { zip.close(); throw failure }
    }

    override suspend fun probe(page: ReaderPage): PageGeometry? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(page).use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) PageGeometry(bounds.outWidth, bounds.outHeight) else null
    }

    private fun localFile(): File = synchronized(STAGE_LOCK) {
        val direct = File(chapter.documentId)
        if (direct.isAbsolute && direct.isFile && direct.canRead()) return direct
        val root = cacheRoot ?: File(System.getProperty("java.io.tmpdir"), "lmreader-chapter-archives")
        check(root.isDirectory || root.mkdirs()) { "无法创建归档缓存目录" }
        val identity = "$treeUri|${chapter.documentId}|${chapter.contentRevision}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cached = File(root, "$digest.zip")
        if (cached.isFile && cached.length() > 0) { cached.setLastModified(System.currentTimeMillis()); return cached }
        val part = File(root, "$digest.part")
        try {
            val source = access.openInputStream(treeUri, chapter.documentId)
                ?: throw FileNotFoundException("无法读取压缩包")
            source.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_ARCHIVE_BYTES) throw IOException("压缩包超过 512 MB 缓存上限")
                    output.write(buffer, 0, count)
                }
            } }
            check(part.renameTo(cached)) { "无法保存归档缓存" }
            root.listFiles().orEmpty().filter { it != cached && it.lastModified() < System.currentTimeMillis() - CACHE_AGE_MS }
                .forEach { it.delete() }
            return cached
        } finally { part.delete() }
    }

    private fun safeImagePath(name: String): Boolean {
        val path = name.replace('\\', '/')
        if (path.startsWith('/') || path.contains('\u0000') || path.length > 1024) return false
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        return MimeTypes.extensionOf(path) in MimeTypes.IMAGE_EXTENSIONS
    }

    private companion object {
        val STAGE_LOCK = Any()
        const val MAX_ENTRIES = 10_000
        const val MAX_PAGE_BYTES = 64_000_000L
        const val MAX_TOTAL_BYTES = 1_000_000_000L
        const val MAX_ARCHIVE_BYTES = 512_000_000L
        const val CACHE_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/** Android's PdfRenderer makes each PDF page available through the common page contract. */
internal class PdfChapterPageSource(
    private val access: TreeAccess,
    private val treeUri: String,
    private val chapter: ChapterRecord,
) : PageSource {
    private var cachedPages: List<ReaderPage>? = null

    override suspend fun pages(): List<ReaderPage> {
        cachedPages?.let { return it }
        val count = withRenderer { renderer -> renderer.pageCount }
        if (count !in 1..MAX_PAGES) throw IOException("PDF 页数无效或超过上限")
        return (0 until count).map { index ->
            val marker = "pdf:$index"
            ReaderPage(StableId.pageId(chapter.chapterId, marker), index,
                "%05d.png".format(index + 1), marker)
        }.also { cachedPages = it }
    }

    override suspend fun open(page: ReaderPage): InputStream {
        requirePage(page)
        return withRenderer { renderer ->
            renderer.openPage(page.ordinal).use { pdfPage ->
                val size = pageSize(pdfPage.width, pdfPage.height)
                val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PDF 页面渲染失败" }
                        if (output.size() > 64_000_000) throw IOException("PDF 页面超过 64 MB 上限")
                        ByteArrayInputStream(output.toByteArray())
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    override suspend fun probe(page: ReaderPage): PageGeometry {
        requirePage(page)
        return withRenderer { renderer ->
            renderer.openPage(page.ordinal).use { pdfPage ->
                val size = pageSize(pdfPage.width, pdfPage.height)
                PageGeometry(size.first, size.second)
            }
        }
    }

    private suspend fun requirePage(page: ReaderPage) {
        if (pages().getOrNull(page.ordinal)?.pageId != page.pageId) throw FileNotFoundException("PDF 页面已不存在")
    }

    private fun pageSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) throw IOException("PDF 页面尺寸无效")
        val scale = min(2.0, sqrt(MAX_RENDER_PIXELS / (width.toDouble() * height)))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    private inline fun <T> withRenderer(block: (PdfRenderer) -> T): T {
        val descriptor = access.openFileDescriptor(treeUri, chapter.documentId)
            ?: throw FileNotFoundException("无法读取 PDF")
        return try { PdfRenderer(descriptor).use(block) } catch (failure: Throwable) {
            runCatching { descriptor.close() }; throw failure
        }
    }

    private companion object {
        const val MAX_PAGES = 5_000
        const val MAX_RENDER_PIXELS = 4_000_000.0
    }
}
