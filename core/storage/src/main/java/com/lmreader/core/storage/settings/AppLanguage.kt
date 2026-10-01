package com.lmreader.core.storage.settings

import java.util.Locale

/** One policy for the UI, platform resources, notifications and language names. */
object AppLanguage {
    const val SIMPLIFIED_CHINESE = "zh-Hans"
    const val ENGLISH = "en"

    fun resolve(selectedTag: String?, systemLocale: Locale?): String = when (selectedTag) {
        SIMPLIFIED_CHINESE, ENGLISH -> selectedTag
        null -> if (isSimplifiedChinese(systemLocale)) SIMPLIFIED_CHINESE else ENGLISH
        else -> ENGLISH
    }

    private fun isSimplifiedChinese(locale: Locale?): Boolean {
        if (locale?.language != "zh") return false
        return when (locale.script) {
            "Hans" -> true
            "Hant" -> false
            "" -> locale.country !in setOf("TW", "HK", "MO")
            else -> false
        }
    }
}
