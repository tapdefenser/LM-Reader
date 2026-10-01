package com.lmreader.core.translation

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.*
import kotlinx.coroutines.flow.map
import java.io.File

class TranslationSourceStore(context: Context) {
    private val store = PreferenceDataStoreFactory.create(produceFile = {
        File(context.applicationContext.noBackupFilesDir, "translation_sources.preferences_pb")
    })
    val settings = store.data.map { prefs -> TranslationSourceSettings(
        prefs[SOURCE]?.let { TranslationDownloadSource.valueOf(it) } ?: TranslationDownloadSource.MOZILLA,
        prefs[BASE] ?: "", prefs[WIFI] ?: true,
    ) }
    suspend fun save(settings: TranslationSourceSettings) {
        val base = if (settings.source == TranslationDownloadSource.CUSTOM) TranslationModelCatalog.validateCustomBase(settings.customBaseUrl) else settings.customBaseUrl.trim()
        store.edit { it[SOURCE] = settings.source.name; it[BASE] = base; it[WIFI] = settings.wifiOnly }
    }
    private companion object {
        val SOURCE = stringPreferencesKey("source")
        val BASE = stringPreferencesKey("base")
        val WIFI = booleanPreferencesKey("wifi_only")
    }
}
