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
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    val inShelf: Boolean,
    val availability: MangaAvailability,
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
    /** 按 owner 归属的 ComicInfo 记录；章节级的也在这里。 */
    val records: List<MetadataRecord> = emptyList(),
    /** 漫画级搜索投影文本（开发文档 6.4）。 */
    val searchText: String,
    val at: Long,
)
