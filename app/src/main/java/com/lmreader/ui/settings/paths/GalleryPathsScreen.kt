package com.lmreader.ui.settings.paths

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.storage.access.StorageAccess
import com.lmreader.core.storage.scan.ScanState
import com.lmreader.di.AppContainer
import com.lmreader.ui.settings.AllFilesAccessActivity
import kotlinx.coroutines.launch

/**
 * 图库路径配置页（开发文档 4，按用户要求修订交互）。
 *
 * 既是首次引导页，也是设置里的同一个页面（开发文档 4 段首："不维护两份逻辑"）。
 * 只有一张路径表（2026-09-25 由两张表合并：一次扫描同时识别图片与 CBZ/ZIP/PDF），
 * 底部操作条固定并且让出手势导航条高度——否则"下一步/完成"会被系统手势条挡住（真机已复现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPathsScreen(
    container: AppContainer,
    onNavigateToLibrary: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val isOnboarding = onBack == null
    val viewModel: GalleryPathsViewModel = viewModel(
        factory = GalleryPathsViewModel.factory(isOnboarding = isOnboarding, container = container),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 只有一个选择器：一次扫描同时解释图片与 CBZ/ZIP/PDF，不再区分"加到哪张表"。
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onDirectoryPicked(uri) }
    val editorPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onEditorDirectoryPicked(uri) }

    LaunchedEffect(state.hint) {
        val hint = state.hint ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(hint)
        viewModel.consumeHint()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isOnboarding) "设置图库路径" else "图库与路径") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            GalleryPathsBottomBar(
                state = state,
                onForceRescan = viewModel::forceRescan,
                onCancelRescan = viewModel::cancelRescan,
                onComplete = { viewModel.complete(onNavigateToLibrary) },
                completeLabel = if (isOnboarding) "下一步" else "完成",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 授权状态横幅：每次启动检测的结果直接显示在最上方。
            // 未获得「全部文件访问」是可选项（仍可用系统选择器逐目录授权），
            // 有路径失效则是必须处理的问题，因此两者用不同语气与颜色。
            val bannerMessage = when {
                state.revokedCount > 0 ->
                    "有 {state.revokedCount} 条路径的授权已失效，需要重新选择目录"

                state.allFilesAccessAvailable && !state.allFilesAccess ->
                    "未获得「全部文件访问」：系统选择器无法选中存储根、Download 根与 Android/data"

                else -> null
            }
            AccessStatusBanner(
                message = bannerMessage,
                isError = state.revokedCount > 0,
                actionLabel = if (state.revokedCount > 0) "重试检测" else "去开启",
                onAction = {
                    if (state.revokedCount > 0) {
                        viewModel.refreshAccessStatus()
                    } else {
                        context.startActivity(StorageAccess.allFilesAccessIntent(context))
                    }
                },
                secondaryActionLabel = if (bannerMessage == null || state.revokedCount > 0) {
                    null
                } else {
                    "为什么需要？"
                },
                onSecondaryAction = {
                    context.startActivity(Intent(context, AllFilesAccessActivity::class.java))
                },
            )
            if (isOnboarding) {
                Text(
                    text = "选择存放漫画的文件夹：选完会立即加入列表并开始扫描。" +
                        "至少有一条路径后即可进入图库，扫描在后台继续，不必等它结束。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            SourceTable(
                table = state.table,
                scanStates = state.scanStates,
                onAdd = { directoryPicker.launch(null) },
                onOpenEditor = { viewModel.openEditor(it) },
                onToggleRecursive = { id, value -> viewModel.setRecursive(id, value) },
                onChangeMode = { id, mode -> viewModel.setMode(id, mode) },
                onDelete = { viewModel.requestDelete(it) },
                onMove = { from, to -> viewModel.moveRow(from, to) },
                onShowDiagnostics = { viewModel.openDiagnostics(it) },
            )

            Text(
                text = "一次扫描同时识别目录里的图片与 CBZ/ZIP/PDF：图片章节取叶子图片目录，" +
                    "归档章节取直接的压缩包/PDF 文件，不需要把同一个目录加两遍。" +
                    "点击某一行可以改名或重新选择该目录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    state.editor?.let { editor ->
        SourceEditDialog(
            editor = editor,
            onNameChange = viewModel::onEditorNameChange,
            onReselect = { editorPicker.launch(null) },
            onConfirm = viewModel::saveEditor,
            onDismiss = viewModel::closeEditor,
        )
    }

    // 扫描诊断：把原始失败原因完整展示出来。
    // 真机（MIUI）会吞掉应用自己的 logcat，如果只把原因写进日志，
    // 用户和开发者看到的现象就只是"扫不出东西"，无从判断是权限、IO 还是格式问题
    // （开发文档 3「错误显示可操作原因」、4.1「点击失败状态查看原因」）。
    val diagnosticsSource = state.diagnosticsTarget
    if (diagnosticsSource != null) {
        val scan = state.scanStates[diagnosticsSource.sourceId]
        AlertDialog(
            onDismissRequest = viewModel::closeDiagnostics,
            title = { Text("扫描诊断") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = diagnosticsSource.displayName ?: diagnosticsSource.displayPath,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "来源状态：${diagnosticsSource.permission} / " +
                            "${diagnosticsSource.lastScanStatus ?: "未扫描"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "已找到 ${scan?.discovered ?: 0} 部漫画，" +
                            "${scan?.chapters ?: 0} 章，已遍历 ${scan?.visited ?: 0} 个目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "章节探测 ${scan?.leafProbes ?: 0} 次" +
                            "（每部漫画约 1–2 次；明显偏大说明跳过规则失效）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "已隐藏陈旧卡片 ${scan?.staleMarked ?: 0} 张" +
                            "（上次完整扫描没再发现的旧卡片；只隐藏不删除，重新发现后自动回来）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "访问方式：${scan?.accessMode ?: "未知"}" +
                            if (scan?.currentPath != null) "，最后枚举：${scan.currentPath}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val lines = scan?.diagnostics.orEmpty()
                    if (lines.isEmpty()) {
                        Text(
                            text = diagnosticsSource.lastScanError ?: "本次扫描没有记录到失败或诊断信息。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        lines.forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeDiagnostics) { Text("关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(buildDiagnosticsReport(diagnosticsSource, scan))
                    },
                ) { Text("复制诊断") }
            },
        )
    }

    state.pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("移除这条路径？") },
            text = {
                Text(
                    text = "${pending.source.displayName ?: pending.source.displayPath}\n\n" +
                        "只会移除索引来源：源文件、书架收藏与已有译文都不会被删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removed = viewModel.confirmDelete()
                        if (removed != null) {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "已移除路径（未删除任何文件）",
                                    actionLabel = "撤销",
                                )
                                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
                            }
                        }
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("取消") }
            },
        )
    }
}

/**
 * 路径编辑弹窗（用户要求的"弹窗 + 手动输入"）。
 *
 * 可编辑的是**显示名称**：SAF 只给出 `primary:Tachiyomi/downloads` 这类 documentId，
 * 真实绝对路径不保证可解析（开发文档 4.1），因此让用户自己起一个认得的名。
 *
 * 系统路径本身只读：树 URI 是授权句柄，手打路径字符串拿不到访问权限。
 * 需要换目录时用"重新选择目录"，它会重新走一次系统选择器。
 *
 * 取消不修改任何已保存数据（开发文档 4.1「取消不修改已保存数据」）。
 */
@Composable
private fun SourceEditDialog(
    editor: SourceEditDialogState,
    onNameChange: (String) -> Unit,
    onReselect: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图库路径") },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.nameInput,
                    onValueChange = onNameChange,
                    label = { Text("名称（可留空，留空显示下面的路径）") },
                    singleLine = true,
                    isError = !editor.nameValid,
                    supportingText = {
                        Text("${editor.nameInput.length}/${SourceEditDialogState.MAX_NAME_LENGTH}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = editor.effectiveSystemPath,
                    onValueChange = { /* 只读：路径必须来自系统目录选择器 */ },
                    label = { Text("系统路径（只读）") },
                    readOnly = true,
                    maxLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(editor.effectiveSystemPath)) },
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制路径")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                editor.providerLabel?.let {
                    Text(
                        text = "提供方：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (editor.pendingTreeUri != null) {
                    Text(
                        text = "保存后将改为上面这个目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                editor.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(
                    onClick = onReselect,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("重新选择目录") }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = editor.nameValid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GalleryPathsBottomBar(
    state: GalleryPathsUiState,
    onForceRescan: () -> Unit,
    onCancelRescan: () -> Unit,
    onComplete: () -> Unit,
    completeLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // enableEdgeToEdge 之后底部条会与手势导航条重叠，显式让出导航栏高度。
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HorizontalDivider()
        // 不确定进度条：扫描没有总数，因此只表示「在工作」，不表示完成比例
        // （开发文档 8.1「不伪造总百分比」）。
        if (state.overall.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = state.overall.statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 「正在扫描：<路径>」只在有唯一答案时显示（单源扫描）；
            // 多源并行时不挑一个出来，避免给出错误信息。
            state.overall.currentPathLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { if (state.overall.running) onCancelRescan() else onForceRescan() },
                enabled = state.hasAnySource,
            ) {
                Icon(
                    imageVector = if (state.overall.running) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.overall.running) "取消扫描" else "强制重新扫描索引")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onComplete, enabled = state.canProceed) {
                Text(completeLabel)
            }
        }
    }
}

/** 设置里的入口（"完成"替代"下一步"，返回原页面，后台扫描继续）。 */
@Composable
fun GalleryPathsSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    GalleryPathsScreen(
        container = container,
        onNavigateToLibrary = onBack,
        onBack = onBack,
    )
}

/**
 * 诊断报告文本：用于"复制诊断"。
 *
 * 报告刻意不含任何文件内容或标题，只有路径、状态与异常文本——用户把这段发给
 * 开发者时不会顺带泄露漫画内容（开发文档 14「日志脱敏」的同一条原则）。
 */
private fun buildDiagnosticsReport(
    source: com.lmreader.core.model.LibrarySource,
    scan: ScanState?,
): AnnotatedString = AnnotatedString(
    buildString {
        appendLine("LM-Reader 扫描诊断")
        appendLine("来源: ${source.displayPath}")
        appendLine("类型: ${source.kind} / ${source.mode} / 递归=${source.recursive}")
        appendLine("授权: ${source.permission}")
        appendLine("上次扫描: ${source.lastScanStatus ?: "未扫描"} @ ${source.lastScanAt ?: "-"}")
        appendLine("汇总错误: ${source.lastScanError ?: "-"}")
        appendLine("已发现: ${scan?.discovered ?: 0}，已访问目录: ${scan?.visited ?: 0}")
        appendLine("已隐藏陈旧卡片: ${scan?.staleMarked ?: 0}（只隐藏不删除）")
        appendLine("--- 详情 ---")
        scan?.diagnostics.orEmpty().forEach { appendLine(it) }
    },
)
