package com.lmreader.reliability

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.R
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.ui.i18n.UiTextTranslations
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LanguageIntegrationTest {
    private fun context(tag: String) = ApplicationProvider.getApplicationContext<android.content.Context>().let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }
    @Test fun otherSystemLanguagesHaveEnglishUiAndPlatformResources() {
        for (tag in listOf("en-US", "ja-JP", "ko-KR", "fr-FR", "ar", "zh-TW", "zh-Hant")) {
            val context = context(tag)
            assertEquals(tag, "All files access information", context.getString(R.string.lmreader_all_files_access_title))
            assertEquals(tag, "Pause all", context.getString(R.string.lmreader_task_pause))
            assertEquals(tag, "Settings", UiTextTranslations.translate(context, "设置"))
        }
    }
    @Test fun simplifiedChineseHasChineseUiAndPlatformResources() {
        for (tag in listOf("zh-CN", "zh-SG", "zh-Hans")) {
            val context = context(tag)
            assertEquals(tag, "全部文件访问说明", context.getString(R.string.lmreader_all_files_access_title))
            assertEquals(tag, "全部暂停", context.getString(R.string.lmreader_task_pause))
            assertEquals(tag, "设置", UiTextTranslations.translate(context, "设置"))
        }
    }
    @Test fun manualChoiceAndFollowSystemKeepTheirSelection() {
        val isolated = IsolatedApp(context("ja-JP"))
        val preferences = GeneralPreferences(isolated)
        preferences.setLanguageTag(null)
        assertEquals(null, preferences.languageTag); assertEquals("en", preferences.effectiveLanguageTag)
        preferences.setLanguageTag("zh-Hans")
        assertEquals("zh-Hans", preferences.effectiveLanguageTag)
        preferences.setLanguageTag("en")
        assertEquals("en", preferences.effectiveLanguageTag)
        preferences.setLanguageTag(null)
        assertEquals(null, preferences.languageTag); assertEquals("en", preferences.effectiveLanguageTag)
    }
}
