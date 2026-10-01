package com.lmreader.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.GlossaryEntry
import com.lmreader.core.model.TranslationRepository
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 漫画译名字典（**只和漫画有关，与语言无关**；用户口径）。
 *
 * 因此这里不再有"正在编辑：目标语言 XXX"这一层：一部作品一份字典，跨章节共享。
 * 换目标语言时字典不变——同一部作品翻成两种语言时它会同时生效，这是这一版接受的代价
 * （见 `GlossaryEntry` 的说明）。
 */
class GlossaryViewModel(
    private val mangaId: String,
    private val translationRepository: TranslationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GlossaryUiState())
    val state: StateFlow<GlossaryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun reload() {
        viewModelScope.launch {
            val entries = runCatching { translationRepository.glossary(mangaId) }
                .getOrDefault(emptyList())
            _state.update { it.copy(loading = false, entries = entries, error = null) }
        }
    }

    /** 新增只填空缺；修改必须明确选中已有条目。 */
    fun save(source: String, target: String, originalSource: String? = null) {
        val trimmedSource = source.trim()
        val trimmedTarget = target.trim()
        if (trimmedSource.isEmpty() || trimmedTarget.isEmpty()) {
            _state.update { it.copy(message = "原词与译名都不能为空") }
            return
        }
        viewModelScope.launch {
            runCatching {
                val entry = GlossaryEntry(
                        mangaId = mangaId,
                        source = trimmedSource,
                        target = trimmedTarget,
                        manual = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                if (originalSource != null) translationRepository.editGlossary(originalSource, entry)
                else {
                    require(translationRepository.glossary(mangaId).none { it.source == trimmedSource }) { "该原词已有译名，请编辑已有条目" }
                    translationRepository.upsertGlossary(entry)
                }
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "保存失败") }
                return@launch
            }
            reload()
            _state.update { it.copy(message = "已保存：$trimmedSource → $trimmedTarget") }
        }
    }

    fun delete(entry: GlossaryEntry) {
        viewModelScope.launch {
            runCatching { translationRepository.deleteGlossary(entry.mangaId, entry.source) }
                .onFailure { error ->
                    _state.update { it.copy(message = error.message ?: "删除失败") }
                    return@launch
                }
            reload()
            _state.update { it.copy(message = "已删除：${entry.source}") }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer, mangaId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    GlossaryViewModel(
                        mangaId = mangaId,
                        translationRepository = container.translationRepository,
                    )
                }
            }
    }
}

data class GlossaryUiState(
    val loading: Boolean = true,
    val entries: List<GlossaryEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)
