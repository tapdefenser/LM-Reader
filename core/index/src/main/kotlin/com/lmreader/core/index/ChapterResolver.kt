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
 *
 * ## 顺手把页数也数了（零额外 IO）
 *
 * 判断"这个子目录是不是叶子图片目录"本来就要 `listChildren()` 一次，那份列表**已经
 * 在手里**，数其中有多少张受支持图片不额外读盘。用户口径："更新章节的同时应该要数
 * 每章页数，因为这个时候要获取完整的表准备给翻译用了。"于是完整清单落地时，
 * 每章的 `pageCount` 也是准的，而不是等到用户逐章打开才慢慢补。
 *
 * 归档章节例外：页数要打开压缩包才知道（P2 的缺口），因此保持原值不动。
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
        val candidates = ArrayList<Candidate>()
        anchorChildren.filter { it.isArchiveFile() }
            .forEach { candidates += Candidate(it, ChapterKind.ARCHIVE, pageCount = null) }

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
            if (isLeafImageChapter) {
                // 页数就用这份已经读到的列表数：叶子判定保证它是"只有图片的目录"，
                // 因此图片数就是页数，与阅读器 `PageSource` 的过滤口径一致。
                candidates += Candidate(
                    node = child,
                    kind = ChapterKind.IMAGE_DIRECTORY,
                    pageCount = children.count { it.isSupportedImage() },
                )
            }
        }

        if (candidates.isEmpty()) {
            return ChapterResolution.Failure("漫画目录内未发现可读章节")
        }

        val now = clock()
        val chapters = candidates.map { candidate ->
            val node = candidate.node
            val kind = candidate.kind
            val existing = existingByDocumentId[node.documentId]
            val title = if (kind == ChapterKind.ARCHIVE) node.name.withoutExtension() else node.name
            ChapterRecord(
                chapterId = StableId.chapterId(node.documentId, kind),
                mangaId = manga.mangaId,
                documentId = node.documentId,
                kind = kind,
                title = title,
                sortKey = NaturalOrder.sortKey(title),
                // 本次数出来的页数优先；归档数不出来时保留上次的值。
                pageCount = candidate.pageCount ?: existing?.pageCount,
                coverDocumentId = existing?.coverDocumentId,
                modifiedAt = node.lastModified ?: existing?.modifiedAt,
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

    /** 一个章节候选：节点、类型，以及**顺手数出来的页数**（归档为 null）。 */
    private data class Candidate(
        val node: ChildNode,
        val kind: ChapterKind,
        val pageCount: Int?,
    )

    private fun String.withoutExtension(): String = substringBeforeLast('.', this)

    private companion object {
        const val INITIAL_CONTENT_REVISION = 1L
    }
}
