package com.lmreader.core.storage.scan

import com.lmreader.core.index.ScanEvent
import com.lmreader.core.index.ScanRequest
import com.lmreader.core.index.StructureScanner
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.TreeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 单来源扫描作业（开发文档 6.3：每源最多一个结构扫描任务，重复请求合并）。
 *
 * 三个必须守住的规则：
 * 1. **每源单任务**：同一来源的重复请求合并到正在跑的任务上，不并发开两个扫描，
 *    否则两个 generation 会互相删对方的章节；
 * 2. **越早落库越好**：发现一部漫画就立刻 upsert 一次，界面才能边扫边出卡片
 *    （开发文档 6.1），而不是等整棵扫描完；
 * 3. **失败不删索引**：扫描中断或授权失效时只更新来源状态，绝不把漫画标成
 *    "已删除"（验收 A07）。
 */
class SourceScanRunner(
    private val treeAccess: TreeAccess,
    private val scanner: StructureScanner,
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Mutex()
    private val generationCounter = AtomicLong(0)

    private val _states = MutableStateFlow<Map<String, ScanState>>(emptyMap())

    /** 各来源的实时扫描状态；界面只订阅它，不自己数目录。 */
    val states: StateFlow<Map<String, ScanState>> = _states.asStateFlow()

    /**
     * 本次运行中「路径 → 失败原因（异常类型 + 原始 message）」。
     *
     * 为什么单独记一份：扫描结束时只能拿到 `ScanSummary.failedPaths`（只有路径），
     * 真正的原因只出现在 `ScanEvent.Failed` 里。如果不在事件回调时留下原因，
     * 界面最终只会显示"某某目录失败"，用户无法判断是权限、IO 还是格式问题
     * （开发文档 3「错误显示可操作原因」）。真机（MIUI 吞掉应用 logcat）上这一点
     * 尤其关键：日志不可读时，界面是唯一的信息出口。
     *
     * 它是 [runScan] 的局部状态而不是字段：多来源并发扫描时共享一份会被互相清空。
     */

    /**
     * 请求扫描一个来源。
     *
     * @param force 强制重建：丢弃目录快照并全量重扫（开发文档 6.3「强制重新扫描索引」）。
     * @return 本次（或已在进行中的）扫描作业。重复请求合并到正在跑的任务上。
     */
    fun requestScan(source: LibrarySource, force: Boolean = false): Job = scope.launch {
        // 锁内只做"检查并占位"，真正的扫描在锁外执行，否则第二个来源会被第一个
        // 的整轮扫描挡住，而开发文档 6.3 要求按源轮转而不是串成一条长队。
        val job = lock.withLock {
            val existing = jobs[source.sourceId]
            if (existing?.isActive == true) {
                // 已有任务在跑：本次请求合并到它上面。返回的 Job 仍然可以 join，
                // 语义是"这个来源的这一轮扫描已经结束"。
                existing
            } else {
                scope.launch { runScan(source, force) }.also { jobs[source.sourceId] = it }
            }
        }
        // 结束后清理登记，允许同一来源再次扫描。
        job.invokeOnCompletion {
            scope.launch { lock.withLock { jobs.remove(source.sourceId, job) } }
        }
        // 必须等**真正那一轮扫描**结束才返回。本方法返回的 Job 是调用方表达
        // "这一轮扫描完成了"的唯一句柄：`LibraryScanCoordinator` 用它限制同时扫描的
        // 来源数（`sourceGate` 的许可只有在 join 真的等待时才有意义），并在扫完后立刻
        // 补全封面/简介。早先的写法在登记完成后外层协程就结束了，`join()` 立即返回——
        // 结果是来源数上限形同虚设、补全提前开始、"已结束"的汇总也会早报。
        job.join()
    }

    suspend fun cancel(sourceId: String) {
        val job = lock.withLock { jobs.remove(sourceId) }
        job?.cancel()
        updateState(sourceId) { it.copy(running = false, status = ScanRunStatus.CANCELLED) }
    }

    fun cancelAll() {
        scope.launch {
            lock.withLock {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
            }
        }
    }

    private suspend fun runScan(source: LibrarySource, force: Boolean) {
        val generation = generationCounter.incrementAndGet()
        android.util.Log.i(
            TAG,
            "开始扫描 ${source.displayPath}（访问方式=" +
                (if (treeAccess.usesDirectFileAccess()) "直接文件访问" else "SAF 授权") + "）",
        )
        // 「路径 → 失败原因」按次运行隔离：多来源并发扫描时共享一份会被互相清空。
        val failureReasons = mutableMapOf<String, String>()
        updateState(source.sourceId) {
            it.copy(
                running = true,
                discovered = 0,
                chapters = 0,
                visited = 0,
                lastError = null,
                status = ScanRunStatus.RUNNING,
                // 记录本次实际使用的访问方式：排障时"为什么读不到"的第一个问题
                // 就是"它到底走的是哪条路径"。
                accessMode = if (treeAccess.usesDirectFileAccess()) "直接文件访问" else "SAF 授权",
                // 口径按来源的模式决定：单章节路径只产出"单章节"，
                // 多章节路径只产出"漫画"，两者不相加（用户要求）。
                isSingleChapterSource = source.mode == LayoutMode.SINGLE_CHAPTER,
            )
        }
        sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.RUNNING, null)

        // 冷启动/刷新时先校验授权：授权失效要立刻反映到路径状态列，而不是等
        // 扫描到一半抛 SecurityException（开发文档 4.1「路径状态」）。
        //
        // 校验由 TreeAccess 按当前访问方式完成：有「全部文件访问」时探测真实目录，
        // 单目录 SAF 授权时查持久授权记录并确认系统放行。原因直接来自那里，
        // 因此用户看到的是"目录不存在"或"请重新授权"这类可操作结论。
        treeAccess.checkReadable(source.treeUri)?.let { reason ->
            sourceRepository.updatePermission(source.sourceId, SourcePermissionState.LOST)
            sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.FAILED, reason)
            updateState(source.sourceId) {
                it.copy(running = false, lastError = reason, status = ScanRunStatus.FAILED, finishedAt = clock())
            }
            return
        }

        val root = treeAccess.open(source.treeUri, source.displayPath)
        if (root == null) {
            val message = "无法打开该目录，可能已被移动或删除"
            sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.FAILED, message)
            updateState(source.sourceId) {
                it.copy(running = false, lastError = message, status = ScanRunStatus.FAILED, finishedAt = clock())
            }
            return
        }

        // 强制重建时先丢弃目录快照：快照是"这个容器完整枚举过"的凭证，
        // 重建的定义就是不信任任何旧凭证（开发文档 6.3）。
        if (force) sourceRepository.updatePermission(source.sourceId, SourcePermissionState.CHECKING)

        val request = ScanRequest(
            sourceId = source.sourceId,
            sourceKind = source.kind,
            rootDocumentId = treeAccess.rootDocumentId(source.treeUri),
            layoutMode = source.mode,
            recursive = source.recursive,
            generation = generation,
            displayPath = source.displayPath,
        )

        val factory = treeAccess.treeFactory(source.treeUri)
        val summary = try {
            // 关键：工厂必须**按本次扫描的授权树**传进去。
            // 扫描器实例是共享的（结构扫描本身无状态），它不能持有某个具体来源的
            // 工厂；早先的写法在容器里给扫描器塞了一个返回 null 的占位工厂、
            // 这里又忘了传真实工厂，结果是"根目录列得出来、每个子目录都打不开、
            // 却没有任何异常"——真机上排查了很久才定位到这个接线错误。
            scanner.scan(
                request,
                root,
                factory = factory,
                events = { event -> onEvent(source.sourceId, event, failureReasons) },
            )
        } catch (cancellation: CancellationException) {
            // [StructureScanner] 按契约把取消表达为 `completed = false` 而不是抛出，
            // 但调度层若在别处取消了这个协程（例如取消整个全库扫描），异常仍会到这里。
            sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.CANCELLED, null)
            updateState(source.sourceId) {
                it.copy(running = false, status = ScanRunStatus.CANCELLED, finishedAt = clock())
            }
            throw cancellation
        }

        // 取消与失败必须区分：取消是用户意图，不该在路径状态里显示成红色错误。
        // 扫描器不抛异常地返回不完整结果，因此这里用当前协程是否仍活跃来判断，
        // 而不是把 `completed = false` 一律当成失败。
        val cancelled = !currentCoroutineContext().isActive
        val status = when {
            cancelled -> ScanRunStatus.CANCELLED
            !summary.completed -> ScanRunStatus.FAILED
            else -> ScanRunStatus.COMPLETED
        }

        // 本轮没有再发现的旧卡片要隐藏掉，否则切换解释方式（多章节 ↔ 单章节）、
        // 改动"子目录"勾选或把路径重新指向另一个目录之后，图库里会同时留着新旧两套
        // 卡片——用户看到的现象就是"单章节模式里出现了名字是带子文件夹的目录的卡片"。
        //
        // 只有 `COMPLETED` 才有资格做这个判定：取消、目录读取失败（completed=false）、
        // 授权失效时必须原样保留旧卡片，否则会把"这次没读到"误判成"已经不存在"
        // （验收 A07「失败不删索引」）。
        //
        // 判定依据是行上的 `discoveryGeneration`：发现阶段每次写入都带上本次代次，
        // 因此"本轮是否发现"不需要回传一份 ID 清单。标记只改 availability、不删行，
        // 章节、书架关系、阅读进度与译文全部保留，卡片被重新发现时自动回到可见。
        val staleMarked = if (status == ScanRunStatus.COMPLETED) {
            runCatching { mangaRepository.markUndiscoveredAsStale(source.sourceId, generation) }
                .onFailure { error ->
                    android.util.Log.e(TAG, "标记陈旧卡片失败 source=${source.sourceId}", error)
                }
                .getOrDefault(0)
        } else {
            0
        }
        // 诊断：把"打开子目录次数 / 枚举次数 / 命中叶子判定次数"打出来，
        // 用来验证"找到一个章节就跳过其余文件夹"是否真的生效（而不是靠感觉）。
        android.util.Log.i(
            TAG,
            "扫描结束 ${source.displayPath}：漫画=${summary.mangas} 遍历目录=${summary.directoriesVisited} " +
                "章节探测=${summary.leafChapterProbes}",
        )
        val error = if (cancelled) {
            null
        } else {
            // 汇总里只有路径，原因在 failureReasons（事件回调时记下的）。
            // 拼成"路径：原因"才能在界面上回答"为什么失败"。
            summary.failedPaths.firstOrNull()?.let { path ->
                failureReasons[path]?.let { "$path：$it" } ?: path
            }
        }
        android.util.Log.i(
            TAG,
            "扫描结束 source=${source.sourceId} 漫画=${summary.mangas} 目录=${summary.directoriesVisited} " +
                "completed=${summary.completed} 失败路径=${summary.failedPaths.size} 陈旧卡片=$staleMarked",
        )
        sourceRepository.updatePermission(
            source.sourceId,
            if (summary.failedPaths.isEmpty()) SourcePermissionState.OK else SourcePermissionState.PARTIAL,
        )
        sourceRepository.updateScanResult(source.sourceId, clock(), status, error)
        updateState(source.sourceId) {
            it.copy(
                running = false,
                discovered = summary.mangas,
                chapters = summary.chapters,
                visited = summary.directoriesVisited,
                leafProbes = summary.leafChapterProbes,
                staleMarked = staleMarked,
                currentPath = null,
                lastError = error,
                status = status,
                finishedAt = clock(),
            )
        }
    }

    private suspend fun onEvent(
        sourceId: String,
        event: ScanEvent,
        failureReasons: MutableMap<String, String>,
    ) {
        when (event) {
            is ScanEvent.MangaDiscovered -> {
                // 边发现边落库：失败只影响这一部作品，其它卡片照常出现（开发文档 6.1）。
                runCatching { mangaRepository.upsertScanResult(event.result) }
                    .onFailure { error ->
                        android.util.Log.e(TAG, "写入索引失败 manga=${event.result.manga.displayName}", error)
                        updateState(sourceId) { it.copy(lastError = error.message ?: "写入索引失败") }
                    }
                updateState(sourceId) { it.copy(discovered = event.totalDiscovered) }
            }

            is ScanEvent.Progress -> updateState(sourceId) {
                it.copy(
                    visited = event.directoriesVisited,
                    chapters = event.chaptersDiscovered,
                    currentPath = event.currentPath ?: it.currentPath,
                )
            }

            is ScanEvent.Diagnostic -> {
                // 诊断不算失败：混放目录只是提示，不该把路径状态标红（开发文档 5.1）。
                // 保留原文供"扫描诊断"查看，P1 再做逐条展示页。
                appendDiagnostic(sourceId, "诊断 ${event.path}：${event.message}")
            }

            is ScanEvent.Failed -> {
                val detail = "${event.path}：${event.message}"
                // 完整失败原因（路径 + 异常类型 + message）必须能被用户看到：
                // 真机（MIUI）会吞掉应用 logcat，只藏在日志里等于没有错误提示。
                failureReasons[event.path] = event.message
                appendDiagnostic(sourceId, detail)
                updateState(sourceId) { it.copy(lastError = detail) }
            }
        }
    }

    /** 诊断列表有上限：扫描万级目录时不能无限增长（UI 只展示最近若干条）。 */
    private fun appendDiagnostic(sourceId: String, line: String) {
        updateState(sourceId) { state ->
            val next = (state.diagnostics + line).takeLast(MAX_DIAGNOSTICS)
            state.copy(diagnostics = next)
        }
    }

    private fun updateState(sourceId: String, transform: (ScanState) -> ScanState) {
        _states.update { current ->
            val previous = current[sourceId] ?: ScanState(sourceId = sourceId)
            current + (sourceId to transform(previous))
        }
    }

    private fun documentIdOf(treeUri: String): String = treeAccess.rootDocumentId(treeUri)

    private companion object {
        const val TAG = "SourceScanRunner"

        /** 界面只展示最近若干条诊断；扫描万级目录时不能无限增长。 */
        const val MAX_DIAGNOSTICS = 40
    }
}
