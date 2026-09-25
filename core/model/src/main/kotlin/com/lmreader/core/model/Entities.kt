package com.lmreader.core.model

/**
 * 图库来源：用户授权的一条目录配置（开发文档 4.1、15.3）。
 *
 * [revision] 每次保存配置 +1：扫描结果是异步落库的，只有携带当前 revision 的
 * 结果才允许覆盖派生字段，否则快速改动「子目录/类型」会被上一个版本的扫描结果
 * 覆盖（验收 A09「旧版本扫描不能覆盖新配置」）。
 */
data class LibrarySource(
    val sourceId: String,
    val kind: SourceKind,
    val treeUri: String,
    val displayPath: String,
    /** 提供方名称；无法解析时用目录名（开发文档 4.1「路径状态」）。 */
    val providerLabel: String?,
    /**
     * 用户可编辑的显示名称。
     *
     * 为什么需要它：SAF 只能给出 `primary:Tachiyomi/downloads` 这类 documentId，
     * 真正的文件系统绝对路径不保证可解析（开发文档 4.1 的已知限制）。与其给用户
     * 一个不可读的路径串，不如让用户给它起一个自己认得的名字；为空时界面显示
     * [displayPath]，因此不会出现"没有名字"的状态。
     *
     * 它同时作为该来源下漫画卡片的默认名称来源（扫描发现阶段用文件夹名，
     * 用户在路径行里起的名字只影响展示，不改变身份与稳定 ID）。
     */
    val displayName: String?,
    /** 子目录列，默认 true（开发文档 4.1）。 */
    val recursive: Boolean,
    /** 类型列，默认 MULTI_CHAPTER（开发文档 4.1）。 */
    val mode: LayoutMode,
    /** 来源顺序，0 起；只有一张路径表，因此是一条序列（拖动排序即时持久化）。 */
    val orderIndex: Int,
    val permission: SourcePermissionState,
    val revision: Long,
    val lastScanAt: Long?,
    val lastScanStatus: ScanRunStatus?,
    val lastScanError: String?,
)

/**
 * 漫画行（开发文档 15.3）。
 *
 * [chapterCount]/[chapterCountKnown] 分开表达「已发现 N 章」与「N 是否完整」：
 * 发现阶段先出卡片、章节数随后补齐，UI 不得把探测到一章伪报成完整一章
 * （开发文档 5.1「漫画发现与章节同步分开」）。
 */
data class MangaRecord(
    val mangaId: String,
    val anchorDocumentId: String,
    /** effectiveSourceId：重叠授权时最具体的授权目录（开发文档 6.4）。 */
    val sourceId: String,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val displayName: String,
    /** 未知显示「未知」；发现阶段未读 ComicInfo 时为 null。 */
    val author: String?,
    /** 是否读到 ComicInfo.xml（开发文档 7.1）。 */
    val hasMetadata: Boolean,
    /** 压缩摘要，可空；「无简介」由 UI 呈现（开发文档 2）。 */
    val summary: String?,
    /** 第一章第一页或 cover.*；空则占位图（开发文档 7.2）。 */
    val coverDocumentId: String?,
    val coverChapterId: String?,
    /** null = 已发现 ≥1 章但未枚举完（开发文档 5.1）。 */
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    val availability: MangaAvailability,
    val discoveryGeneration: Long,
    val discoveredAt: Long,
    val updatedAt: Long,
)

/**
 * 章节行（开发文档 15.3）。
 *
 * [sortKey] 是自然序的**预计算列**：SQLite 只能做字典序比较，所以写入时就把
 * 「不区分大小写 + 数字按数值」的键算好，分页才可能稳定（框架 5.2）。
 */
data class ChapterRecord(
    val chapterId: String,
    val mangaId: String,
    val documentId: String,
    val kind: ChapterKind,
    /** 目录名或去扩展名的文件名（开发文档 1.3）。 */
    val title: String,
    val sortKey: String,
    /** null = 尚未枚举页面；本步不建立页清单（框架 9.4）。 */
    val pageCount: Int?,
    /** 归档首图/目录首页（开发文档 7.2）。 */
    val coverDocumentId: String?,
    val contentRevision: Long,
    val discoveredAt: Long,
)

/**
 * ComicInfo 解析结果：原文始终保留，未知字段不丢弃（开发文档 7）。
 *
 * [parseError] 非空表示格式错误或超过大小上限，此时 [fields] 为空但 [xml] 仍可
 * 展示，阅读与标题不受影响（开发文档 7.1）。
 */
data class MetadataRecord(
    /** mangaId 或 chapterId。 */
    val ownerId: String,
    val ownerType: MetadataOwnerType,
    /** XML 原文，始终保留（开发文档 7）。 */
    val xml: String,
    /** 已知字段；未知字段也保留在 map 中，键为元素本地名。 */
    val fields: Map<String, String>,
    val summary: String?,
    val series: String?,
    val title: String?,
    val writer: String?,
    val alternateSeries: String?,
    /** 规范化后的搜索文本：名称 + 别名 + 全部字段值 + XML 文本节点（框架 4.5）。 */
    val normalizedSearchText: String,
    /** 非空表示格式错误但仍保留原文。 */
    val parseError: String?,
    /** 例如「第一章 ComicInfo.xml」「漫画顶层兜底」（开发文档 7.1）。 */
    val sourceLabel: String,
    /** 稳定内容指纹，用于发现同长度同时间的变化（开发文档 6.2）。 */
    val fingerprint: String,
    val updatedAt: Long,
)

/**
 * 书架分类：首版单分类，内置「未分类」（categoryId = 0）不可删（开发文档 1.3、8.2）。
 */
data class Category(
    val categoryId: Long,
    val name: String,
    val styleMode: StyleMode,
    /** styleMode=CUSTOM 时的文本。 */
    val customStyle: String?,
    val orderIndex: Int,
    val revision: Long,
)

/**
 * 书架项：指向漫画的收藏关系，与源文件相互独立（开发文档 2）。
 *
 * 重扫只更新派生字段，**不得删除**书架条目（开发文档 15.3）。
 */
data class ShelfEntry(
    val mangaId: String,
    val categoryId: Long,
    val addedAt: Long,
)
