package com.lmreader.core.model

/**
 * 图库/书架卡片所需的最小投影，UI 不为每张卡片再查库（框架 3.5）。
 *
 * 做成投影而不是直接复用 [MangaRecord] 的原因：分页每批 40 项（开发文档 6.4），
 * 卡片不需要 anchor/sourceId/时间戳等字段，投影可以让查询只读必要列。
 */
data class MangaCard(
    val mangaId: String,
    val displayName: String,
    /** 最多两行，null = 无简介（开发文档 8.1）。 */
    val summaryPreview: String?,
    /**
     * 卡片所属的有效来源。
     *
     * 为什么卡片必须带它：封面与页面都只能用「授权树 URI + documentId」组合打开
     * （开发文档 4.1），没有 sourceId 就无法把 documentId 变成可读 URI，
     * 界面只能再为每张卡片查一次来源表——那就破坏了 40 项分页的前提。
     */
    val sourceId: String,
    val coverDocumentId: String?,
    val coverChapterId: String?,
    /**
     * 封面**探测过**的时间；null 表示从未探测。
     *
     * 为什么要区分"没有封面"和"没探测过"：封面改为图库滚动时懒加载（扫描只写路径），
     * 而"这部漫画确实没有可用首图"（空目录、首章是归档、目录读不到）与"还没轮到它"
     * 在数据上都是 `coverDocumentId = null`。少了这一列，每次滚动都会把那些**必然拿不到**
     * 封面的卡片重新枚举一遍目录，滚动越深越慢。
     *
     * 语义：非 null = 已经取过一次（成功或失败），不再取第二次；只有详情页的
     * 「更新章节」会强制重取并刷新它。
     */
    val coverProbedAt: Long?,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    val inShelf: Boolean,
    val availability: MangaAvailability,
    /**
     * 这张卡片的章节里是否有归档章节（CBZ/ZIP/PDF）。
     *
     * 一次遍历同时识别图片与归档之后，[sourceKind] 只说明"这条来源是从哪张表加的"，
     * 不再说明卡片内容，因此界面徽标改看这个字段（阅读归档本身是 P2，有这个标记
     * 用户才知道哪几张卡暂时读不了）。
     */
    val hasArchiveChapters: Boolean = false,
)

/**
 * 一次封面探测所需的最小信息（图库滚动懒加载）。
 *
 * 为什么不直接把这些字段塞进 [MangaCard]：卡片是**每批 30 张**都要读的投影，而封面
 * 探测只发生在"这张卡还没有封面且从未探测过"的那一小部分卡片上。把 anchor /
 * treeUri 塞进卡片等于让每一次分页都多读两列只偶尔用到的数据；单独一次批量查询
 * 反而更省，也让"探测用到的输入"与"列表展示用到的字段"各自独立演进。
 */
data class CoverProbeTarget(
    val mangaId: String,
    /** 漫画锚点目录；多章节下是作品的根目录，单章节下就是唯一那一章的目录。 */
    val anchorDocumentId: String,
    val layoutMode: LayoutMode,
    /** 该卡片所属来源的授权树 URI：封面只能用「树 URI + documentId」组合打开。 */
    val sourceTreeUri: String,
    /**
     * 该漫画的**第一章**（自然序）；null 表示章节表里已经没有它的章节。
     *
     * 用第一章而不是锚点章节：扫描的发现阶段只保证"锚点章节存在"，而它不保证是自然序
     * 第一章（见 `StructureScanner` 的偏离记录），封面必须是第一页（开发文档 7.2）。
     */
    val firstChapter: ChapterRecord?,
)

/**
 * 一次封面探测的结果。
 *
 * [coverDocumentId] 与 [coverChapterId] 同生共死：章节 id 是"这张封面属于哪一章"，
 * 详情页用它做跳转与重取；只有其一没有意义。
 */
data class ResolvedCover(
    val coverDocumentId: String,
    val coverChapterId: String,
)

/**
 * 一页结果。total 未知时用 null，UI 不得伪造百分比（开发文档 8.1）。
 *
 * [nextOffset] 是本步 LIMIT/OFFSET 分页的下一页偏移；会话顺序表与 keyset 分页
 * 是 P1 增强（框架 5.2 / 9.2），因此扫描中新增条目可能造成翻页重复，
 * UI 必须用 mangaId 去重（框架 9.2）。
 */
data class MangaPage(
    val items: List<MangaCard>,
    val nextOffset: Int,
    val exhausted: Boolean,
    val totalKnown: Int?,
)

/**
 * 补全阶段的工作投影（开发文档 6.1 第 2 步）。
 *
 * 为什么带 [sourceTreeUri]/[sourceKind]：补全要打开源目录读 ComicInfo 与首图，
 * 而这些只有来源表里有。若让补全线程再按 mangaId 反查来源，每个来源会被
 * 反复查询；一次投影取齐可以让补全按来源分组批处理。
 */
data class MangaBackfillTarget(
    val manga: MangaRecord,
    val chapters: List<ChapterRecord>,
    val sourceTreeUri: String,
    val sourceKind: SourceKind,
    val sourcePermission: SourcePermissionState,
    /** 是否已有封面与简介；用于跳过无变化的条目，避免每次刷新都解码图片。 */
    val hasCover: Boolean,
    val hasMetadata: Boolean,
)

/**
 * 补全结果；`null` 字段表示"本次没有读到新值"，不等于"要清空原值"。
 *
 * 这个区分是必要的：首章目录临时不可读时不能把已有封面与简介抹掉，否则用户
 * 每次刷新都会看到封面闪一下再消失。
 */
data class MangaMetadataUpdate(
    val mangaId: String,
    val coverDocumentId: String? = null,
    val coverChapterId: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val hasMetadata: Boolean = false,
    /**
     * 本次**探测**简介的时间；null 表示这次不是一次完整探测（不写标记）。
     *
     * 与 [hasMetadata] 的区别见 `MangaEntity.metadataProbedAt`：读不到 XML 也必须记
     * "读过了"，否则补全队列会在同一批条目上无限空转。
     */
    val metadataProbedAt: Long? = null,
    /** 按 owner 归属的 ComicInfo 记录；章节级的也在这里。 */
    val records: List<MetadataRecord> = emptyList(),
    /** 漫画级搜索投影文本（开发文档 6.4）。 */
    val searchText: String,
    val at: Long,
)
