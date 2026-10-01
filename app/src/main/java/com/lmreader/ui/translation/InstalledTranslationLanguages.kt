package com.lmreader.ui.translation

import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.translation.TranslationModelCatalog
import java.util.Locale

fun TranslationModelCatalog.availableTargets(source: LocalTranslationLanguage): List<LocalTranslationLanguage> =
    languages.filter { it != source && runCatching { route(source, it) }.isSuccess }

fun TranslationModelCatalog.availableSources(): List<LocalTranslationLanguage> =
    languages.filter { availableTargets(it).isNotEmpty() }

/** Accept old display-name preferences; new choices persist canonical language tags. */
fun matchEngineLanguage(value: String?, languages: List<LocalTranslationLanguage>): LocalTranslationLanguage? =
    languages.firstOrNull { candidate ->
        value.equals(candidate.tag, true) || value.equals(candidate.nativeName, true) ||
            listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.getDefault()).any { locale ->
                platformLanguageNames(candidate, locale).toList().any { value.equals(it, true) }
            }
    }
