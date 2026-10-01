package com.lmreader.core.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GitHubUpdatesTest {
    private lateinit var server: MockWebServer
    private lateinit var updates: GitHubUpdates
    @Before fun startServer() {
        server = MockWebServer().apply { start() }
        updates = GitHubUpdates(endpoint = server.url("/repos/tapdefenser/LM-Reader/releases/latest"))
    }
    @After fun stopServer() { server.shutdown() }
    private fun release(tag: String) = MockResponse().setBody("""{"tag_name":"$tag","draft":false,"prerelease":false,"html_url":"https://example.invalid/untrusted"}""")

    @Test fun newerReleaseUsesNumericComparisonAndOurRepositoryPage() = runBlocking {
        server.enqueue(release("v0.10.0"))
        val result = updates.check("0.9.0") as UpdateStatus.Release
        assertEquals(true, result.isNewer)
        assertEquals("${ProjectLinks.RELEASES}/tag/v0.10.0", result.page)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2026-03-10", request.getHeader("X-GitHub-Api-Version"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun equalAndOlderReleasesDoNotOfferAnUpdate() = runBlocking {
        for (tag in listOf("v0.1.0", "v0.1.0+build.8", "v0.0.9")) {
            server.enqueue(release(tag))
            assertEquals(false, (updates.check("0.1.0") as UpdateStatus.Release).isNewer)
        }
    }

    @Test fun noPublicReleaseAndNonStablePayloadsHaveNoUpdate() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(404),
            MockResponse().setBody("""{"tag_name":"v1.0.0","draft":true}"""),
            MockResponse().setBody("""{"tag_name":"v1.0.0","prerelease":true}"""))) {
            server.enqueue(response)
            assertEquals(UpdateStatus.NoPublicRelease, updates.check("0.1.0"))
        }
    }

    @Test fun accessRestrictionsHaveADistinctStatus() = runBlocking {
        for (status in listOf(403, 429)) {
            server.enqueue(MockResponse().setResponseCode(status))
            assertEquals(UpdateStatus.AccessRestricted, updates.check("0.1.0"))
        }
    }

    @Test fun unfamiliarVersionRequiresManualComparisonAndEncodesItsTag() = runBlocking {
        server.enqueue(release("release/2026"))
        val result = updates.check("0.1.0") as UpdateStatus.Release
        assertNull(result.isNewer)
        assertEquals("${ProjectLinks.RELEASES}/tag/release%2F2026", result.page)
    }

    @Test fun malformedAndOversizedResponsesFailRatherThanClaimingUpToDate() = runBlocking {
        for (response in listOf(MockResponse().setBody("not-json"), MockResponse().setBody("{}"),
            MockResponse().setBody("x".repeat(512 * 1024 + 1)))) {
            server.enqueue(response)
            assertTrue(runCatching { updates.check("0.1.0") }.isFailure)
        }
    }

    @Test fun serverFailureIsReportedAsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(runCatching { updates.check("0.1.0") }.exceptionOrNull() is IOException)
    }

    @Test fun cancellationCancelsTheUnderlyingRequest() = runBlocking {
        val activeCall = AtomicReference<Call>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall.set(chain.call()); chain.proceed(chain.request())
        }.build()
        val cancellableUpdates = GitHubUpdates(http, server.url("/latest"))
        server.enqueue(release("v1.0.0").setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { cancellableUpdates.check("0.1.0") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(activeCall.get().isCanceled())
    }

    @Test fun semanticVersionsHandlePreviewNumbersAndIgnoreBuildMetadata() {
        val ordered = listOf("0.1.0-alpha", "0.1.0-alpha.2", "0.1.0-alpha.10", "0.1.0-beta", "0.1.0", "0.2.0", "1.0.0")
        ordered.zipWithNext().forEach { (left, right) ->
            assertTrue("$left < $right", ReleaseVersion.parse(left)!! < ReleaseVersion.parse(right)!!)
        }
        assertEquals(0, ReleaseVersion.parse("v0.1.0+build.2")!!.compareTo(ReleaseVersion.parse("0.1.0")!!))
        for (invalid in listOf("latest", "0.01.0", "0.1", "0.1.0-01", "0.1.0-", "999999999999.0.0")) {
            assertNull(invalid, ReleaseVersion.parse(invalid))
        }
    }
}
