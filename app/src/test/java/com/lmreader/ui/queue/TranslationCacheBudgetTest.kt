package com.lmreader.ui.queue

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationCacheBudgetTest {
    @Test fun loweringLimitKeepsInUsePagesAndBlocksAdmissionUntilTheyRelease() = runBlocking {
        val mb = 1_048_576L
        val budget = TranslationCacheBudget(64 * mb)
        val first = budget.acquire(32 * mb)
        val second = budget.acquire(32 * mb)
        budget.setLimit(32 * mb)
        val waiting = async { budget.acquire(32 * mb) }
        yield(); assertFalse(waiting.isCompleted)
        first.release(); yield(); assertFalse(waiting.isCompleted)
        second.release()
        val admitted = withTimeout(1000) { waiting.await() }
        assertEquals(32 * mb, budget.bytes.value)
        admitted.shrink(1024); assertEquals(1024, budget.bytes.value)
        admitted.release(); assertEquals(0, budget.bytes.value)
    }
}
