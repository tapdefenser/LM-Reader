package com.lmreader.core.api

import com.lmreader.core.model.ApiFormat
import kotlin.test.*
import org.junit.Test

class ApiStreamTest {
    @Test fun `SSE respects frames comments and EOF`() {
        val d = SseDecoder()
        assertNull(d.line(": ping")); assertNull(d.line("event: token"))
        assertNull(d.line("data: first")); assertNull(d.line("data:second"))
        assertEquals("first\nsecond", d.line("")); assertNull(d.flush())
        d.line("data: last"); assertEquals("last", d.flush())
    }
    @Test fun `Chat separates thinking body and finish`() {
        val events = ApiStreamParser.event(ApiFormat.CHAT, "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"想\",\"content\":\"你好😀\"},\"finish_reason\":\"stop\"}]}")
        assertEquals(listOf(ApiStreamEvent.Text("想", true), ApiStreamEvent.Text("你好😀"), ApiStreamEvent.Finished("stop")), events)
        assertEquals(listOf(ApiStreamEvent.Finished()), ApiStreamParser.event(ApiFormat.CHAT, "[DONE]"))
        assertTrue(ApiStreamParser.event(ApiFormat.CHAT, "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}").isEmpty())
    }
    @Test fun `Responses and Gemini decode increments`() {
        assertEquals(listOf(ApiStreamEvent.Text("ok")), ApiStreamParser.event(ApiFormat.RESPONSES, "{\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}"))
        assertEquals(listOf(ApiStreamEvent.Finished("completed")), ApiStreamParser.event(ApiFormat.RESPONSES, "{\"type\":\"response.completed\"}"))
        assertEquals(listOf(ApiStreamEvent.Text("think", true), ApiStreamEvent.Text("body"), ApiStreamEvent.Finished("STOP")), ApiStreamParser.event(ApiFormat.GEMINI, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"think\",\"thought\":true},{\"text\":\"body\"}]},\"finishReason\":\"STOP\"}]}"))
    }
    @Test fun `malformed and explicit errors fail`() {
        assertFailsWith<ApiException> { ApiStreamParser.event(ApiFormat.CHAT, "truncated{") }
        assertFailsWith<ApiException> { ApiStreamParser.event(ApiFormat.CHAT, "{\"error\":{\"message\":\"bad\"}}") }
        assertFailsWith<ApiException> { ApiStreamParser.event(ApiFormat.RESPONSES, "{\"type\":\"response.failed\"}") }
    }
}
