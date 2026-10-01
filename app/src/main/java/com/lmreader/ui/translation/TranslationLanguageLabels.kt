package com.lmreader.ui.translation

import android.icu.text.LocaleDisplayNames
import android.icu.util.ULocale
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.lmreader.R
import com.lmreader.core.model.LocalTranslationLanguage
import java.util.Locale

fun platformLanguageNames(language: LocalTranslationLanguage,displayLocale: Locale): Pair<String,String> {
    val identity=ULocale.forLanguageTag(language.tag)
    fun name(locale: ULocale)=LocaleDisplayNames.getInstance(locale,LocaleDisplayNames.DialectHandling.DIALECT_NAMES)
        .localeDisplayName(identity).ifBlank {language.tag}
    return name(identity) to name(ULocale.forLocale(displayLocale))
}

@Composable
fun languageLabel(language: LocalTranslationLanguage): String {
    val locale=LocalConfiguration.current.locales[0]
    val (native,localized)=remember(language,locale) {platformLanguageNames(language,locale)}
    return if(native.equals(localized,ignoreCase=true)) native else stringResource(R.string.local_mt_language_name,native,localized)
}
