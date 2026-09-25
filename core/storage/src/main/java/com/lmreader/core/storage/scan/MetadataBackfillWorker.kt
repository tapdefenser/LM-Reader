package com.lmreader.core.storage.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lmreader.core.index.ComicInfoParser
import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.index.SummaryBuilder
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.MangaMetadataUpdate
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MetadataRecord
import com.lmreader.core.model.MimeTypes
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.storage.access.TreeAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 补全阶段：为已发现漫画补封面与 ComicInfo 简介（开发文档 6.1 第 2 步、7）。
 *
 * 元数据优先级严格按开发文档 7.1：**首章 XML 优先，漫画顶层只作兜底**。不照搬
 * Mihon 把顶层 XML 复制回章节或创建 `.noxml` 的写入逻辑——本应用只读源。
 *
 * 本步覆盖范围（框架第 9 节已知限制）：只处理图片目录章节。归档内部成员清单与
 * PDF 栅格化属于"深入"阶段，因此归档/PDF 漫画在这里不设封面、不读内部 XML，
 * 卡片显示来源徽标而不是假装有封面。
 *
 * 并发纪律：开发文档 6.3 要求"用户阅读优先于后台缩略图和批量翻译"，所以补全
 * 由调度层按固定并发（默认 2）驱动，本类自身不做并发控制。
 */
class MetadataBackfillWorker(
    private val context: Context,
    private val treeAccess: TreeAccess,
    private val mangaRepository: MangaRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 补全一批漫画，返回真正写入新信息的数量。
     *
     * 单部失败不阻断其它作品（开发文档 3）：逐部捕获异常。授权失效的来源整体跳过，
     * 因为继续尝试只会为每部作品重复一次 SecurityException（验收 A07）。
     */
    suspend fun backfill(mangaIds: List<String>): Int = withContext(Dispatchers.IO) {
        var updated = 0
        val brokenSources = mutableSetOf<String>()
        for (mangaId in mangaIds) {
            val target = runCatching { mangaRepository.getBackfillTarget(mangaId) }.getOrNull() ?: continue
            if (target.sourcePermission == SourcePermissionState.LOST) continue
            if (target.sourceTreeUri in brokenSources) continue
            val changed = runCatching { backfillOne(mangaId) }
                .onFailure { brokenSources += target.sourceTreeUri }
                .getOrDefault(false)
            if (changed) updated++
        }
        updated
    }

    /** 补全单部漫画；返回是否写入了新信息（封面或 ComicInfo）。 */
    suspend fun backfillOne(mangaId: String): Boolean {
        val target = mangaRepository.getBackfillTarget(mangaId) ?: return false
        val treeUri = Uri.parse(target.sourceTreeUri)
        val anchor = openDocument(treeUri, target.manga.anchorDocumentId) ?: return false

        // 封面：自然序第一章的第一页（开发文档 7.2）。必须按排序取，
        // 不能用目录枚举碰巧返回的第一项（验收 A12）。
        var coverDocumentId: String? = null
        var coverChapterId: String? = null
        val firstChapter = target.chapters.firstOrNull()
        if (firstChapter != null && firstChapter.kind == ChapterKind.IMAGE_DIRECTORY) {
            val chapterTree = openDocument(treeUri, firstChapter.documentId)
            val firstImage = chapterTree?.firstImageInNaturalOrder()
            if (firstImage != null) {
                coverDocumentId = firstImage.documentId
                coverChapterId = firstChapter.chapterId
                mangaRepository.applyMetadataUpdate(
                    MangaMetadataUpdate(
                        mangaId = mangaId,
                        coverDocumentId = coverDocumentId,
                        coverChapterId = coverChapterId,
                        searchText = "",
                        at = clock(),
                    ),
                )
            }
        }

        // ComicInfo：首章优先，顶层兜底（开发文档 7.1 第 1–3 条）。
        val records = mutableListOf<MetadataRecord>()
        var chapterRecord: MetadataRecord? = null
        if (firstChapter != null && firstChapter.kind == ChapterKind.IMAGE_DIRECTORY) {
            val xml = readComicInfo(treeUri, firstChapter.documentId)
            if (xml != null) {
                chapterRecord = ComicInfoParser.parse(
                    ownerId = firstChapter.chapterId,
                    ownerType = MetadataOwnerType.CHAPTER,
                    xml = xml,
                    sourceLabel = "第一章 ComicInfo.xml",
                )
                records += chapterRecord
            }
        }

        // 顶层兜底只在首章没有 XML 时才读取：两处都读会给出两个互相矛盾的简介来源。
        if (chapterRecord == null) {
            val xml = readComicInfo(treeUri, target.manga.anchorDocumentId)
            if (xml != null) {
                records += ComicInfoParser.parse(
                    ownerId = mangaId,
                    ownerType = MetadataOwnerType.MANGA,
                    xml = xml,
                    sourceLabel = "漫画目录顶层 ComicInfo.xml（首章无 XML 时的兜底，开发文档 7.1 第 3 条）",
                )
            }
        }

        if (records.isEmpty()) {
            // 没有 XML：明确记为"无简介"，但不清空已有简介（applyMetadataUpdate 的
            // null 语义就是不覆盖）。
            return coverDocumentId != null
        }

        val primary = chapterRecord ?: records.first()
        val author = primary.writer ?: primary.fields["Penciller"]
        mangaRepository.applyMetadataUpdate(
            MangaMetadataUpdate(
                mangaId = mangaId,
                coverDocumentId = coverDocumentId,
                coverChapterId = coverChapterId,
                summary = SummaryBuilder.build(primary),
                author = author,
                hasMetadata = true,
                records = records,
                // 搜索投影：名称与别名由查询侧另行拼接（本步 search 已同时匹配
                // displayName），这里只负责 XML 侧文本（开发文档 6.4）。
                searchText = primary.normalizedSearchText,
                at = clock(),
            ),
        )
        return true
    }

    /**
     * 打开一个 documentId 对应的目录；失败返回 null，由调用方当作"本次没读到"。
     *
     * 直接按 documentId 打开而不是从根遍历：补全只针对已发现条目，逐层遍历会把
     * 成本变成整树扫描（开发文档 6.1）。
     */
    private fun openDocument(treeUri: Uri, documentId: String): ContentTree? =
        treeAccess.openAt(treeUri.toString(), documentId)

    /**
     * 读目录下的 ComicInfo.xml 原文。
     *
     * 大小上限在读取时生效（开发文档 7.1「设置 2MiB 默认上限」）：先读满上限，
     * 解析器再对超限内容给出 parseError，原文仍然保留可展示。
     */
    private fun readComicInfo(treeUri: Uri, containerDocumentId: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, containerDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        var targetDocumentId: String? = null
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (ComicInfoParser.isComicInfoFileName(name)) {
                    targetDocumentId = cursor.getString(idIndex)
                    break
                }
            }
        }
        val documentId = targetDocumentId ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(ComicInfoParser.MAX_BYTES.toInt() + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /** 目录内自然序第一张图片（开发文档 1.3「首字母排序」）。 */
    private suspend fun ContentTree.firstImageInNaturalOrder(): ChildNode? =
        listChildren()
            .filter { !it.isDirectory && it.isSupportedImage() }
            .minWithOrNull { left, right -> NaturalOrder.compare(left.name, right.name) }

    private fun ChildNode.isSupportedImage(): Boolean {
        val extension = MimeTypes.extensionOf(name)
        if (extension != null && extension in MimeTypes.IMAGE_EXTENSIONS) return true
        // 扩展名缺失时才用 MIME 兜底；两者都不认就不当作图片（开发文档 5.2）。
        return extension == null && mimeType?.startsWith(MimeTypes.IMAGE_MIME_PREFIX) == true
    }
}
