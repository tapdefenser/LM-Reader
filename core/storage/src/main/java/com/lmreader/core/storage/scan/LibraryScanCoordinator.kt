package com.lmreader.core.storage.scan

import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.SourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    private var pendingAllReason: ScanReason? = null

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
        val loopJob = schedulingLock.withLock {
            val running = activeJob
            if (running?.isActive == true) {
                pendingAllReason = mergeReason(pendingAllReason, reason)
                running
            } else {
                val created = scope.launch(start = CoroutineStart.LAZY) { runAllLoop(reason) }
                activeJob = created
                created.start()
                created
            }
        }
        // 调用方拿到的 Job 覆盖当前轮及扫描期间合并进来的补扫，不能在只“排队”后
        // 就提前完成，否则刷新状态和测试都会误判扫描已经结束。
        loopJob.join()
    }

    /** 扫描单个来源（保存路径、更新章节、权限恢复都走这里）。 */
    fun rescanSource(sourceId: String, reason: ScanReason, force: Boolean = false): Job = scope.launch {
        val source = sourceRepository.getSource(sourceId) ?: return@launch
        sourceGate.withPermit {
            updateOverall { it.copy(running = true, runningSources = it.runningSources + 1) }
            try {
                runner.requestScan(source, force = force).join()
                backfillPending()
            } finally {
                updateOverall { it.copy(runningSources = (it.runningSources - 1).coerceAtLeast(0)) }
                publishOverall()
            }
        }
    }

    fun cancelAll(): Job = scope.launch {
        val allJob = schedulingLock.withLock {
            pendingAllReason = null
            activeJob.also { activeJob = null }
        }
        allJob?.cancelAndJoin()
        // runner 使用独立 SupervisorJob；只取消 coordinator 的等待协程不会向下传播，
        // 必须显式取消并等待每个实际来源任务，才能保证“停止”真的停止 IO 与落库。
        runner.cancelAll()
        updateOverall { it.copy(running = false, runningSources = 0, currentPath = null) }
    }

    suspend fun cancel(sourceId: String) {
        runner.cancel(sourceId)
        publishOverall()
    }

    private suspend fun runAllLoop(initialReason: ScanReason) {
        val self = currentCoroutineContext()[Job] ?: return
        var reason = initialReason
        try {
            while (currentCoroutineContext().isActive) {
                runAll(reason)
                val next = schedulingLock.withLock {
                    pendingAllReason.also { pendingAllReason = null }.also { queued ->
                        if (queued == null && activeJob === self) activeJob = null
                    }
                } ?: break
                reason = next
            }
        } finally {
            schedulingLock.withLock {
                if (activeJob === self) {
                    activeJob = null
                    pendingAllReason = null
                }
            }
        }
    }

    private suspend fun runAll(reason: ScanReason) {
        updateOverall { it.copy(running = true, lastFailure = null) }
        try {
            // 只有一张路径表：一次遍历同时解释图片与归档（见 StructureScanner）。
            val sourceSnapshots = sourceRepository.getSources()
            for (snapshot in sourceSnapshots) {
                // 保存设置会提升 revision；不要把全库扫描开始时捕获的旧对象交给 runner。
                val source = sourceRepository.getSource(snapshot.sourceId) ?: continue
                sourceGate.withPermit {
                    updateOverall { it.copy(runningSources = it.runningSources + 1) }
                    try {
                        runner.requestScan(source, force = reason == ScanReason.FORCE_REBUILD).join()
                        // 每扫完一个来源就补全一批：用户不必等整库扫完才看到封面。
                        backfillPending()
                    } finally {
                        updateOverall { it.copy(runningSources = (it.runningSources - 1).coerceAtLeast(0)) }
                    }
                }
            }
        } finally {
            updateOverall { it.copy(running = false, runningSources = 0) }
            publishOverall()
        }
    }

    private fun mergeReason(pending: ScanReason?, incoming: ScanReason): ScanReason =
        if (pending == ScanReason.FORCE_REBUILD || incoming == ScanReason.FORCE_REBUILD) {
            ScanReason.FORCE_REBUILD
        } else {
            incoming
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
