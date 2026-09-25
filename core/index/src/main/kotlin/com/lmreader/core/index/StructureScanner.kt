package com.lmreader.core.index

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MetadataCandidate
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MimeTypes
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.StableId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 结构扫描器。纯算法：只通过 [ContentTree] 读取目录，不认识 SAF/Room/Compose。
 *
 * 调用约定：每个来源同时只有一个扫描任务（开发文档 6.3）；本类不做并发控制，
 * 由调度方保证。事件必须边发现边发出，不能全部收集完再发（开发文档 6.1）。
 *
 * **工厂必须按次传入**：扫描器实例可以被多个来源复用（算法本身无状态），
 * 因此它不能持有某一个来源的 [TreeFactory]。构造参数只是默认值，
 * [scan] 的 `factory` 会覆盖它。留这个默认值是为了让单元测试可以写成
 * `StructureScanner(fakeFactory)`；生产代码必须显式传工厂——真机上曾因为
 * "扫描器持有占位工厂、调用方忘了传真实工厂"导致每个子目录都打不开却没有任何异常。
 */
class StructureScanner(
    private val defaultTreeFactory: TreeFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 扫描一棵已授权的内容树，并按发现顺序回调 [events]。
     *
     * @param factory 本次扫描使用的工厂（按该来源的授权树构造）；默认用构造时的工厂。
     * 返回的 [ScanSummary] 是本次运行的汇总；`completed = false` 表示被取消或
     * 存在 IO 失败，落库方据此禁止删除判定（开发文档 6.2）。
     */
    suspend fun scan(
        request: ScanRequest,
        root: ContentTree,
        factory: TreeFactory = defaultTreeFactory,
        events: suspend (ScanEvent) -> Unit,
    ): ScanSummary = ScanRun(request, root, factory, events, clock).execute()
}

/** 一次扫描的可变状态；扫描器本身无状态，同一实例可被多个来源复用。 */
private class ScanRun(
    private val request: ScanRequest,
    private val root: ContentTree,
    private val factory: TreeFactory,
    private val events: suspend (ScanEvent) -> Unit,
    private val clock: () -> Long,
) {
    private val fullyEnumerated = LinkedHashSet<String>()
    private val candidates = ArrayList<MetadataCandidate>()
    private val diagnostics = ArrayList<String>()
    private val failedPaths = LinkedHashSet<String>()
    private val visited = LinkedHashSet<String>()
    private var mangaCount = 0
    private var chapterCount = 0
    private var cancelled = false

    private val rootRef = DirRef(
        tree = root,
        documentId = request.rootDocumentId,
        name = root.rootName,
        path = request.displayPath.ifBlank { root.rootName },
    )

    suspend fun execute(): ScanSummary {
        try {
            when (request.sourceKind) {
                SourceKind.IMAGE_DIRECTORY -> when (request.layoutMode) {
                    LayoutMode.MULTI_CHAPTER -> scanImageManga(rootRef, depth = 0)
                    LayoutMode.SINGLE_CHAPTER -> scanImageSingle(rootRef, isRoot = true)
                }

                SourceKind.ARCHIVE_IMPORT -> when (request.layoutMode) {
                    LayoutMode.MULTI_CHAPTER -> scanArchiveManga(rootRef, depth = 0, isRoot = true)
                    LayoutMode.SINGLE_CHAPTER -> scanArchiveSingle(rootRef, isRoot = true)
                }
            }
        } catch (cancellation: CancellationException) {
            // 取消不向外抛：ScanSummary.completed 就是「不完整」的表达方式
            // （开发文档 6.2「取消不能把未完成结果标记为完整」）。工作已经在
            // 当前挂起点停止，不会再有新事件产生。
            cancelled = true
        }
        return ScanSummary(
            generation = request.generation,
            mangas = mangaCount,
            chapters = chapterCount,
            directoriesVisited = visited.size,
            diagnostics = diagnostics.toList(),
            failedPaths = failedPaths.toList(),
            completed = !cancelled && failedPaths.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- 图片目录

    /**
     * 图片目录 · 多章节（框架 4.3 第一段 + 本实现补的第二条停止规则）。
     *
     * 漫画判定：目录的直接子目录中至少有一个 [isLeafImageChapter]。一旦成立，
     * 这些子目录就是章节，**不再作为独立漫画卡片**。
     *
     * 关键补充（开发文档示例 `download/pixiv/网球王子/第一章/图片` → 漫画「网球王子」，
     * 不是「pixiv」）：**判定为漫画后不再往它的子目录继续寻找漫画**。已归属的章节
     * 目录是这部作品的内部结构，往里找只会把「第一章」这类章节目录当成新作品。
     * 但仍要检查**其它**直接子目录（下面按 `ownedChapters` 过滤后的循环），
     * 否则"某一章是单篇、同时更深处另有整套作品"会漏（开发文档 5.1、
     * 验收样例第 12 行）。
     *
     * depth 用于实现"未勾选子目录时只把根自身及根的直接子目录当作漫画候选"：
     * depth == 0 的那一层永远要进，再深才看 recursive。
     */
    private suspend fun scanImageManga(dir: DirRef, depth: Int) {
        val children = enumerate(dir) ?: return
        val childDirs = children.filter { it.isDirectory }
        val hasDirectImage = children.any { it.isSupportedImage() }

        if (hasDirectImage && childDirs.isNotEmpty()) {
            diagnose(dir.path, MESSAGE_MIXED)
        }

        val chapterDirs = childDirs.filter { isLeafImageChapter(dir, it) }
        if (chapterDirs.isNotEmpty()) {
            emitManga(
                anchor = dir,
                chapters = chapterDirs.map { ChapterSpec(it.documentId, it.name, ChapterKind.IMAGE_DIRECTORY) },
                anchorChildren = children,
            )
        } else if (depth == 0 && childDirs.isEmpty() && hasDirectImage) {
            // 根特例：授权根本身就是叶子图片目录，说明用户选中的是单章节本体
            // （开发文档 5.1 第 3 行）。只对根生效，不把任意深层叶子误认成独立漫画。
            diagnose(dir.path, MESSAGE_ROOT_LEAF)
            emitManga(
                anchor = dir,
                chapters = listOf(ChapterSpec(request.rootDocumentId, dir.name, ChapterKind.IMAGE_DIRECTORY)),
                anchorChildren = children,
            )
        } else if (depth == 0 && childDirs.isEmpty()) {
            diagnose(dir.path, MESSAGE_NO_IMAGE)
        }

        if (!request.recursive && depth > 0) return
        val ownedChapters = chapterDirs.mapTo(HashSet()) { it.documentId }
        for (child in childDirs) {
            if (child.documentId in ownedChapters) continue
            val sub = openChild(dir, child) ?: continue
            scanImageManga(sub, depth + 1)
        }
    }

    /**
     * 图片目录 · 单章节（框架 4.3 第二段）。
     *
     * 每个叶子图片目录是一本共 1 章的漫画。为了让 `作者/短篇/001.jpg` 得到
     * 「短篇」而不是「作者」（开发文档 5.3 第 7、13 行的示例结构），中间容器的
     * 判定是：**只要还有子目录就继续向下**——既有图片又有子目录的目录既不是
     * 章节也不是漫画，它只是包裹目录，发诊断后继续递归（开发文档 5.1）。
     *
     * "根自身是叶子图片目录"因此自然覆盖：根没有子目录且直接含图片时，
     * 它自己就是唯一章节（开发文档 5.1 根特例），不需要额外分支。
     */
    private suspend fun scanImageSingle(dir: DirRef, isRoot: Boolean) {
        val children = enumerate(dir) ?: return
        val childDirs = children.filter { it.isDirectory }
        val hasDirectImage = children.any { it.isSupportedImage() }

        if (childDirs.isEmpty()) {
            when {
                hasDirectImage -> emitManga(
                    anchor = dir,
                    chapters = listOf(ChapterSpec(dir.documentId, dir.name, ChapterKind.IMAGE_DIRECTORY)),
                    anchorChildren = children,
                )

                isRoot -> diagnose(dir.path, MESSAGE_NO_IMAGE)
            }
            return
        }

        if (hasDirectImage) diagnose(dir.path, MESSAGE_MIXED)
        // 未勾选子目录时只把根自身及根的直接子目录当作候选；更深的叶子目录属于
        // "候选内部的章节层"，不越过递归开关（开发文档 5.1）。
        if (!request.recursive && !isRoot) return
        for (child in childDirs) {
            val sub = openChild(dir, child) ?: continue
            scanImageSingle(sub, isRoot = false)
        }
    }

    // ---------------------------------------------------------------- 归档/PDF

    /** 归档/PDF · 多章节：与图片多章节同构，判定换成 [isArchiveFile]（框架 4.3）。 */
    private suspend fun scanArchiveManga(dir: DirRef, depth: Int, isRoot: Boolean) {
        val children = enumerate(dir) ?: return
        val archives = children.filter { it.isArchiveFile() }

        if (archives.isNotEmpty()) {
            emitManga(
                anchor = dir,
                chapters = archives.map {
                    ChapterSpec(
                        documentId = it.documentId,
                        title = MimeTypes.nameWithoutExtension(it.name),
                        kind = ChapterKind.ARCHIVE,
                    )
                },
                anchorChildren = children,
            )
        } else if (isRoot && children.isEmpty()) {
            diagnose(dir.path, MESSAGE_NO_ARCHIVE)
        }

        if (!request.recursive && depth > 0) return
        // 嵌套目录独立按递归开关检查，不混入父作品（开发文档 5.2）。
        for (child in children.filter { it.isDirectory }) {
            val sub = openChild(dir, child) ?: continue
            scanArchiveManga(sub, depth + 1, isRoot = false)
        }
    }

    /**
     * 归档/PDF · 单章节：每个归档文件自身是一本共 1 章的漫画，章节标题为
     * 去扩展名的文件名（开发文档 1.3「归档单章节」）；recursive 控制是否继续
     * 向下遍历子目录（框架 4.3）。
     */
    private suspend fun scanArchiveSingle(dir: DirRef, isRoot: Boolean) {
        val children = enumerate(dir) ?: return

        for (file in children.filter { it.isArchiveFile() }) {
            val title = MimeTypes.nameWithoutExtension(file.name)
            emitManga(
                // 单章归档的漫画锚点就是归档文件本身：卡片与章节共用同一物理身份。
                anchor = DirRef(dir.tree, file.documentId, title, "${dir.path}/${file.name}"),
                chapters = listOf(ChapterSpec(file.documentId, title, ChapterKind.ARCHIVE)),
                anchorChildren = emptyList(),
            )
        }

        if (isRoot && children.isEmpty()) diagnose(dir.path, MESSAGE_NO_ARCHIVE)
        if (!request.recursive && !isRoot) return
        for (child in children.filter { it.isDirectory }) {
            val sub = openChild(dir, child) ?: continue
            scanArchiveSingle(sub, isRoot = false)
        }
    }

    // ---------------------------------------------------------------- 基础设施

    /**
     * 记录"正在枚举"的目录并发出进度事件。
     *
     * 每次都发（不只是首次访问）：界面要显示"正在扫描：<当前目录>"，
     * 只在首次访问时发会让这一行停在上一个目录上不动
     * （用户要求：扫描中显示正在扫描的路径）。
     */
    private suspend fun markVisited(documentId: String, path: String?) {
        currentCoroutineContext().ensureActive()
        visited += documentId
        events(
            ScanEvent.Progress(
                directoriesVisited = visited.size,
                mangasDiscovered = mangaCount,
                chaptersDiscovered = chapterCount,
                currentPath = path,
            ),
        )
    }

    /**
     * 完整枚举直接子项。成功即记入 [fullyEnumerated]（开发文档 6.2 的删除判定前提），
     * 失败只影响该分支并让 `completed = false`，不阻断其它作品（验收 A07）。
     */
    private suspend fun enumerate(dir: DirRef): List<ChildNode>? {
        markVisited(dir.documentId, dir.path)
        return try {
            dir.tree.listChildren().also { fullyEnumerated += dir.documentId }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(dir.path, error)
            null
        }
    }

    private suspend fun openChild(parent: DirRef, child: ChildNode): DirRef? {
        val path = "${parent.path}/${child.name}"
        markVisited(child.documentId, path)
        return try {
            val tree = factory.open(child)
            if (tree == null) {
                fail(path, null)
                null
            } else {
                DirRef(tree, child.documentId, child.name, path)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(path, error)
            null
        }
    }

    /**
     * leafChapter(d)：直接子项中至少有一张受支持图片，且没有子目录（开发文档 5.1）。
     *
     * 判定顺序是「先确认没有子目录，再确认有图片」（框架 4.3）：先看到图片不能
     * 断言没有子目录（验收 A04）。这里用 [ContentTree.hasDirectoryChildren] 提前
     * 短路，避免为判定叶子而枚举整棵子树。
     */
    private suspend fun isLeafImageChapter(parent: DirRef, child: ChildNode): Boolean {
        val ref = openChild(parent, child) ?: return false
        return try {
            if (ref.tree.hasDirectoryChildren()) false else ref.tree.hasImageChild()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail("${parent.path}/${child.name}", error)
            false
        }
    }

    private suspend fun diagnose(path: String, message: String) {
        diagnostics += "$path：$message"
        events(ScanEvent.Diagnostic(path, message))
    }

    private suspend fun fail(path: String, error: Exception?) {
        val message = error?.let { failure ->
            // 带上异常类型：`SecurityException: Permission Denial…` 与
            // `FileNotFoundException: …` 需要完全不同的处理，只留 message
            // 会把两者显示成同一句话，真机排障时无法区分。
            val text = failure.message?.takeIf { it.isNotBlank() } ?: "无附加信息"
            "${failure::class.simpleName}：$text"
        } ?: "无法打开该目录"
        failedPaths += path
        events(ScanEvent.Failed(path, message))
    }

    /**
     * 发出一条漫画发现事件，并立刻把当前累计的 [ScanResult] 交给调用方。
     *
     * `fullyEnumeratedContainers` 取「此刻已完整枚举」的快照：每次事件里的锚点目录
     * 一定包含在内（章节只可能是锚点的直接子项），因此章节删除判定不会因为
     * 分批发事件而失去依据。
     */
    private suspend fun emitManga(
        anchor: DirRef,
        chapters: List<ChapterSpec>,
        anchorChildren: List<ChildNode>,
    ) {
        val now = clock()
        val mangaId = StableId.mangaId(anchor.documentId, request.sourceKind)
        val chapterRecords = chapters.map { spec ->
            ChapterRecord(
                chapterId = StableId.chapterId(spec.documentId, spec.kind),
                mangaId = mangaId,
                documentId = spec.documentId,
                kind = spec.kind,
                title = spec.title,
                sortKey = NaturalOrder.sortKey(spec.title),
                // 页清单在「深入」阶段才建立（开发文档 6.1），此处不假装已知。
                pageCount = null,
                // 封面由补全阶段填（框架 6.3 / 开发文档 7.2），发现阶段不解码图片。
                coverDocumentId = null,
                contentRevision = INITIAL_CONTENT_REVISION,
                discoveredAt = now,
            )
        }.sortedWith(CHAPTER_ORDER)

        collectMetadataCandidates(anchor, chapters, anchorChildren)

        val manga = MangaRecord(
            mangaId = mangaId,
            anchorDocumentId = anchor.documentId,
            // 重叠授权时由调用方保证 request.sourceId 已是 effectiveSourceId（开发文档 6.4）。
            sourceId = request.sourceId,
            sourceKind = request.sourceKind,
            layoutMode = request.layoutMode,
            displayName = anchor.name,
            author = null,
            hasMetadata = false,
            summary = null,
            coverDocumentId = null,
            coverChapterId = null,
            // 章节候选已经全部枚举完才发事件，所以这里是确定值而不是「≥1」。
            chapterCount = chapterRecords.size,
            chapterCountKnown = true,
            availability = MangaAvailability.AVAILABLE,
            discoveryGeneration = request.generation,
            discoveredAt = now,
            updatedAt = now,
        )

        val result = ScanResult(
            sourceId = request.sourceId,
            sourceKind = request.sourceKind,
            generation = request.generation,
            manga = manga,
            chapters = chapterRecords,
            fullyEnumeratedContainers = fullyEnumerated.toSet(),
            metadataCandidates = candidates.toList(),
        )
        mangaCount++
        chapterCount += chapterRecords.size
        events(ScanEvent.MangaDiscovered(result = result, totalDiscovered = mangaCount))
    }

    /**
     * 登记元数据候选位置（框架 4.1）。
     *
     * 发现阶段只登记**已经看到**的位置，不打开归档：归档页清单是「深入」阶段的事
     * （开发文档 6.1），打开每个 CBZ 去猜 ComicInfo 会把发现阶段变成解压阶段。
     */
    private fun collectMetadataCandidates(
        anchor: DirRef,
        chapters: List<ChapterSpec>,
        anchorChildren: List<ChildNode>,
    ) {
        for (spec in chapters) {
            if (spec.kind != ChapterKind.ARCHIVE) continue
            candidates += MetadataCandidate(
                ownerDocumentId = spec.documentId,
                ownerType = MetadataOwnerType.CHAPTER,
                archiveMemberPath = null,
                label = "${spec.title}：归档内 ComicInfo.xml（补全阶段打开归档后定位成员路径）",
            )
        }

        val comicInfo = anchorChildren.firstOrNull {
            !it.isDirectory && ComicInfoParser.isComicInfoFileName(it.name)
        } ?: return

        val selfChapter = chapters.singleOrNull()?.takeIf { it.documentId == anchor.documentId }
        candidates += if (selfChapter != null) {
            // 单章漫画：目录里的 ComicInfo.xml 就是这一章自己的元数据（开发文档 7.1 第 1 条）。
            MetadataCandidate(
                ownerDocumentId = selfChapter.documentId,
                ownerType = MetadataOwnerType.CHAPTER,
                archiveMemberPath = null,
                label = "${selfChapter.title} ComicInfo.xml",
            )
        } else {
            // 多章漫画：顶层 ComicInfo.xml 只在第一章没有 XML 时兜底（开发文档 7.1 第 3 条）。
            MetadataCandidate(
                ownerDocumentId = anchor.documentId,
                ownerType = MetadataOwnerType.MANGA,
                archiveMemberPath = null,
                label = "漫画顶层兜底",
            )
        }
    }

    /** 目录引用：树 + 稳定身份 + 展示名 + 诊断用路径。 */
    private class DirRef(
        val tree: ContentTree,
        val documentId: String,
        val name: String,
        val path: String,
    )

    private data class ChapterSpec(
        val documentId: String,
        val title: String,
        val kind: ChapterKind,
    )

    private companion object {
        /** 章节顺序按自然序（框架 4.3「排序」）；标题相同时用 documentId 保证确定性。 */
        val CHAPTER_ORDER = Comparator<ChapterRecord> { left, right ->
            val byTitle = NaturalOrder.compare(left.title, right.title)
            if (byTitle != 0) byTitle else left.documentId.compareTo(right.documentId)
        }

        const val INITIAL_CONTENT_REVISION = 1L

        const val MESSAGE_MIXED = "目录同时包含图片和子目录，未按叶子章节处理（开发文档 5.1）"
        const val MESSAGE_ROOT_LEAF = "根目录自身是叶子图片目录，按根特例生成共 1 章的漫画（开发文档 5.1）"
        const val MESSAGE_NO_IMAGE = "目录内没有受支持的图片，未生成卡片（开发文档 5.1）"
        const val MESSAGE_NO_ARCHIVE = "目录内没有可导入的 CBZ/ZIP/PDF（开发文档 5.2）"
    }
}
