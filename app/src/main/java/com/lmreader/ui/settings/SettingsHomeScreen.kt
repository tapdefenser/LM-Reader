package com.lmreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置首页（开发文档 14「多级设置」）。
 *
 * 本步只接通「图库与路径」一项——它是第一步的产品要求，也是引导页的同一页面
 * （开发文档 4 段首："此页既是首次引导，也是设置中的同一个页面，不维护两份逻辑"）。
 *
 * 其余分组按开发文档 14 的导航摘要列出，但**如实标注为未实现**：显示一个会打开
 * 空白页的设置项，比暂时不显示更糟——用户会以为自己配置错了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onOpenPaths: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsEntry(
                title = "图库与路径",
                subtitle = "两张路径表、子目录与类型、强制重新扫描索引",
                enabled = true,
                onClick = onOpenPaths,
            )
            HorizontalDivider()
            SettingsEntry(
                title = "图库与书架显示",
                subtitle = "排序与卡片字段（P1）",
                enabled = false,
                onClick = {},
            )
            SettingsEntry(
                title = "阅读器",
                subtitle = "方向、连续模式、缩放、裁白边、音量键（P2）",
                enabled = false,
                onClick = {},
            )
            SettingsEntry(
                title = "翻译配置",
                subtitle = "主 AI、OCR、三种翻译模式、语言与文风、遮罩与字体（P3）",
                enabled = false,
                onClick = {},
            )
            SettingsEntry(
                title = "导出",
                subtitle = "默认格式、目标目录、质量、缺页策略（P4）",
                enabled = false,
                onClick = {},
            )
            SettingsEntry(
                title = "存储与备份",
                subtitle = "缓存上限、清缓存、用户数据备份恢复（P4）",
                enabled = false,
                onClick = {},
            )
            SettingsEntry(
                title = "外观与语言",
                subtitle = "当前跟随系统深浅色；粉彩/深海/自定义颜色与应用语言在 P5",
                enabled = false,
                onClick = {},
            )
            SettingsEntry(
                title = "关于",
                subtitle = "版本 0.1.0-m1 · 开源许可与项目链接待补",
                enabled = false,
                onClick = {},
            )
        }
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (enabled) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            } else {
                Text(
                    text = "未实现",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    )
}
