package com.lmreader.core.index

import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.SourceKind

/**
 * 扫描结果类型的重导出。
 *
 * 这三个类型在框架实现说明 §4.1 里列在 `core:index`，但 §3.6 的
 * `MangaRepository.upsertScanResult` 需要它们，而 §1 只允许 `core:index → core:model`
 * 的单向依赖。为同时满足「签名冻结」与「禁止循环依赖」，实体放在 `core:model`，
 * 这里用 typealias 让 `com.lmreader.core.index.ScanResult` 等引用名继续可用。
 */
typealias ScanResult = com.lmreader.core.model.ScanResult
typealias MetadataCandidate = com.lmreader.core.model.MetadataCandidate
typealias ScanPersistReport = com.lmreader.core.model.ScanPersistReport

/**
 * 一个来源的扫描请求。root 已由调用方打开，扫描器不负责授权（框架 4.1）。
 */
data class ScanRequest(
    val sourceId: String,
    /**
     * 来源所属的表（图片目录 / CBZ-ZIP-PDF 导入）。
     *
     * **它不再决定扫描行为**：两种解释共用一次遍历（一个目录的直接章节是"直接含的
     * 归档文件"与"直接子目录里的叶子图片目录"的并集）。这里保留它是因为它参与
     * 卡片身份（[com.lmreader.core.model.StableId.mangaId]）与界面徽标，改它会让
     * 已有卡片的 ID 变化、连带丢掉书架与阅读进度。
     */
    val sourceKind: SourceKind,
    val rootDocumentId: String,
    val layoutMode: LayoutMode,
    val recursive: Boolean,
    val generation: Long,
    /** 根的真实展示路径，仅用于诊断文案。 */
    val displayPath: String,
)

/**
 * 扫描进度事件，UI 只消费它，不自己数目录（框架 4.1）。
 *
 * 设计成流式事件而不是「结束时的汇总」：开发文档 6.1 要求发现阶段边扫边出卡片，
 * 而卡片数、状态条与取消都必须在扫描过程中可用。
 */
sealed interface ScanEvent {
    /** 新发现一部漫画；totalDiscovered 是本次运行的累计数。 */
    data class MangaDiscovered(val result: ScanResult, val totalDiscovered: Int) : ScanEvent

    data class Progress(
        val directoriesVisited: Int,
        val mangasDiscovered: Int,
        /** 已发现章节数（含单章节漫画自身的 1 章）。 */
        val chaptersDiscovered: Int = 0,
        /** 正在枚举的目录（可读路径），用于「正在扫描：xxx」。 */
        val currentPath: String? = null,
    ) : ScanEvent

    /** 目录可读但结构异常（混放等），进诊断列表，不阻断（开发文档 5.1）。 */
    data class Diagnostic(val path: String, val message: String) : ScanEvent

    /** 目录/文件读取失败：只影响该分支，其它作品继续（开发文档 5.3）。 */
    data class Failed(val path: String, val message: String) : ScanEvent
}

/** 按需打开子目录/归档；SAF 实现与内存实现都走这里（框架 4.2）。 */
fun interface TreeFactory {
    suspend fun open(child: ChildNode): ContentTree?
}

/**
 * 一次扫描的汇总（框架 4.2）。
 *
 * [completed] = false 表示被取消或存在无法恢复的 IO 错误。落库方必须据此禁止
 * 删除判定，否则中断会被当成「章节消失」（开发文档 6.2、验收 A07）。
 */
data class ScanSummary(
    val generation: Long,
    val mangas: Int,
    val chapters: Int,
    val directoriesVisited: Int,
    val diagnostics: List<String>,
    val failedPaths: List<String>,
    /** false = 被取消或存在无法恢复的 IO 错误。 */
    val completed: Boolean,
    /**
     * 为了确认"某个子目录是不是章节"而打开并检查它的次数。
     *
     * 用户要求的跳过规则使这个数字应当接近**漫画数**：一个作品目录里只探测一个
     * 子目录就停。它与 [directoriesVisited] 不是一回事——后者包含为了找到漫画
     * 而必须走到的每一层目录，因此天然更大。
     */
    val leafChapterProbes: Int = 0,
)
