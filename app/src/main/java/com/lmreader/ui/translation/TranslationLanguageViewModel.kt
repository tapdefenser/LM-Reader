package com.lmreader.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.resolveSourceLanguage
import com.lmreader.core.model.resolveTargetLanguage
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 漫画级翻译语言设置。
 *
 * ## 为什么保存后要把"生效值"一起算出来显示
 *
 * 用户看到的必须是**最终会进请求的那个语言**，而不是"漫画设了什么"。留空 = 跟随全局，
 * 因此界面要同时显示漫画设置与全局默认——否则用户会以为设了日语却是别的（或者相反）。
 */
class TranslationLanguageViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(TranslationLanguageUiState())
    val state: StateFlow<TranslationLanguageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = mangaRepository.translationSettings(mangaId)
            val globalSource = preferences.translationSourceLanguage.first()
            val globalAutoDetect = preferences.translationAutoDetectSource.first()
            val globalTarget = preferences.translationTargetLanguage.first()
            _state.value = TranslationLanguageUiState(
                loading = false,
                settings = settings,
                globalSource = globalSource,
                globalAutoDetect = globalAutoDetect,
                globalTarget = globalTarget,
            )
        }
    }

    /** 源语言：空串 = 清除覆盖、跟随全局；"自动识别"由 [setAutoDetectSource] 单独设。 */
    fun setSourceLanguage(language: String) {
        val trimmed = language.trim()
        update { current ->
            current.copy(
                sourceLanguage = trimmed.takeIf { it.isNotEmpty() },
                // 手工选了语言就同时关掉自动识别：两个都开着时哪个生效要靠优先级解释，
                // 不如让选择本身互斥（与界面上的单选一致）。
                autoDetectSource = false,
            )
        }
    }

    fun setAutoDetectSource(enabled: Boolean) {
        update { it.copy(autoDetectSource = enabled) }
    }

    fun setTargetLanguage(language: String) {
        val trimmed = language.trim()
        update { it.copy(targetLanguage = trimmed.takeIf { it.isNotEmpty() }) }
    }

    private fun update(transform: (MangaTranslationSettings) -> MangaTranslationSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch {
            runCatching { mangaRepository.updateTranslationSettings(mangaId, next) }
                .onFailure { error ->
                    _state.update { it.copy(message = error.message ?: "保存失败") }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer, mangaId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TranslationLanguageViewModel(
                        mangaId = mangaId,
                        mangaRepository = container.mangaRepository,
                        preferences = container.preferences,
                    )
                }
            }
    }
}

data class TranslationLanguageUiState(
    val loading: Boolean = true,
    val settings: MangaTranslationSettings = MangaTranslationSettings(),
    val globalSource: String = "日语",
    val globalAutoDetect: Boolean = false,
    val globalTarget: String = "简体中文",
    val message: String? = null,
) {
    /** 生效的源语言标签：自动识别时说明是识别，而不是显示某个语言。 */
    val effectiveSourceLabel: String
        get() {
            val (language, auto) = resolveSourceLanguage(settings, globalSource)
            return if (auto) "自动识别" else language ?: globalSource
        }

    val effectiveTarget: String
        get() = resolveTargetLanguage(settings, globalTarget)
}
