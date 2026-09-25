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

    /**
     * 调用 [isLeafImageChapter] 的次数，即"为了确认章节而打开并检查子目录"的次数。
     *
     * 它是"找到一个章节就跳过其余文件夹"这条规则的**可观测指标**：
     * 真机实测 762 部漫画时为 1823 次（每部约 2 次：一次探测作品目录本身不是章节，
     * 一次在其内部命中第一章）。这个数字显著大于漫画数就说明跳过规则没有生效，
     * 因此把它随扫描结果一起报出来，而不是只留在开发者的脑子里。
     */
    var leafProbes: Int = 0
        private set

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
                    LayoutMode.MULTI_CHAPTER -> scanArchiveManga(rootRef, depth = 0)
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
            leafChapterProbes = leafProbes,
        )
    }

    // ---------------------------------------------------------------- 图片目录

    /**
     * 图片目录 · 多章节。
     *
     * 判定与停止规则（用户明确要求，也是这个模式唯一必要的成本）：
     * 1. 逐个检查直接子目录，**找到一个** [isLeafImageChapter] 就成立——这个目录
     *    是一部漫画，那个子目录是它的章节；
     * 2. 一旦成立，**该目录下的其余子文件夹全部跳过**：它们要么是同一部作品的其它
     *    章节，要么是这部作品的附属内容，都不是新的漫画。因此每部作品最多只打开
     *    一个子目录，而不是枚举几百个章节文件夹。
     *
     * 第 1 条成立的前提是"章节"判得准。[isLeafImageChapter] 因此要求子目录
     * **既没有子目录、也没有 CBZ/ZIP/PDF**：真机 `/Tachiyomi/local` 里
     * `Jyminish  OOHS/`（`cover.jpg` + 两个章节 `.zip`，没有子目录）曾被当成章节，
     * 于是授权根被判成一部叫 `local` 的漫画、其余 41 个子文件夹全部没被检查。
     * 这类目录归「CBZ/ZIP/PDF 导入列表」管，不是图片章节。
     *
     * 反过来说：判定"这里不是漫画"必须把直接子目录都检查完（否则会把漫画误判成
     * 包裹目录，继续往下把「第一章」当成新作品）。
     *
     * 与开发文档的两点偏离都记录在此，避免以后被当成 bug 改回去：
     * - 锚点章节是"枚举顺序里第一个被确认为叶子的子目录"，不保证是自然序第一章。
     *   封面与简介由补全阶段按自然序重取（开发文档 7.2），因此不影响展示；
     * - 因此也不再深入"已判定为漫画"的目录去找更深的作品（开发文档 5.1 第 12 行
     *   的样例属于这种情况，本实现按用户要求以跳过换取性能）。
     *
     * depth 用于实现"未勾选子目录时只把根自身及根的直接子目录当作漫画候选"：
     * depth == 0 的那一层永远要进，再深才看 recursive。
     */
    private suspend fun scanImageManga(dir: DirRef, depth: Int) {
        val children = dir.enumerateOnce() ?: return
        val childDirs = children.filter { it.isDirectory }
        val hasDirectImage = children.any { it.isSupportedImage() }

        if (hasDirectImage && childDirs.isNotEmpty()) {
            diagnose(dir.path, MESSAGE_MIXED)
        }

        var isManga = false
        // 先按名称自然序排候选，再"找到第一个叶子就停"：
        // - 排序让锚点章节**确定、且通常是第一章**，而不是枚举顺序碰巧返回的那个
        //   （真机上出现过先返回「第10话」，会让封面在不同设备上不一致）；
        // - 只对排序后的前几项做叶子判定，命中即 break，其余子文件夹全部跳过。
        // 排序不读磁盘（一次 listChildren 已在上面完成），成本可忽略。
        for (child in childDirs.sortedWith(CHAPTER_NAME_ORDER)) {
            // 每轮都检查取消：父目录的 children 已经在内存里，循环体本身不必然挂起，
            // 不显式检查就会出现"取消之后又冒出一部漫画"（验收 A09 要求无错序结果）。
            currentCoroutineContext().ensureActive()
            if (!isLeafImageChapter(dir, child)) continue
            isManga = true
            emitManga(
                anchor = dir,
                chapters = listOf(
                    ChapterSpec(child.documentId, child.name, ChapterKind.IMAGE_DIRECTORY),
                ),
                anchorChildren = children,
                // 只探测到一个章节，因此章节数是"已知下限"而不是准确总数
                // （开发文档 5.1「不得把探测到一章伪报成完整的一章」）。
                chaptersFullyEnumerated = false,
            )
            // 找到一个章节就够：其余子文件夹全部跳过。
            break
        }

        if (isManga) return

        if (depth == 0 && childDirs.isEmpty() && hasDirectImage) {
            // 根特例：授权根本身就是叶子图片目录，说明用户选中的是单章节本体
            // （开发文档 5.1 第 3 行）。只对根生效，不把任意深层叶子误认成独立漫画。
            diagnose(dir.path, MESSAGE_ROOT_LEAF)
            emitManga(
                anchor = dir,
                chapters = listOf(ChapterSpec(request.rootDocumentId, dir.name, ChapterKind.IMAGE_DIRECTORY)),
                anchorChildren = children,
                chaptersFullyEnumerated = true,
            )
            return
        }
        if (depth == 0 && childDirs.isEmpty()) {
            diagnose(dir.path, MESSAGE_NO_IMAGE)
            return
        }

        // 走到这里说明这个目录不是漫画（没有直接章节），继续向下找。
        if (!request.recursive && depth > 0) return
        for (child in childDirs) {
            // 打开下一个目录之前检查取消：`openChild` 与随后的枚举都可能真的落盘，
            // 而循环本身不必然挂起——不检查就会出现"取消之后又读了一个目录"
            // （验收 A09：取消后不得继续产生结果）。
            currentCoroutineContext().ensureActive()
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
        val children = dir.enumerateOnce() ?: return
        val childDirs = children.filter { it.isDirectory }
        val hasDirectImage = children.any { it.isSupportedImage() }
        // 直接含 CBZ/ZIP/PDF 的目录属于「归档表」的解释范围，不是图片单章节。
        // 少了这一条，一张 `cover.jpg` + 几个章节压缩包就会看起来像"有图片且没有子文件夹"，
        // 于是父目录被误判成单章节、其余子文件夹全部被跳过（真机 `/Tachiyomi/local` 实测：
        // 51 个子文件夹只扫出 1 张名叫 local 的卡片）。
        val hasDirectArchive = children.any { it.isArchiveFile() }

        if (childDirs.isEmpty()) {
            when {
                // 只有"图片 + 压缩包"同时存在时才值得提示：那种目录正好会被误认成图片单章节。
                hasDirectArchive -> if (hasDirectImage) diagnose(dir.path, MESSAGE_ARCHIVE_CHAPTER)

                hasDirectImage -> emitManga(
                    anchor = dir,
                    chapters = listOf(ChapterSpec(dir.documentId, dir.name, ChapterKind.IMAGE_DIRECTORY)),
                    anchorChildren = children,
                    // 单章节模式下一张卡片就是这一个目录，章节数是结构定义。
                    chaptersFullyEnumerated = true,
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

    /**
     * 归档/PDF · 多章节：判定换成 [isArchiveFile]，其余与图片多章节同构
     * （开发文档 5.2：包含至少一个直接 CBZ/ZIP/PDF 的目录是一部漫画）。
     *
     * 同样**只取第一个**归档文件就能断定这是一部漫画；枚举每个归档的页数、
     * 打开每个 PDF 都是「深入」阶段的事，不属于发现阶段（开发文档 6.1）。
     */
    private suspend fun scanArchiveManga(dir: DirRef, depth: Int) {
        val children = dir.enumerateOnce() ?: return
        // 与图片多章节同一套规则：按名称自然序取第一个归档即可断定这是一部漫画，
        // 命中后其余子文件/子目录全部跳过。
        val firstArchive = children
            .filter { it.isArchiveFile() }
            .minWithOrNull(Comparator { a, b -> NaturalOrder.compare(a.name, b.name) })

        if (firstArchive != null) {
            emitManga(
                anchor = dir,
                chapters = listOf(
                    ChapterSpec(
                        documentId = firstArchive.documentId,
                        title = MimeTypes.nameWithoutExtension(firstArchive.name),
                        kind = ChapterKind.ARCHIVE,
                    ),
                ),
                anchorChildren = children,
                chaptersFullyEnumerated = false,
            )
        } else if (depth == 0 && children.isEmpty()) {
            diagnose(dir.path, MESSAGE_NO_ARCHIVE)
        }

        // 已判定为漫画：其余子目录全部跳过（与图片多章节一致）。
        if (firstArchive != null) return

        if (!request.recursive && depth > 0) return
        // 嵌套目录独立按递归开关检查，不混入父作品（开发文档 5.2）。
        for (child in children.filter { it.isDirectory }) {
            val sub = openChild(dir, child) ?: continue
            scanArchiveManga(sub, depth + 1)
        }
    }

    /**
     * 归档/PDF · 单章节：每个归档文件自身是一本共 1 章的漫画，章节标题为
     * 去扩展名的文件名（开发文档 1.3「归档单章节」）；recursive 控制是否继续
     * 向下遍历子目录（框架 4.3）。
     *
     * 这里的 1 章是**结构定义**而不是探测结果，因此 `chaptersFullyEnumerated = true`：
     * 一张卡片对应一个文件，不存在"还有别的章节"。
     */
    private suspend fun scanArchiveSingle(dir: DirRef, isRoot: Boolean) {
        val children = dir.enumerateOnce() ?: return

        for (file in children.filter { it.isArchiveFile() }) {
            val title = MimeTypes.nameWithoutExtension(file.name)
            emitManga(
                // 单章归档的漫画锚点就是归档文件本身：卡片与章节共用同一物理身份。
                anchor = DirRef(dir.tree, file.documentId, title, "${dir.path}/${file.name}"),
                chapters = listOf(ChapterSpec(file.documentId, title, ChapterKind.ARCHIVE)),
                anchorChildren = emptyList(),
                chaptersFullyEnumerated = true,
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
     * leafChapter(d)：直接子项中至少有一张受支持图片、**没有**子目录、且**没有** CBZ/ZIP/PDF
     * （开发文档 5.1，见下面对压缩包这一条的说明）。
     *
     * 判定顺序是「先确认没有子目录，再确认有图片」（框架 4.3）：先看到图片不能
     * 断言没有子目录（验收 A04）。这里用 [ContentTree.hasDirectoryChildren] 提前
     * 短路，避免为判定叶子而枚举整棵子树。
     *
     * **为什么要排除含压缩包的目录**（真机实测补上的规则）：Tachiyomi 的本地库里，
     * "章节就是压缩包"的漫画文件夹长这样：`漫画名/cover.jpg` + `漫画名/第1话.zip`……
     * 它没有子目录，唯一的一张图片是封面。按"有图片且没有子目录"判定它就是单章节，
     * 于是它的**父目录**（来源目录或授权根）被判定成漫画、其余子文件夹全部跳过——
     * 真机 `/Tachiyomi/local`（51 个子文件夹）因此只扫出 1 张名叫 `local` 的卡片。
     * 这类目录的正确归属是「CBZ/ZIP/PDF 导入列表」：归档表按"包含至少一个直接归档
     * 文件的目录是一部漫画"解释它，章节是那些压缩包（开发文档 5.2）。
     */
    private suspend fun isLeafImageChapter(parent: DirRef, child: ChildNode): Boolean {
        leafProbes++
        val ref = openChild(parent, child) ?: return false
        return try {
            // 用一次枚举同时回答"有没有子目录"和"有没有图片"，并把它缓存在 ref 上：
            // 若判定为章节就到此为止（用户要求的跳过），若判定为包裹目录，
            // 后面的递归会直接复用这份结果，不再重读同一个目录。
            val children = ref.enumerateOnce() ?: return false
            when {
                children.any { it.isDirectory } -> false

                children.any { it.isArchiveFile() } -> {
                    // 只有"图片 + 压缩包"同时存在时才提示：那种目录最容易被误认成图片章节。
                    if (children.any { it.isSupportedImage() }) {
                        diagnose("${parent.path}/${child.name}", MESSAGE_ARCHIVE_CHAPTER)
                    }
                    false
                }

                else -> children.any { it.isSupportedImage() }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail("${parent.path}/${child.name}", error)
            false
        }
    }

    /** 取该目录的子项，命中缓存则不重读磁盘。 */
    private suspend fun DirRef.enumerateOnce(): List<ChildNode>? {
        cachedChildren?.let { return it }
        val listed = enumerate(this) ?: return null
        cachedChildren = listed
        return listed
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
        /**
         * 章节清单是否已完整枚举。
         *
         * 发现阶段只探测**一个**直接章节就足以断定「这是一部漫画」，因此章节数是
         * 「已知下限」而不是总数（开发文档 5.1「不得把探测到一章伪报成完整的一章」）。
         * 完整清单属于「深入」阶段（开发文档 6.1 第 3 步、详情页的「更新章节」）。
         */
        chaptersFullyEnumerated: Boolean,
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
            // 只有真正枚举完章节候选时才声明「章节数已知」；
            // 否则界面显示「已发现 N 章，更新中」，不把下限当总数。
            chapterCount = chapterRecords.size,
            chapterCountKnown = chaptersFullyEnumerated,
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
    ) {
        /**
         * 已枚举的子项缓存。
         *
         * 每个目录在一次扫描里会被访问两次：一次是父目录判断"它是不是章节"
         * （isLeafImageChapter），一次是真正递归进去。缓存让第二次不再重读磁盘——
         * 真机上这是扫描耗时里最容易被忽略的一半（File.listFiles 与 SAF 查询都不便宜）。
         *
         * 生命周期只到本次扫描结束：ContentTree 实例本身是按次创建的，
         * 因此不存在"读到过期目录内容"的风险（开发文档 6.1 发现阶段只读元数据）。
         */
        var cachedChildren: List<ChildNode>? = null
    }

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

        /**
         * 候选子目录的检查顺序：名称自然序。
         *
         * 只影响"先检查哪一个"，不改变"找到一个章节就停、其余全部跳过"的规则；
         * 目的是让被选中的锚点章节确定且通常是第一章（目录枚举顺序在真实文件系统上
         * 不保证，真机上出现过先返回「第10话」）。
         */
        val CHAPTER_NAME_ORDER = Comparator<ChildNode> { a, b -> NaturalOrder.compare(a.name, b.name) }

        const val MESSAGE_MIXED = "目录同时包含图片和子目录，未按叶子章节处理（开发文档 5.1）"
        const val MESSAGE_ROOT_LEAF = "根目录自身是叶子图片目录，按根特例生成共 1 章的漫画（开发文档 5.1）"
        const val MESSAGE_NO_IMAGE = "目录内没有受支持的图片，未生成卡片（开发文档 5.1）"

        /**
         * 含 CBZ/ZIP/PDF 的目录在图片解释下的提示。
         *
         * 必须显式提示而不是静默跳过：这类目录（Tachiyomi 本地库里"章节就是压缩包"的
         * 漫画）在图片表里本来就不该出卡片，但用户只会在图库里"少了一部漫画"，
         * 不知道该把它加到另一张表。
         */
        const val MESSAGE_ARCHIVE_CHAPTER =
            "目录内有 CBZ/ZIP/PDF，按归档表解释（请把该目录加入「CBZ / ZIP / PDF 导入列表」），" +
                "不作为图片单章节（开发文档 5.2）"
        const val MESSAGE_NO_ARCHIVE = "目录内没有可导入的 CBZ/ZIP/PDF（开发文档 5.2）"
    }
}
