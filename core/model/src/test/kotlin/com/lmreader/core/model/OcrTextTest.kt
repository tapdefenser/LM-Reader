package com.lmreader.core.model

import org.junit.Test
import kotlin.test.*

class OcrTextTest {
    @Test fun `layout lines become a continuous english sentence`() {
        assertEquals("I thought you would come.", joinOcrText(listOf(" I thought\r\n", "you   would", "come", "."), LocalOcrLanguage.ENGLISH))
    }
    @Test fun `chinese and japanese wraps do not insert spaces`() {
        assertEquals("你好，世界！", joinOcrText(listOf("你好，", "世界！"), LocalOcrLanguage.CHINESE_SIMPLIFIED))
        assertEquals("こんにちは世界。", joinOcrText(listOf("こんにちは", "世界。"), LocalOcrLanguage.JAPANESE))
        assertEquals("私の名前はJohn Smithです。", joinOcrText(listOf("私の名前は", "John", "Smith", "です。"), LocalOcrLanguage.JAPANESE))
    }
    @Test fun `korean requires word spaces even when both boundaries are hangul`() {
        assertEquals("안녕 세상", joinOcrText(listOf("안녕", "세상"), LocalOcrLanguage.KOREAN))
    }
    @Test fun `raw layout remains available while translation input removes wraps`() {
        val lines = listOf("HELLO", "WORLD").mapIndexed { i, text -> OcrLine("$i", PixelRect(0f, 0f, 10f, 10f), text, 1f) }
        val result = LocalOcrResult("p", 100, 100, LocalOcrLanguage.ENGLISH, lines, 0)
        assertEquals("HELLO\nWORLD", result.text)
        assertEquals("HELLO WORLD", result.translationText)
        assertEquals("", joinOcrText(listOf("\r\n", " "), LocalOcrLanguage.JAPANESE))
    }
}
