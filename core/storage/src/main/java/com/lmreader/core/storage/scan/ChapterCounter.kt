package com.lmreader.core.storage.scan

import com.lmreader.core.index.isArchiveFile
import com.lmreader.core.index.isSupportedImage
import com.lmreader.core.model.ChapterCountProbeTarget
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.storage.access.TreeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 滚动/搜索时的**章节计数**（用户口径："滚动+搜索结果时加载，不要做成扫描时加载"）。
 *
 * ## 为什么不是"跑一遍完整同步"
 *
 * 完整同步（[MangaChapterSyncer]）会枚举并落库整份章节清单，成本是"每个章节子目录都要
 * 打开一次确认它是叶子图片目录"：763 章的漫画就是 764 次目录列举。滚动时不能这么干。
 * 这里**只列锚点目录一次**，数直接子项。数出来的只是**数量**，不写章节清单，因此
 * 也绝不打开 `chapterCountKnown` 那道"可以删掉多余章节行"的闸门
 * （见 `MangaRepositoryImpl` 里 `incomingIsComplete` 的说明）。
 *
 * ## 计数口径（与全量同步一致，而不是"子目录 + 压缩包"简单相加）
 *
 * 全量同步（`StructureScanner.scanManga` / `ChapterResolver.resolve`）的判定顺序是：
 *
 * 1. **目录里有归档文件** → 这些归档就是全部章节，子目录一律跳过；
 * 2. 否则子目录——发现阶段只取自然序第一个（所以 `chapterCount` 长期是下限 1），
 *    完整同步会把所有**叶子图片目录**都算上；
 * 3. 目录里既没有归档也没有子目录、但直接含图片 → 根目录自己就是一章。
 *
 * 这里按同一顺序数。只有"归档与子目录混放"这一种形态与全量同步不同，而那种形态
 * 扫描器本来就会发诊断（`StructureScanner` 的 `MESSAGE_MIXED`），属已知不可靠布局。
 *
 * ## 已知偏差
 *
 * 只列一层、不打开子目录，因此**嵌套分组目录**（`Vol.1/Ch.1` 这种）会被多算成"卷数"。
 * 这是为省掉 N 次目录列举付的代价，换来的是一次 `listChildren()`。用户点过
 * 「更新章节」之后 `chapterCountKnown = true` 的准确值会优先显示（见
 * `ui/common/MangaCardItem.chapterLabel`）。
 *
 * ## 依赖为什么是一个函数
 *
 * 与 `CoverResolver` 同形：只需要"按树 URI + documentId 打开目录"，收窄成一个函数
 * 之后本类不认识 `TreeAccess`（Android 侧实现），可以在纯 JVM 测试里用内存目录树
 * 覆盖"归档 / 嵌套 / 空目录 / 读不到"这些分支。
 */
class ChapterCounter(
    private val openTree: (treeUri: String, documentId: String) -> ContentTree?,
) {

    /** 生产装配用这个：只通过统一的树入口打开（有「全部文件访问」时走直接文件访问）。 */
    constructor(treeAccess: TreeAccess) : this(
        openTree = { treeUri, documentId -> treeAccess.openAt(treeUri, documentId) },
    )

    /**
     * 数一部漫画的章节。
     *
     * @return 章节数；null = **探测完成但没有可读章节**（空目录、目录读不到）。调用方
     *   必须把 null 也记成"已探测"，否则这些卡片每次滚动都会被重新列一遍目录。
     */
    suspend fun count(target: ChapterCountProbeTarget): Int? = withContext(Dispatchers.IO) {
        runCatching { countInner(target) }
            .getOrElse { error ->
                // 取消必须继续向上传播，否则"退出图库"会变成"继续在后台枚举目录"。
                if (error is CancellationException) throw error
                // 单张卡片的失败只是"这一步数不出来"，不该冒泡成列表错误。
                null
            }
    }

    private suspend fun countInner(target: ChapterCountProbeTarget): Int? {
        // 单章节模式的"1 章"是结构定义，不需要任何 IO——与 `MangaChapterSyncer` 同口径
        // （它对这个模式也直接返回，不列目录）。
        if (target.layoutMode == LayoutMode.SINGLE_CHAPTER) return 1

        val anchor = openTree(target.sourceTreeUri, target.anchorDocumentId) ?: return null
        return countIn(anchor.listChildren())
    }

    private fun countIn(children: List<ChildNode>): Int? {
        val archives = children.count { it.isArchiveFile() }
        if (archives > 0) return archives
        val directories = children.count { it.isDirectory }
        if (directories > 0) return directories
        return if (children.any { it.isSupportedImage() }) 1 else null
    }
}
