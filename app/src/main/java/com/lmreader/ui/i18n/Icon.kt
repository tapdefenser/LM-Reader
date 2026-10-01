package com.lmreader.ui.i18n

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val context = LocalContext.current
    MaterialIcon(imageVector, contentDescription?.let { UiTextTranslations.translate(context, it) }, modifier, tint)
}

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val context = LocalContext.current
    MaterialIcon(painter, contentDescription?.let { UiTextTranslations.translate(context, it) }, modifier, tint)
}

@Composable
fun Icon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val context = LocalContext.current
    MaterialIcon(bitmap, contentDescription?.let { UiTextTranslations.translate(context, it) }, modifier, tint)
}
