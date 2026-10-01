package com.lmreader.core.storage.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/** Synchronous locale read is needed in Activity.attachBaseContext before Compose starts. */
class GeneralPreferences(private val context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("general-settings", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(themeValue())
    val theme = _theme.asStateFlow()
    fun reload() { _theme.value = themeValue() }

    val languageTag: String?
        get() = preferences.getString("language", null)?.takeIf { it.isNotBlank() }

    /** Keep null as the stored "Follow system" selection; resolve it only for display. */
    val effectiveLanguageTag: String
        get() = AppLanguage.resolve(languageTag, context.resources.configuration.locales.let {
            if (it.isEmpty) null else it[0]
        })

    fun setLanguageTag(tag: String?) {
        require(tag == null || tag in setOf("zh-Hans", "en"))
        check(preferences.edit().putString("language", tag).commit())
    }

    fun setTheme(mode: AppThemeMode) {
        check(preferences.edit().putString("theme", mode.name).commit())
        _theme.value = mode
    }

    private fun themeValue(): AppThemeMode = runCatching {
        AppThemeMode.valueOf(preferences.getString("theme", "SYSTEM")!!)
    }.getOrDefault(AppThemeMode.SYSTEM)
}
