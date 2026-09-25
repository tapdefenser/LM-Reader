package com.lmreader.core.storage.scan

import com.lmreader.core.model.ScanRunStatus

/**
 * 单个来源的扫描状态（开发文档 4.1「路径状态」、8.1「扫描条」）。
 *
 * 刻意**不含百分比**：发现阶段没有总数，伪造进度条正是开发文档 8.1 明确禁止的
 * 「不伪造总百分比」。界面只能显示已发现数与已访问目录数。
 */
data class ScanState(
    val sourceId: String,
    val running: Boolean = false,
    val discovered: Int = 0,
    val visited: Int = 0,
    /** 为确认章节而打开检查子目录的次数；"找到一个就跳过其余"规则的可观测指标。 */
    val leafProbes: Int = 0,
    /**
     * 本次扫描结束时被标成陈旧、从图库/书架隐藏的旧卡片数。
     *
     * 只在扫描**完整跑完**后可能大于 0：取消或失败时旧卡片必须原样保留
     * （验收 A07）。显示它是为了让"切换解释方式后旧卡片消失"这件事可核对，
     * 而不是让用户凭感觉猜。
     */
    val staleMarked: Int = 0,
    val lastError: String? = null,
    /** 正在枚举的目录（相对授权根的可读路径），用于「正在扫描：xxx」提示。 */
    val currentPath: String? = null,
    /**
     * 本次扫描实际使用的访问方式：直接文件访问（全部文件访问）或 SAF 单目录授权。
     *
     * 排障时"为什么读不到"的第一个问题就是"它到底走了哪条路"，
     * 直接显示在诊断里比让用户猜要省一整轮往返。
     */
    val accessMode: String? = null,
    /** 已发现的章节总数（含单章节漫画自身的 1 章）。 */
    val chapters: Int = 0,
    /**
     * 本来源的解释方式是否为单章节。
     *
     * 用户明确要求"漫画"与"单章节"是两个互不相干的概念：单章节路径只按单章节
     * 方式扫描，多章节路径只按多章节方式扫描，因此统计口径与文案必须按来源区分，
     * 不能把两种结果加在一起说成"漫画"。
     */
    val isSingleChapterSource: Boolean = false,
    /**
     * 最近若干条诊断/失败详情（原始异常文本）。
     *
     * 为什么需要单独一份：`lastError` 在扫描结束时会被汇总覆盖成"首个失败路径"，
     * 那只是路径而不是原因。真机（MIUI 会吞掉应用 logcat）上如果不同时保留原始
     * 异常文本，用户与开发者都只看到"某某目录失败"而不知道是权限、IO 还是解码，
     * 违反开发文档 3「错误显示可操作原因」。
     */
    val diagnostics: List<String> = emptyList(),
    val status: ScanRunStatus? = null,
    val finishedAt: Long? = null,
) {
    val phaseLabel: String
        get() = when {
            running && isSingleChapterSource ->
                "正在扫描：已找到 $discovered 个单章节，已遍历 $visited 个目录"

            running -> "正在扫描：已找到 $discovered 部漫画，已遍历 $visited 个目录"
            lastError != null -> "扫描失败：$lastError"
            status == ScanRunStatus.CANCELLED -> "已取消扫描"
            discovered > 0 && isSingleChapterSource -> "已找到 $discovered 个单章节"
            discovered > 0 -> "已找到 $discovered 部漫画"
            else -> "尚未扫描"
        }
}

/**
 * 全库扫描的汇总状态（开发文档 8.1「扫描条」）。
 *
 * 只报**可数的东西**：已找到的漫画数、章节数、已遍历目录数与当前正在枚举的目录。
 * 发现阶段没有总数，因此不显示百分比（开发文档 8.1「不伪造总百分比」）。
 *
 * [currentPath] 只在单源扫描时给出：多源并行时"正在扫描哪一个"没有唯一答案，
 * 与其随便挑一个，不如让路径行自己显示各自的状态。
 */
data class OverallScanState(
    val running: Boolean = false,
    val runningSources: Int = 0,
    val currentPath: String? = null,
    /** 多章节来源发现的漫画数（单章节来源不计入这里）。 */
    val mangas: Int = 0,
    /** 单章节来源发现的单章节数；与 [mangas] 是两套口径，不相加。 */
    val singleChapters: Int = 0,
    /** 已发现的章节总数（仅多章节来源有意义）。 */
    val chapters: Int = 0,
    val visited: Int = 0,
    val lastFailure: String? = null,
    val lastFinishedAt: Long? = null,
) {
    /**
     * 底部操作条的状态行。
     *
     * 扫描中给出"已找到 x 部漫画，y 章，已遍历 z 个目录"，空闲时给出同样的汇总；
     * 完全没扫过时才说"还没有扫描结果"。
     */
    val statusLabel: String
        get() = when {
            // 用户口径：漫画与单章节互不相加，始终分列两个数字。
            running -> "正在扫描：已找到 $mangas 部漫画，$singleChapters 个单章节，已遍历 $visited 个目录"
            lastFailure != null -> "上次扫描出错：$lastFailure"
            mangas > 0 || singleChapters > 0 || visited > 0 ->
                "已找到 $mangas 部漫画，$singleChapters 个单章节，已遍历 $visited 个目录"

            else -> "还没有扫描结果"
        }

    /** 「正在扫描：<路径>」；没有唯一答案时返回 null，界面不显示这一行。 */
    val currentPathLabel: String?
        get() = currentPath?.takeIf { running && it.isNotBlank() }?.let { "正在扫描：$it" }
}

/**
 * 触发扫描的原因。
 *
 * **本应用只在三类显式动作下扫描**（用户要求，也是开发文档 6.3 的收敛结果）：
 * 1. 保存/新增了一条路径（[SAVED_SOURCE]）；
 * 2. 用户点了刷新（[REFRESH]）；
 * 3. 用户点了强制重新扫描索引（[FORCE_REBUILD]）。
 *
 * 打开图库、打开书架、冷启动、搜索**都不触发扫描**：它们只读已建好的本地索引。
 * 万级图库的一次全量变化扫描要几十秒，把它绑在"打开某个页面"上会让用户每次
 * 进页面都要等。
 *
 * [PERMISSION_RESTORED] 保留给权限恢复/外部变动通知（开发文档 6.3 的触发表），
 * 目前没有调用点——等"变化扫描算法"落地时它会用上：那时需要一个独立入口在
 * 检测到外部变化后按需补一轮，而不是在每个页面的 onResume 里扫。
 */
enum class ScanReason {
    /** 保存或新增了一个来源。 */
    SAVED_SOURCE,

    /** 用户显式刷新（图库顶部刷新按钮 / 路径页的刷新）。 */
    REFRESH,

    /** 强制重新扫描索引：丢弃目录快照并全量重建（开发文档 4.1）。 */
    FORCE_REBUILD,

    /** 权限恢复或外部文件变动通知。 */
    PERMISSION_RESTORED,
}
