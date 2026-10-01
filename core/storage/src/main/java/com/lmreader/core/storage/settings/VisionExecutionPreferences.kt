package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.VisionExecutionSettings
import com.lmreader.core.model.OcrBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VisionExecutionPreferences(private val context: Context) {
    private fun read(p: Preferences) = VisionExecutionSettings(
        segConcurrency = (p[SEG_COUNT] ?: 0).coerceIn(0, 8),
        ocrConcurrency = (p[OCR_COUNT] ?: 0).coerceIn(0, 8),
        segGpu = p[SEG_GPU] ?: true,
        ocrBackend = OcrBackend.entries.firstOrNull { it.name == p[OCR_BACKEND] }
            ?: if (p[OCR_ACCEL] == true) OcrBackend.AUTO else OcrBackend.CPU)
    val settings = context.preferenceStore.data.map(::read)
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, VisionExecutionSettings())
    suspend fun update(change: (VisionExecutionSettings) -> VisionExecutionSettings) = context.preferenceStore.edit { p ->
        val old = read(p)
        val next = change(old)
        p[SEG_COUNT] = next.segConcurrency; p[OCR_COUNT] = next.ocrConcurrency
        p[SEG_GPU] = next.segGpu; p[OCR_BACKEND] = next.ocrBackend.name
        p.remove(OCR_ACCEL)
    }
    private companion object {
        val SEG_COUNT = intPreferencesKey("vision_seg_concurrency")
        val OCR_COUNT = intPreferencesKey("vision_ocr_concurrency")
        val SEG_GPU = booleanPreferencesKey("vision_seg_gpu")
        val OCR_ACCEL = booleanPreferencesKey("vision_ocr_hardware_acceleration")
        val OCR_BACKEND = stringPreferencesKey("vision_ocr_backend")
    }
}
