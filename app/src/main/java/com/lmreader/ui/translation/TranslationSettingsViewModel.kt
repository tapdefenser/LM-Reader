package com.lmreader.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.StyleMode
import com.lmreader.core.model.resolveSourceLanguage
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
 * 翻译设置（**一个页面装下语言、文风与译名入口**；用户口径）。
 *
 * ## 为什么合成一页
 *
 * 语言、文风、译名原来各占一个入口（⋮ 里三行），但它们回答的是同一件事：
 * "这部作品要怎么翻"。合成一页之后，用户从"翻译所选"被拦进来时能一次把三样都填完，
 * 而不是在三个页面之间来回找。
 *
 * ## 语言为什么不给缺省值
 *
 * 用户口径：翻译语言没设置过就是**空的**，首次进来必须自己填。给一个默认的
 * "日语 → 简体中文"看着友好，实际会让用户跳过这一步，直到发现整章译文都不对。
 * 因此 [TranslationSettingsUiState.languageConfigured] 为 false 时详情页会拦住翻译并
 * 把他带到这一页。
 */
class TranslationSettingsViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val shelfRepository: ShelfRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(TranslationSettingsUiState())
    val state: StateFlow<TranslationSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /**
     * 重新读一遍设置。
     *
     * 每次回到这一页都要重读：用户可以只填全局默认（不改漫画覆盖），那时生效值变了
     * 但漫画设置没变，只观察 [MangaTranslationSettings] 是看不出来的。
     */
    fun reload() {
        viewModelScope.launch {
            val settings = mangaRepository.translationSettings(mangaId)
            val globalSource = preferences.translationSourceLanguage.first()
            val globalAutoDetect = preferences.translationAutoDetectSource.first()
            val globalTarget = preferences.translationTargetLanguage.first()
            val globalStyle = preferences.translationGlobalStyle.first()
            val categoryId = shelfRepository.categoryIdOf(mangaId)
            val category = categoryId?.let { id ->
                shelfRepository.observeCategories().first().firstOrNull { it.categoryId == id }
            }
            _state.update {
                it.copy(
                    loading = false,
                    settings = settings,
                    globalSource = globalSource,
                    globalAutoDetect = globalAutoDetect,
                    globalTarget = globalTarget,
                    globalStyle = globalStyle,
                    categoryName = category?.name,
                    categoryStyle = category?.customStyle?.takeIf { text -> text.isNotBlank() },
                )
            }
        }
    }

    /** 源语言：空串 = 清除覆盖、跟随全局。 */
    fun setSourceLanguage(language: String) {
        val trimmed = language.trim()
        update { current ->
            current.copy(
                sourceLanguage = trimmed.takeIf { it.isNotEmpty() },
                // 手工选了语言就同时关掉自动识别：两个都开着时"哪个生效"要靠优先级解释，
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

    /**
     * 文风：一个文本框，**留空 = 自动往下退化**（用户口径："如果留空就是自动应用分类"）。
     *
     * 因此这里不再有"用自定义 / 跟随分类"的单选，也总是写 `StyleMode.CUSTOM`——
     * 解析只看文本是否为空（见 `resolveTranslationStyle`），模式列只为兼容旧行保留。
     */
    fun setStyle(text: String) {
        update { it.copy(styleMode = StyleMode.CUSTOM, customStyle = text) }
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
                    TranslationSettingsViewModel(
                        mangaId = mangaId,
                        mangaRepository = container.mangaRepository,
                        shelfRepository = container.shelfRepository,
                        preferences = container.preferences,
                    )
                }
            }
    }
}

data class TranslationSettingsUiState(
    val loading: Boolean = true,
    val settings: MangaTranslationSettings = MangaTranslationSettings(),
    /** null = 全局还没设过（用户口径：不给缺省值）。 */
    val globalSource: String? = null,
    val globalAutoDetect: Boolean = false,
    val globalTarget: String? = null,
    val globalStyle: String = "",
    /** 这部作品所在分类的名字；null = 不在书架，没有分类这一层。 */
    val categoryName: String? = null,
    /** 分类那一层的文风；null = 分类没设，继续回退全局。 */
    val categoryStyle: String? = null,
    val message: String? = null,
) {
    /** 生效的源语言：null = 还没定（且没开自动识别）。 */
    val effectiveSource: String?
        get() = resolveSourceLanguage(settings, globalSource).let { (language, auto) ->
            if (auto) AUTO_DETECT_LABEL else language
        }

    val autoDetectSource: Boolean
        get() = resolveSourceLanguage(settings, globalSource).second

    /** 生效的目标语言；null = 未设置。 */
    val effectiveTarget: String?
        get() = resolveTargetLanguage(settings, globalTarget)

    /** 语言是否已经配到"可以开始翻译"的程度，决定详情页是否要拦住翻译。 */
    val languageConfigured: Boolean
        get() = com.lmreader.core.model.translationSetupComplete(
            targetLanguage = effectiveTarget,
            sourceLanguage = settings.sourceLanguage ?: globalSource,
            autoDetectSource = autoDetectSource,
        )

    /** 最终生效的文风（漫画 → 分类 → 全局）。 */
    val effectiveStyle: String
        get() = resolveTranslationStyle(settings, categoryStyle, globalStyle)

    /** 生效来源的说明，显示在文风框下面。 */
    val effectiveStyleSource: String
        get() = when {
            !settings.customStyle.isNullOrBlank() -> "正在使用这部作品的文风"
            !categoryStyle.isNullOrBlank() -> "留空，正在使用分类「${categoryName ?: "未知"}」的文风"
            else -> "留空，正在使用全局默认文风"
        }

    val sourceLabel: String
        get() = effectiveSource ?: "未设置"

    val targetLabel: String
        get() = effectiveTarget ?: "未设置"
}

/** 自动识别在界面上的显示名（它不是一种语言，因此不能和语言名混在一列里）。 */
internal const val AUTO_DETECT_LABEL = "自动识别"
