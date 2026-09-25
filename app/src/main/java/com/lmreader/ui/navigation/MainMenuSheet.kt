package com.lmreader.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 主菜单：图库 / 书架 / 翻译队列 / 导出队列 / 设置（开发文档 3 导航图）。
 *
 * 队列入口显示活动任务数（开发文档 8.1「菜单」）。本步没有翻译与导出任务，
 * 因此数量恒为 0——这是"确实没有任务"，而不是"未接线"；点进去会看到明确的
 * 阶段说明页，而不是假进度或空壳控件（开发文档 17 完成标准）。
 */
@Composable
fun MainMenuSheet(
    onNavigate: (MainDestination) -> Unit,
    onDismiss: () -> Unit,
    activeTranslationTasks: Int = 0,
    activeExportTasks: Int = 0,
) {
    ModalDrawerSheet {
        Text(
            text = "LM-Reader",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        MainDestination.entries
            .filter { it != MainDestination.MENU }
            .forEach { destination ->
                val badge = when (destination) {
                    MainDestination.TRANSLATION_QUEUE -> activeTranslationTasks
                    MainDestination.EXPORT_QUEUE -> activeExportTasks
                    else -> 0
                }
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = if (badge > 0) {
                                "${destination.label}（$badge）"
                            } else {
                                destination.label
                            },
                        )
                    },
                    selected = false,
                    onClick = {
                        onNavigate(destination)
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Text(
            text = "本地图库 · 单/多章节 · 译文遮罩阅读",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

/** 主菜单可到达的目的地。 */
enum class MainDestination(val label: String) {
    MENU("菜单"),
    LIBRARY("图库"),
    BOOKSHELF("书架"),
    TRANSLATION_QUEUE("翻译队列"),
    EXPORT_QUEUE("导出队列"),
    SETTINGS("设置"),
}
