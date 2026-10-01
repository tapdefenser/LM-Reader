package com.lmreader.ui.queue

import android.content.Context
import android.net.Uri
import com.lmreader.core.model.LayoutMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExportFormat(val label: String, val extension: String) {
    PNG("PNG", "png"), JPEG("JPEG", "jpg"), CBZ("CBZ", "cbz")
}

/** Separate SAF destinations for the two library layouts. */
class ExportSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("export-settings", Context.MODE_PRIVATE)
    private val multi = MutableStateFlow(preferences.getString("multi", null))
    private val single = MutableStateFlow(preferences.getString("single", null))
    val multiChapter = multi.asStateFlow()
    val singleChapter = single.asStateFlow()
    private val selectedFormat = MutableStateFlow(runCatching {
        ExportFormat.valueOf(preferences.getString("format", ExportFormat.CBZ.name)!!)
    }.getOrDefault(ExportFormat.CBZ))
    val format = selectedFormat.asStateFlow()
    fun reload() {
        multi.value = preferences.getString("multi", null); single.value = preferences.getString("single", null)
        selectedFormat.value = ExportFormat.valueOf(preferences.getString("format", "CBZ")!!)
    }

    fun destination(mode: LayoutMode): Uri? =
        (if (mode == LayoutMode.SINGLE_CHAPTER) single.value else multi.value)?.let(Uri::parse)

    fun setDestination(mode: LayoutMode, uri: Uri) {
        val key = if (mode == LayoutMode.SINGLE_CHAPTER) "single" else "multi"
        preferences.edit().putString(key, uri.toString()).apply()
        if (mode == LayoutMode.SINGLE_CHAPTER) single.value = uri.toString() else multi.value = uri.toString()
    }

    fun setFormat(value: ExportFormat) {
        preferences.edit().putString("format", value.name).apply()
        selectedFormat.value = value
    }
}
