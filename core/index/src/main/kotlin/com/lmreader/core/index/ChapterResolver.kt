package com.lmreader.core.index

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.StableId
import kotlinx.coroutines.CancellationException

/** 单漫画章节完整枚举结果（框架 6.3A）。 */
sealed interface ChapterResolution {
    data class Success(val chapters: List<ChapterRecord>) : ChapterResolution
    data class Failure(val reason: String) : ChapterResolution
}

/**
 * 深入枚举一部多章节漫画的直接章节。
 *
 * 与 [StructureScanner] 的快速发现不同，本类只有在锚点和所有直接子目录都枚举
 * 成功时才返回 [ChapterResolution.Success]。这是落库时允许删除消失章节的前提。
 */
class ChapterResolver(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(
        manga: MangaRecord,
        existingChapters: List<ChapterRecord>,
        anchor: ContentTree,
        factory: TreeFactory,
    ): ChapterResolution {
        val anchorChildren = try {
            anchor.listChildren()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ChapterResolution.Failure(
                "无法读取漫画目录：${error.message ?: error::class.simpleName}",
            )
        }

        val existingByDocumentId = existingChapters.associateBy { it.documentId }
        val candidates = ArrayList<Pair<ChildNode, ChapterKind>>()
        anchorChildren.filter { it.isArchiveFile() }.forEach { candidates += it to ChapterKind.ARCHIVE }

        for (child in anchorChildren.filter { it.isDirectory }) {
            val childTree = try {
                factory.open(child)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return ChapterResolution.Failure(
                    "无法打开子目录「${child.name}」：${error.message ?: error::class.simpleName}",
                )
            } ?: return ChapterResolution.Failure("无法打开子目录「${child.name}」")

            val children = try {
                childTree.listChildren()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return ChapterResolution.Failure(
                    "无法读取子目录「${child.name}」：${error.message ?: error::class.simpleName}",
                )
            }
            val isLeafImageChapter = children.none { it.isDirectory } &&
                children.none { it.isArchiveFile() } &&
                children.any { it.isSupportedImage() }
            if (isLeafImageChapter) candidates += child to ChapterKind.IMAGE_DIRECTORY
        }

        if (candidates.isEmpty()) {
            return ChapterResolution.Failure("漫画目录内未发现可读章节")
        }

        val now = clock()
        val chapters = candidates.map { (node, kind) ->
            val existing = existingByDocumentId[node.documentId]
            val title = if (kind == ChapterKind.ARCHIVE) node.name.withoutExtension() else node.name
            ChapterRecord(
                chapterId = StableId.chapterId(node.documentId, kind),
                mangaId = manga.mangaId,
                documentId = node.documentId,
                kind = kind,
                title = title,
                sortKey = NaturalOrder.sortKey(title),
                pageCount = existing?.pageCount,
                coverDocumentId = existing?.coverDocumentId,
                contentRevision = node.lastModified ?: existing?.contentRevision ?: INITIAL_CONTENT_REVISION,
                discoveredAt = existing?.discoveredAt ?: now,
            )
        }.sortedWith { left, right ->
            NaturalOrder.compare(left.title, right.title)
                .takeIf { it != 0 }
                ?: left.documentId.compareTo(right.documentId)
        }
        return ChapterResolution.Success(chapters)
    }

    private fun String.withoutExtension(): String = substringBeforeLast('.', this)

    private companion object {
        const val INITIAL_CONTENT_REVISION = 1L
    }
}
