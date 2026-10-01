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
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lmreader.R
import androidx.compose.ui.unit.dp

/** 设置首页；尚未接入的功能显示明确状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onOpenPaths: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenGeneral: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAbout: () -> Unit,
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
                title = "通用",
                subtitle = "应用语言与主题",
                enabled = true,
                onClick = onOpenGeneral,
            )
            SettingsEntry(
                title = "API 与翻译引擎",
                subtitle = "LLM、机翻语言包、OCR 与 SEG（并发、加速、预处理缓存）",
                enabled = true,
                onClick = onOpenApi,
            )
            SettingsEntry(
                title = "图库与路径",
                subtitle = "两张路径表、子目录与类型、强制重新扫描索引",
                enabled = true,
                onClick = onOpenPaths,
            )
            SettingsEntry(
                title = "阅读器",
                subtitle = "预载页数、缓存章节数（下次打开生效）、加载原图（立即生效）",
                enabled = true,
                onClick = onOpenReader,
            )
            SettingsEntry(
                title = "导出设置",
                subtitle = "多章节与单章节导出路径",
                enabled = true,
                onClick = onOpenExport,
            )
            SettingsEntry("备份与恢复", "设置、书架、进度、译文和人工编辑", true, onOpenBackup)
            SettingsEntry("后台任务与通知", "后台运行、中断恢复与临时文件清理", true, onOpenTasks)
            SettingsEntry("关于", "版本、GitHub 与检查更新", true, onOpenAbout)
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
