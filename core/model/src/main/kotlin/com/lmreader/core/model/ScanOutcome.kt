package com.lmreader.core.model

/**
 * 一次扫描产出的可落库结果（框架实现说明 4.1）。
 *
 * **归属说明（与文档的已知冲突）**：框架实现说明把本类型列在 `core:index` 一节，
 * 但同文档 §3.6 的 [MangaRepository.upsertScanResult] 需要它，而 §1 又规定依赖
 * 只能由 `core:index` 指向 `core:model`（禁止反向依赖与循环依赖）。两个要求无法
 * 同时满足，取舍是**把扫描结果类型放在依赖图最底层的 `core:model`**，
 * 并在 `core:index` 用 typealias 保持 `com.lmreader.core.index.ScanResult`
 * 这一引用名可用，使两侧调用方都不必改签名。修改此处前请先改框架实现说明。
 */
data class ScanResult(
    val sourceId: String,
    val sourceKind: SourceKind,
    /**
     * 启动本轮扫描时的来源配置版本。
     *
     * 落库方必须与 library_sources.revision 比较；不一致表示用户已在扫描途中
     * 修改类型、递归或目录，旧结果不得覆盖新配置（验收 A09）。
     */
    val sourceRevision: Long,
    val generation: Long,
    val manga: MangaRecord,
    val chapters: List<ChapterRecord>,
    /**
     * 本次完整枚举成功的目录 documentId 集合；只在其中判定删除（开发文档 6.2）。
     *
     * 语义是「枚举直接子项成功的容器」：只有完整枚举过的容器，其成员的消失
     * 才可能是真的删除，否则权限丢失/中断会被误判为大批删除（验收 A07）。
     */
    val fullyEnumeratedContainers: Set<String>,
    /** 已发现的归档内 ComicInfo 位置，供后续补全阶段读取（开发文档 6.1 第 2 步）。 */
    val metadataCandidates: List<MetadataCandidate>,
)

/**
 * ComicInfo 待读位置。
 *
 * [archiveMemberPath] 非空表示 XML 在归档内部，此时 [ownerDocumentId] 是归档/章节
 * 的 documentId；为 null 表示 XML 就是内容树里的一个普通文档（目录下的
 * `ComicInfo.xml`，或归档根，由补全阶段打开后确认）。
 */
data class MetadataCandidate(
    val ownerDocumentId: String,
    val ownerType: MetadataOwnerType,
    /** 非空表示在归档内部。 */
    val archiveMemberPath: String?,
    /** 供 UI/日志展示的来源说明，例如「第一章 ComicInfo.xml」（开发文档 7.1）。 */
    val label: String,
)

/**
 * 扫描落库报告：由仓储实现在事务内统计（框架 5.2）。
 *
 * 把计数放在结果里而不是让调用方再查库，是因为「本次扫描新增/更新了什么」
 * 只有事务内部知道，且失败重试时不能靠二次查询推断。
 */
data class ScanPersistReport(
    val mangasInserted: Int,
    val mangasUpdated: Int,
    val chaptersInserted: Int,
    val chaptersRemoved: Int,
    val mangasMarkedUnavailable: Int,
)
