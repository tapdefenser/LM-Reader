package com.lmreader.ui.common

import com.lmreader.core.model.ChapterCountProbeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 章节计数懒加载队列（图库与书架共用）。
 *
 * ## 为什么需要它
 *
 * 多章节漫画的发现阶段只探测一个章节（不遍历所有章节文件夹，那是"扫描慢"的主要来源），
 * 于是卡片的章节数长期是**下限 1**。界面把它显示成「已发现 1 章，更新中」有歧义
 * （用户口径："不要做成已发现一章，更新中，这会产生歧义"）；而按用户要求改成
 * **滚动 + 搜索结果时**真的数一次：与封面同一套懒加载形状。
 *
 * ## 规则（与 [CoverProbeQueue] 完全同形）
 *
 * 1. 只对"章节清单还不完整、且从未数过"的卡片入队（`chapterCountKnown == false &&
 *    countedChapterCountAt == null`，由调用方过滤）；
 * 2. 数过一次就落库标记（数出多少都算），**不再数第二次**；
 * 3. 同一批最多 30 项，一次一批；
 * 4. 猛划时队列有上限，最新可见的优先。
 *
 * ## 它**不**碰 `chapterCountKnown`
 *
 * 数出来的数字只写 `countedChapterCount/countedChapterCountAt`：那一位是同步逻辑
 * "可以删掉多余章节行"的闸门，而这里并没有枚举并落库章节清单（见 `ChapterCounter`）。
 *
 * @param probeTargets 批量取探测输入（漫画 + 锚点目录 + 布局模式 + 来源树 URI）
 * @param count 数一部漫画的章节数；null = 数不出可读章节
 * @param persist 落库（成功与失败都要写，否则失败项会被反复重试）
 * @param onCounted 结果回传给界面（用于就地刷新那张卡片，不必重查列表）
 */
class ChapterCountProbeQueue(
    private val scope: CoroutineScope,
    private val probeTargets: suspend (List<String>) -> List<ChapterCountProbeTarget>,
    private val count: suspend (ChapterCountProbeTarget) -> Int?,
    private val persist: suspend (mangaId: String, count: Int?, at: Long) -> Unit,
    private val onCounted: (mangaId: String, count: Int?, at: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 待探测的 mangaId，保持入队顺序（LinkedHashSet 顺手去重）。 */
    private val pending = LinkedHashSet<String>()

    /**
     * 本会话已经探测过的 mangaId。
     *
     * 数据库里的 `countedChapterCountAt` 才是"不再数"的**持久**依据，调用方也会按它过滤；
     * 这里再记一份是防线：界面上的卡片对象可能还没收到回传（计数在飞），那一刻的重复上报
     * （滚动中每帧都会上报可见集合）不该变成重复的目录列举。有上限，真值在数据库里。
     */
    private val attempted = LinkedHashSet<String>()

    /** 是否已有计数循环在跑；只在串行作用域（`viewModelScope`）上读写。 */
    private var running = false

    /** 入队一批可见卡片；只接受确实需要计数的 id。 */
    fun request(mangaIds: List<String>) {
        if (mangaIds.isEmpty()) return
        for (id in mangaIds) {
            if (id in attempted) continue
            pending.add(id)
        }
        // 猛划时"已经划过去"的卡片没有计数的价值：丢掉最旧的那批，保留最新窗口。
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
        // 批量取目标：一批 30 张卡片若各自查一次"漫画 + 来源"，就是 30 次数据库往返。
        val targets = runCatching { probeTargets(ids) }.getOrNull() ?: return
        if (targets.isEmpty()) return

        // 并发 2：目录枚举是闪存上的寻道操作，并发开大只会互相拖慢，还挤占阅读的 IO。
        val results = mutableListOf<Pair<String, Int?>>()
        for (chunk in targets.chunked(CONCURRENCY)) {
            val done = coroutineScope {
                chunk.map { target ->
                    async { target.mangaId to runCatching { count(target) }.getOrNull() }
                }.awaitAll()
            }
            results += done
        }

        for ((mangaId, chapters) in results) {
            val now = clock()
            // 失败也落库（chapters = null）：这正是"不再数第二次"的载体。
            runCatching { persist(mangaId, chapters, now) }
            onCounted(mangaId, chapters, now)
        }
    }

    private companion object {
        /** 一批最多几张卡片：与列表分页一批 30 项对齐（用户口径）。 */
        const val BATCH_SIZE = 30

        /** 队列上限：快滑几千项时不能堆出几千次目录列举。 */
        const val MAX_PENDING = 200

        /** "已探测过"的记忆上限；真值在数据库的 `countedChapterCountAt` 里。 */
        const val MAX_ATTEMPTED = 2000

        const val CONCURRENCY = 2
    }
}
