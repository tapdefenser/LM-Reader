package com.lmreader.ui.common

import com.lmreader.core.model.ChapterCountProbeTarget
import com.lmreader.core.model.LayoutMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节计数队列的行为。
 *
 * 与封面队列守同一批口径（只数"还没数过"的、数过就不再数、一批最多 30 项、失败也要落库），
 * 另外守一条**本队列独有**的底线：它只写计数列，绝不碰 `chapterCountKnown`
 * ——那一位是同步逻辑删除章节行的闸门，见 `ChapterCounter` 的说明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterCountProbeQueueTest {

    private fun target(id: String) = ChapterCountProbeTarget(
        mangaId = id,
        anchorDocumentId = "/root/$id",
        layoutMode = LayoutMode.MULTI_CHAPTER,
        sourceTreeUri = "content://tree",
    )

    private class Recorder {
        val counted = mutableListOf<String>()
        val persisted = mutableListOf<Triple<String, Int?, Long>>()
        val notified = mutableListOf<Pair<String, Int?>>()
        val targetBatches = mutableListOf<List<String>>()
    }

    private fun TestScope.queue(
        recorder: Recorder,
        count: (ChapterCountProbeTarget) -> Int? = { 7 },
    ) = ChapterCountProbeQueue(
        scope = this,
        clock = { 42L },
        probeTargets = { ids ->
            recorder.targetBatches += ids
            ids.map { target(it) }
        },
        count = { t ->
            recorder.counted += t.mangaId
            count(t)
        },
        persist = { mangaId, c, at -> recorder.persisted += Triple(mangaId, c, at) },
        onCounted = { mangaId, c, _ -> recorder.notified += mangaId to c },
    )

    @Test
    fun `入队后就地数出并把结果回传界面`() = runTest {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request(listOf("a", "b"))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), recorder.counted)
        assertEquals(listOf("a" to 7, "b" to 7), recorder.notified)
        assertEquals(listOf(Triple("a", 7, 42L), Triple("b", 7, 42L)), recorder.persisted)
    }

    @Test
    fun `同一个 id 在一次会话里只数一次`() = runTest {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request(listOf("a"))
        advanceUntilIdle()
        queue.request(listOf("a", "a"))
        advanceUntilIdle()

        assertEquals(listOf("a"), recorder.counted)
    }

    @Test
    fun `数不出章节也要落库（否则每次滚动都会重数）`() = runTest {
        val recorder = Recorder()
        val queue = queue(recorder, count = { null })

        queue.request(listOf("a"))
        advanceUntilIdle()

        assertEquals(listOf(Triple("a", null, 42L)), recorder.persisted)
        assertEquals(listOf("a" to null), recorder.notified)
    }

    @Test
    fun `一批最多 30 项`() = runTest {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request((1..70).map { "m$it" })
        advanceUntilIdle()

        assertEquals(70, recorder.counted.size)
        assertTrue("每批不超过 30", recorder.targetBatches.all { it.size <= 30 })
        assertEquals(3, recorder.targetBatches.size)
    }

    @Test
    fun `猛划时只保留最新的窗口`() = runTest {
        val recorder = Recorder()
        val queue = queue(recorder)

        // 200 是上限：一次性丢进 500 个，最旧的 300 个应该被丢掉。
        queue.request((1..500).map { "m$it" })
        advanceUntilIdle()

        val counted = recorder.counted.toSet()
        assertTrue("最新的还在", "m500" in counted)
        assertTrue("最旧的被丢掉", "m1" !in counted)
        assertEquals(200, counted.size)
    }

    @Test
    fun `clear 之后旧会话的待办不再处理`() = runTest {
        val recorder = Recorder()
        val queue = queue(recorder)

        queue.request(listOf("a"))
        queue.clear()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), recorder.counted)
    }
}
