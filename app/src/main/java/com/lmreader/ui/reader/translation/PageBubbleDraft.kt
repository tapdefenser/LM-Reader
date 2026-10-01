package com.lmreader.ui.reader.translation

import com.lmreader.core.model.PageTranslatedRegion

/** A per-page draft. Reader edit mode itself is independent of this page snapshot. */
data class PageBubbleDraft(
    val saved: ReaderPageTranslation,
    val regions: List<PageTranslatedRegion> = saved.regions,
    val selectedId: String? = null,
    val undo: List<List<PageTranslatedRegion>> = emptyList(),
) {
    val dirty get() = regions != saved.regions
    val selected get() = regions.firstOrNull { it.region.id == selectedId }

    fun select(id: String?) = copy(selectedId = id?.takeIf { candidate -> regions.any { it.region.id == candidate } })
    fun editText(text: String): PageBubbleDraft {
        require(text.length <= 16384)
        val id = selected?.region?.id ?: return this
        return change(regions.map { if (it.region.id == id) it.copy(translatedText = text) else it })
    }
    fun deleteSelected(): PageBubbleDraft {
        val id = selected?.region?.id ?: return this
        return change(regions.filterNot { it.region.id == id }).copy(selectedId = null)
    }
    fun undoChange(): PageBubbleDraft {
        val previous = undo.lastOrNull() ?: return this
        return copy(regions = previous, undo = undo.dropLast(1)).select(selectedId)
    }
    fun discarded() = PageBubbleDraft(saved)
    private fun change(next: List<PageTranslatedRegion>) = if (next == regions) this else
        copy(regions = next, undo = (undo + listOf(regions)).takeLast(20))
}
