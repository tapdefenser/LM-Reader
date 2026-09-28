package com.lmreader.ui.common

import com.lmreader.core.model.CoverProbeTarget
import com.lmreader.core.model.ResolvedCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 封面懒加载队列（图库与书架共用）。
 *
 * ## 为什么需要它
 *
 * 封面原先在扫描的补全阶段算：每部漫画多开一次章节目录（扫描变慢），而且补全每轮
 * 只处理 120 条，几千条待补要刷十几次图库才轮得到——真机上的表现就是"图库封面
 * 几乎全是破图"。现在**扫描只写路径**，封面在列表**滚动到可见时**才取。
 *
 * ## 规则（用户口径）
 *
 * 1. 只对"还没有封面、且从未探测过"的卡片入队（`coverProbedAt == null`，由调用方过滤）；
 * 2. 取过一次就落库标记（成功或失败都算），**不再取第二次**；
 * 3. 同一批最多 30 项，一次一批，不因为"结果是几千条"就一股脑全取；
 * 4. 猛划时队列有上限，最新可见的优先——否则一次快滑会堆出几千个目录枚举。
 *
 * ## 依赖为什么是三个函数而不是仓储
 *
 * 队列真正需要的能力只有"批量取目标 / 解析一张 / 落库"，用函数表达之后它完全不认识
 * 仓储接口，也就可以用三个 lambda 做纯 JVM 测试（去重、上限、批次、落库时机）。
 *
 * @param probeTargets 批量取探测输入（漫画 + 来源 + 第一章），必须一次查询取齐
 * @param persist 把一次探测结果写进数据库（成功与失败都要写，否则失败项会被反复重试）
 * @param onResolved 结果回传给界面（用于就地刷新那张卡片，不必重查列表）
 */
class CoverProbeQueue(
    private val scope: CoroutineScope,
    private val probeTargets: suspend (List<String>) -> List<CoverProbeTarget>,
    private val resolve: suspend (CoverProbeTarget) -> ResolvedCover?,
    private val persist: suspend (mangaId: String, cover: ResolvedCover?, at: Long) -> Unit,
    private val onResolved: (mangaId: String, cover: ResolvedCover?, at: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 待探测的 mangaId，保持入队顺序（LinkedHashSet 顺手去重）。 */
    private val pending = LinkedHashSet<String>()

    /**
     * 本会话已经探测过的 mangaId。
     *
     * 数据库里的 `coverProbedAt` 才是"不再重取"的**持久**依据，调用方也会按它过滤；
     * 这里再记一份是防线而不是重复：界面上的卡片对象可能还没收到回传（探测在飞），
     * 那一刻的重复上报（滚动中每帧都会上报可见集合）不该变成重复的目录枚举。
     * 有上限，长时间滚动不会无限增长——真值在数据库里。
     */
    private val attempted = LinkedHashSet<String>()

    /**
     * 是否已有探测循环在跑。
     *
     * 只在主线程（`scope` 默认的调度器）读写，因此不需要额外同步；`scope` 若是
     * 多线程调度器，这个假设就不成立——调用方必须传一个串行的作用域
     * （ViewModel 的 `viewModelScope` 满足）。
     */
    private var running = false

    /** 入队一批可见卡片；只接受确实需要探测的 id。 */
    fun request(mangaIds: List<String>) {
        if (mangaIds.isEmpty()) return
        for (id in mangaIds) {
            if (id in attempted) continue
            pending.add(id)
        }
        // 猛划时"已经划过去"的卡片没有取封面的价值：丢掉最旧的那批，保留最新窗口。
        while (pending.size > MAX_PENDING) {
            val oldest = pending.first()
            pending.remove(oldest)
        }
        start()
    }

    /** 列表会话重建（换搜索词、换筛选）时清空队列：旧结果已经不在屏幕上了。 */
    fun clear() {
        pending.clear()
    }

    private fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                while (true) {
                    val batch = drain()
                    if (batch.isEmpty()) break
                    runBatch(batch)
                }
            } finally {
                running = false
                // 循环期间新入队的：立刻再起一轮，否则要等用户再滑一下才处理。
                // 与 start() 同在串行作用域上，不存在重入竞争。
                if (pending.isNotEmpty()) start()
            }
        }
    }

    private fun drain(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val batch = pending.take(BATCH_SIZE)
        batch.forEach { pending.remove(it) }
        remember(batch)
        return batch
    }

    /** 记下"这批已经探测过"，并让集合保持有界。 */
    private fun remember(ids: List<String>) {
        ids.forEach { attempted.add(it) }
        while (attempted.size > MAX_ATTEMPTED) {
            val oldest = attempted.first()
            attempted.remove(oldest)
        }
    }

    private suspend fun runBatch(ids: List<String>) {
        // 批量取目标：一批 30 张卡片若各自查"漫画 + 来源 + 第一章"，就是 90 次数据库往返。
        val targets = runCatching { probeTargets(ids) }.getOrNull() ?: return
        if (targets.isEmpty()) return

        // 并发 2：与开发文档 6.3 的"封面解码 2 个"一致。文件系统上的目录枚举是
        // 机械盘/闪存上的寻道操作，并发开大只会互相拖慢，还挤占阅读的 IO。
        val results = mutableListOf<Pair<String, ResolvedCover?>>()
        for (chunk in targets.chunked(CONCURRENCY)) {
            val done = coroutineScope {
                chunk.map { target ->
                    async { target.mangaId to runCatching { resolve(target) }.getOrNull() }
                }.awaitAll()
            }
            results += done
        }

        for ((mangaId, cover) in results) {
            val now = clock()
            // 失败也落库（cover = null）：这正是"不再取第二次"的载体。
            runCatching { persist(mangaId, cover, now) }
            onResolved(mangaId, cover, now)
        }
    }

    private companion object {
        /** 一批最多几张卡片：与列表分页一批 30 项对齐（用户口径）。 */
        const val BATCH_SIZE = 30

        /** 队列上限：快滑几千项时不能堆出几千个目录枚举。 */
        const val MAX_PENDING = 200

        /** "已探测过"的记忆上限；真值在数据库的 `coverProbedAt` 里。 */
        const val MAX_ATTEMPTED = 2000

        const val CONCURRENCY = 2
    }
}
