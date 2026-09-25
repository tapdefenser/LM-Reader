package com.lmreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题：本阶段提供跟随系统的浅色/深色两套配色。
 *
 * 开发文档 14「外观与语言」还要求粉彩、深海与自定义颜色；这些属于设置页
 * 落地时的扩展项，先保留单一入口以便后续替换为可配置方案，不摆放未接线的开关。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4B5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9E4F2),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF4A6267),
    surfaceVariant = Color(0xFFDCE4E7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DCDDC),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFB9E4F2),
    secondary = Color(0xFFB1CBD0),
)

@Composable
fun LmReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
