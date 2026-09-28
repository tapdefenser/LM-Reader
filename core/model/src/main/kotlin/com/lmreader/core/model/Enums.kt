package com.lmreader.core.model

/**
 * 来源种类：卡片身份的一半（开发文档 15.3）。
 *
 * **它不再是"扫描方式的开关"**：结构扫描一次遍历就会同时找"图片章节"（叶子图片目录）
 * 与"归档章节"（直接的 CBZ/ZIP/PDF），所以同一个目录（例如 Tachiyomi 的
 * `/Tachiyomi/local`，里面既有"章节是文件夹"的作品、也有"章节是压缩包"的作品）
 * 一条来源就能扫全，界面也只有**一张**路径表。
 *
 * 保留这一维度的唯一理由是**身份**：`mangaId` 由 `(锚点 documentId, sourceKind)` 派生，
 * 改它会让已有卡片换 ID，连带丢掉书架与阅读进度。卡片内容改由章节种类表达
 * （`MangaCard.hasArchiveChapters`）。
 */
enum class SourceKind { IMAGE_DIRECTORY, ARCHIVE_IMPORT }

/**
 * 解释方式：多章节 / 单章节。
 *
 * 它是可修改的配置而**不是身份的一部分**，因此不进入漫画唯一键
 * （开发文档 15.3「layoutMode 是可修改解释，不放进该唯一键」）。
 */
enum class LayoutMode { MULTI_CHAPTER, SINGLE_CHAPTER }

/**
 * 授权状态：失效不视为该目录下所有漫画被删除（开发文档 4.1）。
 *
 * 之所以单独建状态而不是直接删索引：SD 卡拔出、提供方离线都可恢复，
 * 恢复后必须能沿用稳定 ID 找回书架与进度（验收 A07）。
 */
enum class SourcePermissionState { OK, CHECKING, LOST, PARTIAL }

/**
 * 漫画可用性：来源不可用时卡片仍然存在，只是不可阅读（开发文档 4.1）。
 *
 * [STALE] 表示"本来源最近一次**完整跑完**的扫描没有再发现这张卡片"——例如用户把这一行
 * 的解释方式从多章节改成单章节（锚点目录随之从"漫画目录"变成"叶子目录"，[StableId.mangaId]
 * 因此完全不同），或者目录已经被删掉/移走。它只说明"当前配置下不该再出现这张卡片"，
 * **不是**"文件被删了"：漫画行、章节、书架关系、阅读进度与译文一律保留，下一次扫描
 * 重新发现它时会自动回到 [AVAILABLE]（用户要求：旧卡片不能一直留在图库里，但也不能
 * 用删除来实现——那会级联删掉书架项与用户数据）。
 */
enum class MangaAvailability { AVAILABLE, SOURCE_UNAVAILABLE, STALE }

/** 章节种类：物理形式是叶子图片目录还是归档/PDF 文件（开发文档 2、5.2）。 */
enum class ChapterKind { IMAGE_DIRECTORY, ARCHIVE }

/** 扫描运行状态：由调度层维护，用于「检查中/部分失败」等路径状态展示（开发文档 4.1）。 */
enum class ScanRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

/** 元数据归属：ComicInfo 可以属于一部漫画，也可以只属于它的某一章（开发文档 7.1）。 */
enum class MetadataOwnerType { MANGA, CHAPTER }

/** 文风模式：全局 / 跟随分类 / 自定义（开发文档 15.3、翻译配置对照文档）。 */
enum class StyleMode { GLOBAL, CATEGORY, CUSTOM }

/**
 * 一章（某一种目标语言）的翻译状态。
 *
 * **"未翻译"不是一个状态值，而是"没有记录"**：一部 500 章的作品里用户只翻了 3 章，
 * 为其余 497 章各写一行只会让表无意义地变大（与 `chapter_read_state` 同一个取舍）。
 *
 * [PENDING] 的语义是"没有译文、且在队列里"——「翻译所选」与「清除翻译文本」都会落到
 * 这个状态（用户口径：都没译文了不得待翻译）。
 */
enum class TranslationState {
    /** 已入队，等待翻译。 */
    PENDING,

    /** 翻译中（P3 的调度器接上后才会出现）。 */
    RUNNING,

    /** 有译文。 */
    DONE,

    /** 上次失败，[ChapterTranslation.failure] 里有原因。 */
    FAILED,
}
