package com.lmreader.core.api

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import org.junit.Test

class ApiConcurrencyLimiterTest {
    @Test fun `two active requests exclude waiters and cancellation releases counts`() = runBlocking {
        val limiter = ApiConcurrencyLimiter()
        val release = CompletableDeferred<Unit>()
        val first = launch { limiter.withPermit("computer", 2) { release.await() } }
        val second = launch { limiter.withPermit("computer", 2) { release.await() } }
        withTimeout(2000) { limiter.activeRequests.first { it == 2 } }
        var thirdStarted = false
        val third = launch { limiter.withPermit("computer", 2) { thirdStarted = true; release.await() } }
        yield()
        assertFalse(thirdStarted)
        assertEquals(2, limiter.activeRequests.value)
        third.cancelAndJoin()
        assertEquals(2, limiter.activeRequests.value)
        first.cancelAndJoin()
        assertEquals(1, limiter.activeRequests.value)
        release.complete(Unit); second.join()
        assertEquals(0, limiter.activeRequests.value)
    }

    @Test fun `failed requests release counts across profile pools`() = runBlocking {
        val limiter = ApiConcurrencyLimiter()
        assertFailsWith<IllegalStateException> {
            limiter.withPermit("one", 1) {
                limiter.withPermit("two", 1) {
                    assertEquals(2, limiter.activeRequests.value)
                    error("synthetic failure")
                }
            }
        }
        assertEquals(0, limiter.activeRequests.value)
    }
}
