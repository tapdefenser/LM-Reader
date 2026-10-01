package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TranslationCachePreferences(private val context: Context) {
    val megabytes = context.preferenceStore.data.map { (it[KEY] ?: 128).coerceIn(32, 1024) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, 128)
    suspend fun setMegabytes(value: Int) { context.preferenceStore.edit { it[KEY] = value.coerceIn(32, 1024) } }
    private companion object { val KEY = intPreferencesKey("translation_preprocess_cache_mb") }
}
