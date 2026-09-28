package com.lmreader.core.storage.cover

import com.lmreader.core.index.firstImageInNaturalOrder
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.CoverProbeTarget
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.ResolvedCover
import com.lmreader.core.storage.access.TreeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 取一部漫画的封面（开发文档 7.2：自然序第一章的第一页）。
 *
 * ## 为什么从"扫描期补全"搬到这里
 *
 * 原先封面在扫描的补全阶段算，两个后果都是用户直接看到的：
 * 1. 扫描要为每部漫画多开一次章节目录，扫描变慢；
 * 2. 补全每轮只处理 120 条，待补的几千条要刷十几次图库才轮得到——于是"图库的封面
 *    几乎全是破图"。
 *
 * 现在**扫描只写路径**，封面在图库/书架/搜索结果**滚动到可见时**才取，一次一批；
 * 取过一次（成功或失败）就由 `coverProbedAt` 记住，不再取第二次；只有详情页的
 * 「更新章节」会强制重取。
 *
 * ## 成本
 *
 * 一次封面 = 打开一个目录并列出子项（多章节下可能试到第 [MAX_CHAPTERS_TRIED] 个
 * 章节）。它只在卡片首次可见时发生一次，因此与"扫全库"不是同一个量级。
 *
 * ## 依赖为什么是一个函数
 *
 * 只需要"按树 URI + documentId 打开目录"这一件事。收窄成一个函数之后，本类不认识
 * `TreeAccess`（它是 Android 侧的具体实现），也就可以在纯 JVM 测试里用内存目录树
 * 覆盖"首章是归档 / 空目录 / 目录读不到"这些分支。
 *
 * ## 线程
 *
 * 内部整段切到 [Dispatchers.IO]：`listChildren()` 自己会切，但按 documentId 打开目录
 * 在直接文件访问下会做 `File.isDirectory/canRead`，那句是调用线程上的系统调用。
 * 调用方不必自己包 `withContext`。
 */
class CoverResolver(
    private val openTree: (treeUri: String, documentId: String) -> ContentTree?,
) {
    /** 生产装配用这个：封面只通过统一的树入口打开（有「全部文件访问」时走直接文件访问）。 */
    constructor(treeAccess: TreeAccess) : this(
        openTree = { treeUri, documentId -> treeAccess.openAt(treeUri, documentId) },
    )

    /**
     * 解析一部漫画的封面。
     *
     * @return 找到的封面；null = **探测完成但没有可用首图**（空目录、首章是归档、
     *   目录读不到、章节表里已经没有章节）。调用方必须把 null 也记成"已探测"，
     *   否则这些卡片每次滚动都会被重新枚举一遍。
     */
    suspend fun resolve(target: CoverProbeTarget): ResolvedCover? = withContext(Dispatchers.IO) {
        runCatching { resolveInner(target) }
            .getOrElse { error ->
                // 取消必须继续向上传播，否则"退出图库"会变成"继续在后台枚举目录"。
                if (error is CancellationException) throw error
                // 单张封面的任何失败都只是"这张暂时没有封面"，不该冒泡成列表错误。
                null
            }
    }

    private suspend fun resolveInner(target: CoverProbeTarget): ResolvedCover? {
        val candidates = candidateChapters(target)
        for (chapter in candidates) {
            val tree = openTree(target.sourceTreeUri, chapter.documentId) ?: continue
            val image = firstImageInNaturalOrder(tree.listChildren()) ?: continue
            return ResolvedCover(
                coverDocumentId = image.documentId,
                coverChapterId = chapter.chapterId,
            )
        }
        return null
    }

    /**
     * 按顺序尝试哪些目录。
     *
     * - 单章节来源：卡片本身就是一章，直接用它；
     * - 多章节：从**自然序第一章**开始，最多试 [MAX_CHAPTERS_TRIED] 个。
     *   为什么要往下试几个：第一章可能是归档（本步不解析归档内部成员，见
     *   `MetadataBackfillWorker` 的说明），而"这部漫画的第二章是图片目录"很常见；
     *   只试第一章会让这类作品永远没有封面；
     * - 一个都没有：返回空列表，调用方记成"探测过但没有"。
     */
    private fun candidateChapters(target: CoverProbeTarget): List<ChapterEntry> {
        val chapters = buildList {
            target.firstChapter?.let { add(it) }
        }
        if (target.layoutMode == LayoutMode.SINGLE_CHAPTER) {
            // 单章节：章节就是锚点目录本身；即使章节行缺失也还能按锚点试一次。
            return listOf(
                ChapterEntry(
                    chapterId = chapters.firstOrNull()?.chapterId.orEmpty(),
                    documentId = chapters.firstOrNull()?.documentId ?: target.anchorDocumentId,
                    kind = ChapterKind.IMAGE_DIRECTORY,
                ),
            )
        }
        return chapters
            .asSequence()
            .filter { it.kind == ChapterKind.IMAGE_DIRECTORY }
            .take(MAX_CHAPTERS_TRIED)
            .map { ChapterEntry(it.chapterId, it.documentId, it.kind) }
            .toList()
    }

    private data class ChapterEntry(
        val chapterId: String,
        val documentId: String,
        val kind: ChapterKind,
    )

    private companion object {
        /**
         * 最多往下试几个章节。
         *
         * 取 3 是成本与覆盖的折中：每多试一个就多一次目录枚举，而"前两章都是归档"的
         * 作品极少。真正的归档封面（读 zip 内部第一张图）属于 P2。
         */
        const val MAX_CHAPTERS_TRIED = 3
    }
}
