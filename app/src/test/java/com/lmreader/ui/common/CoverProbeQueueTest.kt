package com.lmreader.ui.common

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.CoverProbeTarget
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.ResolvedCover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面懒加载队列的行为。
 *
 * 这里守的是用户的四条口径：只取"还没取过"的、取过就不再取、一批最多 30 项、
 * 失败也要落库（否则那些卡片每次滚动都会被重新枚举一遍目录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverProbeQueueTest {

    private fun target(id: String) = CoverProbeTarget(
        mangaId = id,
        anchorDocumentId = "/root/$id",
        layoutMode = LayoutMode.MULTI_CHAPTER,
        sourceTreeUri = "content://tree",
        firstChapter = ChapterRecord(
            chapterId = "c_$id",
            mangaId = id,
            documentId = "/root/$id/Chapter 1",
            kind = ChapterKind.IMAGE_DIRECTORY,
            title = "Chapter 1",
            sortKey = "chapter 1",
            pageCount = null,
            coverDocumentId = null,
            contentRevision = 0,
            discoveredAt = 0,
        ),
    )

    /** 记录调用的队列夹具。 */
    private class Recorder {
        val resolved = mutableListOf<String>()
        val persisted = mutableListOf<Pair<String, ResolvedCover?>>()
        val notified = mutableListOf<String>()
        val targetBatches = mutableListOf<List<String>>()
    }

    private fun TestScope.queue(
        recorder: Recorder,
        resolve: (CoverProbeTarget) -> ResolvedCover? = { t ->
            ResolvedCover("${t.anchorDocumentId}/001.jpg", t.firstChapter!!.chapterId)
        },
        beforeResolve: suspend () -> Unit = {},
    ) = CoverProbeQueue(
        scope = this,
        clock = { 42L },
        probeTargets = { ids ->
            recorder.targetBatches += ids
            ids.map { target(it) }
        },
        resolve = { t ->
            recorder.resolved += t.mangaId
            beforeResolve()
            resolve(t)
        },
        persist = { id, cover, at ->
            assertEquals(42L, at)
            recorder.persisted += id to cover
        },
        onResolved = { id, _, _ -> recorder.notified += id },
    )

    @Test
    fun `取到封面后落库并回传界面`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request(listOf("m1", "m2"))

        assertEquals(listOf("m1", "m2"), recorder.resolved)
        assertEquals(2, recorder.persisted.size)
        assertEquals("m1", recorder.persisted[0].first)
        // 封面路径由解析器给出（真实实现是"第一章目录下的自然序首图"）。
        assertEquals("/root/m1/001.jpg", recorder.persisted[0].second?.coverDocumentId)
        assertEquals("c_m1", recorder.persisted[0].second?.coverChapterId)
        assertEquals(listOf("m1", "m2"), recorder.notified)
    }

    @Test
    fun `没取到封面也算探测过——失败必须落库`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val queue = queue(recorder, resolve = { null })

        queue.request(listOf("empty"))

        // 落库是 null 的结论：不落的话这些卡片每次滚动都会被重新枚举一遍目录。
        assertEquals(listOf("empty" to null), recorder.persisted)
    }

    @Test
    fun `同一张卡片重复可见只取一次`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request(listOf("m1"))
        queue.request(listOf("m1"))
        queue.request(listOf("m1", "m1"))

        assertEquals(listOf("m1"), recorder.resolved)
    }

    @Test
    fun `一批最多 30 项`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request((1..70).map { "m$it" })

        // 70 项要分 3 批（30 / 30 / 10），而不是一次把 70 个目录全打开。
        assertEquals(listOf(30, 30, 10), recorder.targetBatches.map { it.size })
        assertEquals(70, recorder.resolved.size)
    }

    @Test
    fun `猛划时丢掉最旧的、保留最新可见窗口`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        // 让解析一直挂着：队列因此积压，上限才有意义（真实场景是目录枚举慢）。
        val gate = CompletableDeferred<Unit>()
        val queue = queue(recorder, beforeResolve = { gate.await() })

        // 分多次上报，模拟用户快速下滑：队列上限 200，更早划过去的应该被丢掉。
        repeat(12) { page -> queue.request((1..30).map { "p$page-m$it" }) }

        gate.complete(Unit)
        advanceUntilIdle()

        val probed = recorder.resolved.toSet()
        // 最新窗口必须在：用户现在看得见的是它。
        assertTrue("最新的窗口必须在", "p11-m1" in probed)
        // 积压期间入队但还没被取走的，按"最旧先丢"淘汰（上限 200）。
        // 已经在飞的那一批（p0）不在此列——它已经付出了一次目录枚举，丢掉只是浪费。
        assertTrue("积压里最旧的那批应该被丢掉", "p1-m1" !in probed)
    }

    @Test
    fun `换搜索词后清空队列，旧结果不再取`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request(listOf("stale"))
        queue.clear()
        // clear 之后重新入队同一批：它已经不在屏幕上了，但用户滚回来时仍应能取到。
        queue.request(listOf("fresh"))

        assertEquals(listOf("stale", "fresh"), recorder.resolved)
    }
}
