package com.lmreader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.StyleMode

/**
 * Room 实体。表名与列名遵循 `docs/框架实现说明.md` §5.1。
 *
 * 为什么实体与 `core:model` 的领域类型分开：Room 需要可空/注解化的行结构，
 * 而领域类型要给纯 JVM 扫描器与 UI 用，不能带注解依赖。转换在 `Mappers.kt`。
 * 两边字段名保持同名，避免"两个名字指同一件事"的长期歧义。
 */

/** 路径表一行；两张表共用此表，用 [kind] 区分（开发文档 4.1）。 */
@Entity(
    tableName = "library_sources",
    indices = [Index(value = ["kind", "orderIndex"])],
)
data class LibrarySourceEntity(
    @PrimaryKey val sourceId: String,
    val kind: SourceKind,
    val treeUri: String,
    val displayPath: String,
    val providerLabel: String?,
    /** 用户可编辑的显示名称；为空时界面回退到 displayPath。 */
    val displayName: String?,
    val recursive: Boolean,
    val mode: LayoutMode,
    val orderIndex: Int,
    val permission: SourcePermissionState,
    /** 每次保存配置 +1；过期扫描结果不得覆盖新配置（验收 A09）。 */
    val revision: Long,
    val lastScanAt: Long?,
    val lastScanStatus: ScanRunStatus?,
    val lastScanError: String?,
)

/**
 * 漫画行。
 *
 * 唯一约束是 `(anchorDocumentId, sourceKind)` 而不是 documentId 单列：同一个物理
 * 目录可以分别被图片表和归档表识别，两张卡片各自成立（开发文档 15.3）。
 */
@Entity(
    tableName = "mangas",
    indices = [
        Index(value = ["anchorDocumentId", "sourceKind"], unique = true),
        Index(value = ["sourceId", "sourceOrderIndex"]),
        Index(value = ["sortKey", "mangaId"]),
        Index(value = ["displayName"]),
    ],
)
data class MangaEntity(
    @PrimaryKey val mangaId: String,
    val anchorDocumentId: String,
    val sourceId: String,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val displayName: String,
    /**
     * 自然序预计算列：SQLite 只能做字典序比较，分页要稳定就必须在写入时把
     * 「不区分大小写 + 数字按数值」的键算好（框架 5.2）。
     */
    val sortKey: String,
    /**
     * 来源表内顺序的冗余列。
     *
     * 为什么冗余：分页查询要按 `来源顺序 → 自然名称 → mangaId` 排序（开发文档 6.4），
     * 若每次 JOIN 来源表排序，路径表重排会让整个查询计划退化；冗余一列后
     * 重排只需一条 UPDATE。写入方负责与 `library_sources.orderIndex` 保持一致。
     */
    val sourceOrderIndex: Int,
    val author: String?,
    val hasMetadata: Boolean,
    val summary: String?,
    val coverDocumentId: String?,
    val coverChapterId: String?,
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    val availability: MangaAvailability,
    val discoveryGeneration: Long,
    val discoveredAt: Long,
    val updatedAt: Long,
)

/** 章节行；物理定位键是 `(documentId, kind)`（开发文档 15.3）。 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["mangaId"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["documentId", "kind"], unique = true),
        Index(value = ["mangaId", "sortKey"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey val chapterId: String,
    val mangaId: String,
    val documentId: String,
    val kind: ChapterKind,
    val title: String,
    val sortKey: String,
    val pageCount: Int?,
    val coverDocumentId: String?,
    val contentRevision: Long,
    val discoveredAt: Long,
)

/**
 * ComicInfo 记录；`ownerId` 是 mangaId 或 chapterId。
 *
 * 章节的原始 XML 按章节独立保存，漫画级搜索投影另建（开发文档 6.4）。
 */
@Entity(
    tableName = "metadata_records",
    indices = [Index(value = ["ownerType", "ownerId"])],
)
data class MetadataEntity(
    @PrimaryKey val ownerId: String,
    val ownerType: MetadataOwnerType,
    val xml: String,
    /** JSON 文本；未知字段必须保留（开发文档 7.1）。 */
    @ColumnInfo(name = "fieldsJson") val fieldsJson: String,
    val summary: String?,
    val series: String?,
    val title: String?,
    val writer: String?,
    val alternateSeries: String?,
    val normalizedSearchText: String,
    val parseError: String?,
    val sourceLabel: String,
    val fingerprint: String,
    val updatedAt: Long,
)

/** 书架分类；categoryId = 0 是内置「未分类」，不可删（开发文档 1.3）。 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey val categoryId: Long,
    val name: String,
    val styleMode: StyleMode,
    val customStyle: String?,
    val orderIndex: Int,
    val revision: Long,
)

/**
 * 书架项：一个漫画最多一条收藏关系（开发文档 8.2）。
 *
 * 外键挂 mangaId 而非 anchorDocumentId：重扫只更新派生字段，漫画行不会被删，
 * 因此收藏关系天然存活（开发文档 15.3）。
 */
@Entity(
    tableName = "shelf_entries",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["mangaId"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class ShelfEntryEntity(
    @PrimaryKey val mangaId: String,
    val categoryId: Long,
    val addedAt: Long,
)

/** 阅读进度；本步只建表，阅读器在 P2 接入。 */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val mangaId: String,
    val chapterId: String?,
    val pageOrdinal: Int,
    val intraPageRatio: Float,
    val read: Boolean,
    val bookmark: Boolean,
    val updatedAt: Long,
)

/**
 * 目录快照：记录「这个容器是否被完整枚举过」。
 *
 * 为什么存摘要而不是成员列表：成员集合可以从 `mangas/chapters` 的 documentId
 * 反查，重复存一份必然出现两处不一致（开发文档 6.2）。
 */
@Entity(
    tableName = "directory_snapshots",
    primaryKeys = ["sourceId", "documentId"],
    indices = [Index(value = ["sourceId"])],
)
data class DirectorySnapshotEntity(
    val sourceId: String,
    val documentId: String,
    val memberDigest: String?,
    val memberCount: Int,
    val generation: Long,
    val completedAt: Long,
    val lastError: String?,
)

/** 扫描运行记录；用于界面显示"上次扫描"与诊断（开发文档 6.2）。 */
@Entity(tableName = "scan_runs", indices = [Index(value = ["sourceId"])])
data class ScanRunEntity(
    @PrimaryKey val generation: Long,
    val sourceId: String,
    val sourceRevision: Long,
    val status: ScanRunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val mangasFound: Int,
    val error: String?,
)
