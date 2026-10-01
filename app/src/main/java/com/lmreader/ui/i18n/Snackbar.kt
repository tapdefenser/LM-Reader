package com.lmreader.ui.i18n

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import android.content.Context

suspend fun SnackbarHostState.showLocalizedSnackbar(
    context: Context,
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult = showSnackbar(UiTextTranslations.translate(context, message),
    actionLabel?.let { UiTextTranslations.translate(context, it) }, withDismissAction, duration)
