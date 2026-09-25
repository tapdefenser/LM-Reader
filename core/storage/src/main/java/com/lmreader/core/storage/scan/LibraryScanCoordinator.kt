package com.lmreader.core.storage.scan

import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 全库扫描协调（开发文档 6.3「每源最多一个结构扫描任务；全库调度按源轮转」）。
 *
 * 与 [SourceScanRunner] 的分工：runner 负责"一个来源怎么扫"，coordinator 负责
 * "什么时候扫哪些来源、扫完做什么"。这样界面只需要跟 coordinator 打交道，
 * 不必知道有几个来源正在跑。
 *
 * 并发上限按开发文档 6.3 的起点值：同时枚举目录 2 个、封面解码 2 个。这些数字
 * 是起点值而不是承诺值，设备测试后再调；集中在这里定义，避免散落到多处。
 */
class LibraryScanCoordinator(
    private val runner: SourceScanRunner,
    private val sourceRepository: SourceRepository,
    private val mangaRepository: MangaRepository,
    private val backfillWorker: MetadataBackfillWorker?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val schedulingLock = Mutex()

    /** 同时进行的**来源**数量上限；每个来源内部仍然串行。 */
    private val sourceGate = Semaphore(MAX_ACTIVE_SOURCES)

    /** 封面/元数据补全的并发上限（开发文档 6.3「封面解码 2 个」）。 */
    private val backfillGate = Semaphore(MAX_BACKFILL_CONCURRENCY)

    private val _overall = MutableStateFlow(OverallScanState())
    val overall: StateFlow<OverallScanState> = _overall.asStateFlow()

    /**
     * 各来源的扫描状态，直接转发 runner。
     *
     * 界面需要"这个路径正在扫/已发现 N 项"来画路径状态列（开发文档 4.1），
     * 让界面同时订阅 coordinator.overall 与 runner.states 会绕过协调层，
     * 因此在这里统一暴露。
     */
    val runnerStates: StateFlow<Map<String, ScanState>> get() = runner.states

    private var activeJob: Job? = null

    init {
        // 直接订阅各来源状态：进度与「正在扫描：xxx」必须跟着每次 Progress 事件走。
        // 只在"开始/结束"时手工汇总会让当前目录停在上一个位置不动。
        scope.launch {
            runner.states.collect { publishOverall() }
        }
    }

    /**
     * 重扫全部已保存来源。
     *
     * 请求合并规则（开发文档 6.3「正在扫描则合并请求，必要时结束后补一轮」）：
     * 已经有全库任务在跑时，记一个"结束后补一轮"标记而不是并发开第二轮——
     * 两轮并发会互相删除对方的章节。
     */
    fun rescanAll(reason: ScanReason): Job = scope.launch {
        var followUp = false
        schedulingLock.withLock {
            val running = activeJob
            if (running?.isActive == true) {
                followUp = true
                return@withLock
            }
            activeJob = launch { runAll(reason) }
        }
        if (followUp) {
            // 补一轮：等当前任务结束后再排一次，把扫描期间发生的配置变化纳入。
            activeJob?.join()
            schedulingLock.withLock {
                if (activeJob?.isActive != true) activeJob = launch { runAll(reason) }
            }
        }
    }

    /** 扫描单个来源（保存路径、更新章节、权限恢复都走这里）。 */
    fun rescanSource(sourceId: String, reason: ScanReason, force: Boolean = false): Job = scope.launch {
        val source = sourceRepository.getSource(sourceId) ?: return@launch
        sourceGate.withPermit {
            updateOverall { it.copy(running = true, runningSources = it.runningSources + 1) }
            runner.requestScan(source, force = force).join()
            updateOverall { it.copy(runningSources = (it.runningSources - 1).coerceAtLeast(0)) }
            backfillPending()
            publishOverall()
        }
    }

    fun cancelAll() {
        runner.cancelAll()
        updateOverall { it.copy(running = false, runningSources = 0) }
    }

    suspend fun cancel(sourceId: String) {
        runner.cancel(sourceId)
        publishOverall()
    }

    private suspend fun runAll(reason: ScanReason) {
        updateOverall { it.copy(running = true, lastFailure = null) }
        // 两张表都要扫：图片表与归档表是独立的解释方式（开发文档 4.1）。
        val sources = SourceKind.entries.flatMap { sourceRepository.getSources(it) }
        if (sources.isEmpty()) {
            updateOverall { it.copy(running = false, runningSources = 0) }
            return
        }

        for (source in sources) {
            sourceGate.withPermit {
                updateOverall { it.copy(runningSources = it.runningSources + 1) }
                runner.requestScan(source, force = reason == ScanReason.FORCE_REBUILD).join()
                updateOverall { it.copy(runningSources = (it.runningSources - 1).coerceAtLeast(0)) }
                // 每扫完一个来源就补全一批：用户不必等整库扫完才看到封面
                // （开发文档 6.1「当前可见卡片优先补封面」的近似实现）。
                backfillPending()
            }
        }
        updateOverall { it.copy(running = false, runningSources = 0) }
        publishOverall()
    }

    /**
     * 补全一批待补全漫画。
     *
     * 本步的实现说明（框架第 9 节）：按固定顺序补"缺封面或缺 XML"的条目，每轮
     * 有限批量，避免一次把万级条目塞进内存。真正的"当前可见优先 + 低优先级后台"
     * 两级队列是 P1 项，这里必须标注为已知限制，不能声称已实现优先级调度。
     */
    private suspend fun backfillPending() {
        val worker = backfillWorker ?: return
        val ids = mangaRepository.pendingBackfillIds(BACKFILL_BATCH)
        if (ids.isEmpty()) return
        val chunks = ids.chunked(BACKFILL_CHUNK_SIZE)
        chunks.map { chunk ->
            scope.launch {
                backfillGate.withPermit {
                    val updated = worker.backfill(chunk)
                    if (updated > 0) publishOverall()
                }
            }
        }.forEach { it.join() }
    }

    /**
     * 汇总各来源状态。
     *
     * 计数取**各源之和**：多个路径并行扫描时，用户关心的是整个库的进度，
     * 而不是某一个来源。当前路径只在恰好一个来源在扫时给出——多源并行时
     * "正在扫描哪一个"没有唯一答案，随便挑一个会误导（开发文档 8.1 不伪造状态）。
     */
    private fun publishOverall() {
        scope.launch {
            val states = runner.states.value.values
            val active = states.filter { it.running }
            val failure = states.firstNotNullOfOrNull { it.lastError }
            updateOverall {
                it.copy(
                    running = active.isNotEmpty(),
                    runningSources = active.size,
                    currentPath = if (active.size == 1) active.first().currentPath else null,
                    // 只把多章节来源算作"漫画"，单章节来源单独计（用户要求：
                    // 漫画与单章节是两个互不相干的概念，不能相加）。
                    mangas = states.filterNot { it.isSingleChapterSource }.sumOf { it.discovered },
                    singleChapters = states.filter { it.isSingleChapterSource }.sumOf { it.discovered },
                    chapters = states.sumOf { state -> state.chapters },
                    visited = states.sumOf { state -> state.visited },
                    lastFailure = failure,
                    lastFinishedAt = states.mapNotNull { state -> state.finishedAt }.maxOrNull(),
                )
            }
        }
    }

    private fun updateOverall(transform: (OverallScanState) -> OverallScanState) {
        _overall.value = transform(_overall.value)
    }

    private companion object {
        /** 开发文档 6.3 的起点值：同时枚举目录 2 个。 */
        const val MAX_ACTIVE_SOURCES = 2

        /** 开发文档 6.3 的起点值：封面解码 2 个。 */
        const val MAX_BACKFILL_CONCURRENCY = 2

        /** 每轮补全的条目上限；避免一次把万级条目读进内存。 */
        const val BACKFILL_BATCH = 120
        const val BACKFILL_CHUNK_SIZE = 30
    }
}
