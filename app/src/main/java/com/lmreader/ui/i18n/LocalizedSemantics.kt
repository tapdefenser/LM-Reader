package com.lmreader.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun Modifier.localizedContentDescription(value: String): Modifier {
    val context = LocalContext.current
    val localized = UiTextTranslations.translate(context, value)
    return semantics { contentDescription = localized }
}
