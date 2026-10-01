package com.lmreader.core.storage.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun simplifiedChineseUsesChinese() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN", "zh-Hans-HK"))
            assertEquals(tag, "zh-Hans", AppLanguage.resolve(null, Locale.forLanguageTag(tag)))
    }
    @Test fun traditionalChineseUsesEnglish() {
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN"))
            assertEquals(tag, "en", AppLanguage.resolve(null, Locale.forLanguageTag(tag)))
    }
    @Test fun otherAndMissingSystemLanguagesUseEnglish() {
        for (tag in listOf("en-US", "ja-JP", "ko-KR", "fr-FR", "de-DE", "ar", "ru-RU", "und"))
            assertEquals(tag, "en", AppLanguage.resolve(null, Locale.forLanguageTag(tag)))
        assertEquals("en", AppLanguage.resolve(null, null))
    }
    @Test fun manualSelectionOverridesSystem() {
        assertEquals("zh-Hans", AppLanguage.resolve("zh-Hans", Locale.JAPANESE))
        assertEquals("en", AppLanguage.resolve("en", Locale.SIMPLIFIED_CHINESE))
        assertEquals("en", AppLanguage.resolve("unsupported", Locale.SIMPLIFIED_CHINESE))
    }
}
