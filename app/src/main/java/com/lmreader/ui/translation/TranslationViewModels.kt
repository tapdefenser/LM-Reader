package com.lmreader.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.GlossaryEntry
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.StyleMode
import com.lmreader.core.model.TranslationRepository
import com.lmreader.core.model.resolveTargetLanguage
import com.lmreader.core.model.resolveTranslationStyle
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 漫画译名字典（TR09）：`mangaId + 目标语言 + 原词 → 译名`。
 *
 * 目标语言取这部漫画**生效**的那个（漫画覆盖 → 全局默认），因为字典是按它分套的：
 * 显示"正在编辑哪一套"比让用户自己猜重要。
 */
class GlossaryViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val translationRepository: TranslationRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(GlossaryUiState())
    val state: StateFlow<GlossaryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun reload() {
        viewModelScope.launch {
            val settings = mangaRepository.translationSettings(mangaId)
            val globalTarget = preferences.translationTargetLanguage.first()
            val target = resolveTargetLanguage(settings, globalTarget)
            val entries = runCatching { translationRepository.glossary(mangaId, target) }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(loading = false, targetLanguage = target, entries = entries, error = null)
            }
        }
    }

    /** 新增或修改一条。人工录入（`manual = true`）因此不会被自动流程覆盖。 */
    fun save(source: String, target: String) {
        val trimmedSource = source.trim()
        val trimmedTarget = target.trim()
        if (trimmedSource.isEmpty() || trimmedTarget.isEmpty()) {
            _state.update { it.copy(message = "原词与译名都不能为空") }
            return
        }
        viewModelScope.launch {
            val language = _state.value.targetLanguage
            runCatching {
                translationRepository.upsertGlossary(
                    GlossaryEntry(
                        mangaId = mangaId,
                        targetLanguage = language,
                        source = trimmedSource,
                        target = trimmedTarget,
                        manual = true,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
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
            runCatching {
                translationRepository.deleteGlossary(entry.mangaId, entry.targetLanguage, entry.source)
            }.onFailure { error ->
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
                        mangaRepository = container.mangaRepository,
                        translationRepository = container.translationRepository,
                        preferences = container.preferences,
                    )
                }
            }
    }
}

data class GlossaryUiState(
    val loading: Boolean = true,
    /** 正在编辑哪一套字典（这部漫画生效的目标语言）。 */
    val targetLanguage: String = "简体中文",
    val entries: List<GlossaryEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

/**
 * 文风设置（覆盖链：漫画 → 分类 → 全局）。
 *
 * 界面要同时给出"这一层设了什么"和"最终生效的是哪一层"，否则用户看到一段文风却不知道
 * 它从哪来——覆盖链最容易出的问题就是这个。
 */
class TranslationStyleViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val shelfRepository: com.lmreader.core.model.ShelfRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(TranslationStyleUiState())
    val state: StateFlow<TranslationStyleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun reload() {
        viewModelScope.launch {
            val settings = mangaRepository.translationSettings(mangaId)
            val global = preferences.translationGlobalStyle.first()
            val categoryId = shelfRepository.categoryIdOf(mangaId)
            val category = categoryId?.let { id ->
                shelfRepository.observeCategories().first().firstOrNull { it.categoryId == id }
            }
            _state.update {
                it.copy(
                    loading = false,
                    settings = settings,
                    globalStyle = global,
                    categoryName = category?.name,
                    categoryStyle = category?.customStyle?.takeIf { text ->
                        category.styleMode == StyleMode.CUSTOM && text.isNotBlank()
                    },
                )
            }
        }
    }

    /** 模式只决定"是否使用漫画自己的文本"；文本为空时仍会往下回退（见 resolveTranslationStyle）。 */
    fun setMode(mode: StyleMode) {
        update { it.copy(styleMode = mode) }
    }

    fun setCustomStyle(text: String) {
        update { it.copy(customStyle = text) }
    }

    private fun update(transform: (com.lmreader.core.model.MangaTranslationSettings) -> com.lmreader.core.model.MangaTranslationSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch {
            runCatching { mangaRepository.updateTranslationSettings(mangaId, next) }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "保存失败") } }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer, mangaId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TranslationStyleViewModel(
                        mangaId = mangaId,
                        mangaRepository = container.mangaRepository,
                        shelfRepository = container.shelfRepository,
                        preferences = container.preferences,
                    )
                }
            }
    }
}

data class TranslationStyleUiState(
    val loading: Boolean = true,
    val settings: com.lmreader.core.model.MangaTranslationSettings =
        com.lmreader.core.model.MangaTranslationSettings(),
    val globalStyle: String = "",
    /** 这部作品所在分类的名字；null = 不在书架，也就没有分类这一层。 */
    val categoryName: String? = null,
    /** 分类那一层的自定义文风；null = 分类没设，继续回退全局。 */
    val categoryStyle: String? = null,
    val message: String? = null,
) {
    /** 最终生效的文风（覆盖链解析的结果）。 */
    val effectiveStyle: String
        get() = resolveTranslationStyle(settings, categoryStyle, globalStyle)

    /** 生效来源的说明，直接显示给用户。 */
    val effectiveSourceLabel: String
        get() = when {
            settings.styleMode == StyleMode.CUSTOM && !settings.customStyle.isNullOrBlank() ->
                "这部作品的自定义文风"

            !categoryStyle.isNullOrBlank() -> "分类「${categoryName ?: "未知"}」的文风"

            else -> "全局默认文风"
        }
}
