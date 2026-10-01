package com.lmreader.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Icon
import com.lmreader.ui.i18n.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(container: AppContainer, onBack: () -> Unit, onPaths: () -> Unit) {
    val manager = container.backups
    val busy by manager.busy.collectAsStateWithLifecycle()
    val failure by manager.failure.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    fun run(restore: Boolean, uri: Uri) { scope.launch {
        message = null
        try {
            if (restore) manager.restore(uri) else manager.create(uri)
            message = if (restore) "恢复完成。请重新授权图库与导出目录、填写 API 密钥，再手动继续任务。重新打开应用后语言设置生效。" else "备份完成"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = "${if (restore) "恢复" else "备份"}失败：${error.message}" }
    } }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { run(false, it) } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri }
    BackHandler(busy) { }
    Scaffold(topBar = { TopAppBar(title = { Text("备份与恢复") }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("备份包含应用与阅读设置、图库索引、书架分类、阅读进度、译名字典、译文及人工编辑、工作流和 API 配置。")
            Text("API 密钥、请求日志、原始漫画、模型包和导出产物不包含在备份中。目录授权不能跨设备迁移，需要重新选择原目录。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { backup.launch("LM-Reader-${java.time.LocalDate.now()}.zip") }, enabled = !busy) { Text("创建备份") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text("从备份恢复") }
            OutlinedButton(onClick = onPaths, enabled = !busy) { Text("图库与路径") }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请保持此页面打开…") }
            (failure ?: message)?.let { Text(it) }
        }
    }
    pending?.let { uri -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("恢复备份") },
        text = { Text("恢复会替换本机设置、书架、进度、字典和译文，并暂停翻译、导出与扫描。校验失败不会替换数据；恢复前会保留本机回滚副本。") },
        confirmButton = { TextButton(onClick = { pending = null; run(true, uri) }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }) }
}
