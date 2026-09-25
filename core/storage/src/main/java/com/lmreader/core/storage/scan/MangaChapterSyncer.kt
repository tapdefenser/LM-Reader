package com.lmreader.core.storage.scan

import com.lmreader.core.index.ChapterResolution
import com.lmreader.core.index.ChapterResolver
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ScanPersistReport
import com.lmreader.core.model.ScanResult
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.TreeAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 详情页单漫画章节同步结果。 */
sealed interface ChapterSyncOutcome {
    data class Success(
        val chapters: List<ChapterRecord>,
        val report: ScanPersistReport?,
    ) : ChapterSyncOutcome

    data class Failure(val reason: String) : ChapterSyncOutcome
}

/**
 * 完整枚举并原子替换一部漫画的章节索引（框架 6.3A）。
 *
 * 失败和取消都不落库；落库仍经过来源 revision 门禁，所以同步期间更换目录
 * 不会把旧结果写回新来源。
 */
class MangaChapterSyncer(
    private val treeAccess: TreeAccess,
    private val resolver: ChapterResolver,
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(mangaId: String): ChapterSyncOutcome = withContext(Dispatchers.IO) {
        val target = mangaRepository.getBackfillTarget(mangaId)
            ?: return@withContext ChapterSyncOutcome.Failure("漫画或来源已不存在")

        if (target.manga.layoutMode == LayoutMode.SINGLE_CHAPTER) {
            return@withContext ChapterSyncOutcome.Success(target.chapters, report = null)
        }
        if (target.sourcePermission == SourcePermissionState.LOST) {
            return@withContext ChapterSyncOutcome.Failure("目录授权已失效，请先在设置中重新授权")
        }

        val source = sourceRepository.getSource(target.manga.sourceId)
            ?: return@withContext ChapterSyncOutcome.Failure("来源已被删除")
        if (source.mode != target.manga.layoutMode || source.kind != target.manga.sourceKind) {
            return@withContext ChapterSyncOutcome.Failure("来源类型已变更，请先刷新图库再更新章节")
        }
        if (source.permission == SourcePermissionState.LOST) {
            return@withContext ChapterSyncOutcome.Failure("目录授权已失效，请先在设置中重新授权")
        }
        treeAccess.checkReadable(source.treeUri)?.let { reason ->
            return@withContext ChapterSyncOutcome.Failure(reason)
        }
        val anchor = treeAccess.openAt(source.treeUri, target.manga.anchorDocumentId)
            ?: return@withContext ChapterSyncOutcome.Failure("无法打开漫画目录，可能已被移动或删除")

        when (
            val resolution = resolver.resolve(
                manga = target.manga,
                existingChapters = target.chapters,
                anchor = anchor,
                factory = treeAccess.treeFactory(source.treeUri),
            )
        ) {
            is ChapterResolution.Failure -> ChapterSyncOutcome.Failure(resolution.reason)
            is ChapterResolution.Success -> {
                val now = clock()
                val report = mangaRepository.upsertScanResult(
                    ScanResult(
                        sourceId = source.sourceId,
                        sourceKind = source.kind,
                        sourceRevision = source.revision,
                        generation = target.manga.discoveryGeneration,
                        manga = target.manga.copy(
                            chapterCount = resolution.chapters.size,
                            chapterCountKnown = true,
                            updatedAt = now,
                        ),
                        chapters = resolution.chapters,
                        fullyEnumeratedContainers = setOf(target.manga.anchorDocumentId),
                        metadataCandidates = emptyList(),
                    ),
                )
                if (!report.accepted) {
                    ChapterSyncOutcome.Failure("同步期间来源配置已变更，请重试")
                } else {
                    ChapterSyncOutcome.Success(resolution.chapters, report)
                }
            }
        }
    }
}
