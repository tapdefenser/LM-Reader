package com.lmreader.ui.settings

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.model.LayoutMode
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.ExportFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = container.exportSettings
    val multi by settings.multiChapter.collectAsStateWithLifecycle()
    val single by settings.singleChapter.collectAsStateWithLifecycle()
    val format by settings.format.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(LayoutMode.MULTI_CHAPTER) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.setDestination(choosing, uri)
        }.onFailure { error = it.message ?: "无法保存目录授权" }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("导出设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择导出目录", style = MaterialTheme.typography.titleMedium)
            Text("多章节和单章节分别保存到所选目录。PNG/JPEG 按章节创建图片文件夹；CBZ 按章节生成压缩包。已翻译页面合成译文。")
            Text("导出格式", style = MaterialTheme.typography.titleSmall)
            ExportFormat.entries.forEach { choice ->
                ListItem(headlineContent = { Text(choice.label) },
                    supportingContent = { Text(if (choice == ExportFormat.CBZ) "每章一个 CBZ 文件" else "每章一个 ${choice.label} 图片文件夹") },
                    leadingContent = { RadioButton(selected = format == choice, onClick = null) },
                    modifier = Modifier.fillMaxWidth().clickable { settings.setFormat(choice) })
            }
            listOf(Triple(LayoutMode.MULTI_CHAPTER, "多章节路径", multi),
                Triple(LayoutMode.SINGLE_CHAPTER, "单章节路径", single)).forEach { (mode, title, value) ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(value?.let { Uri.parse(it).lastPathSegment ?: it } ?: "尚未选择",
                            style = MaterialTheme.typography.bodySmall, localize = value == null)
                        if (value == null) context.getSharedPreferences("export-settings", 0)
                            .getString(if (mode == LayoutMode.MULTI_CHAPTER) "previous-multi" else "previous-single", null)
                            ?.let { Text("备份中的目录：$it · 请重新选择", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { choosing = mode; picker.launch(null) }) { Text("选择目录") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
