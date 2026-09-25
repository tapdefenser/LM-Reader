package com.lmreader.core.model

/**
 * 来源种类：决定卡片唯一键的一半（开发文档 15.3）。
 *
 * 同一物理目录允许同时出现在「图库路径列表」与「CBZ/ZIP/PDF 导入列表」中，
 * 此时它是两个实体而不是重复项：两张表解释出的章节集合不同（开发文档 4.1、15.3）。
 */
enum class SourceKind { IMAGE_DIRECTORY, ARCHIVE_IMPORT }

/**
 * 解释方式：同一物理目录在两张表里可以有不同的解释。
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

/** 漫画可用性：来源不可用时卡片仍然存在，只是不可阅读（开发文档 4.1）。 */
enum class MangaAvailability { AVAILABLE, SOURCE_UNAVAILABLE }

/** 章节种类：物理形式是叶子图片目录还是归档/PDF 文件（开发文档 2、5.2）。 */
enum class ChapterKind { IMAGE_DIRECTORY, ARCHIVE }

/** 扫描运行状态：由调度层维护，用于「检查中/部分失败」等路径状态展示（开发文档 4.1）。 */
enum class ScanRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

/** 元数据归属：ComicInfo 可以属于一部漫画，也可以只属于它的某一章（开发文档 7.1）。 */
enum class MetadataOwnerType { MANGA, CHAPTER }

/** 文风模式：全局 / 跟随分类 / 自定义（开发文档 15.3、翻译配置对照文档）。 */
enum class StyleMode { GLOBAL, CATEGORY, CUSTOM }
