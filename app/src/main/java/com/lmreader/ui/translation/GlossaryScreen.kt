package com.lmreader.ui.translation

import com.lmreader.ui.i18n.showLocalizedSnackbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.GlossaryEntry
import com.lmreader.di.AppContainer

/**
 * 译名管理（开发文档 TR09）：这部漫画在**生效目标语言**下的键值字典。
 *
 * ## 为什么键里带目标语言
 *
 * "译成简中的名字"与"译成英文的名字"是两套东西（同一个日文原词译法完全不同）。
 * 因此字典按 `mangaId + 目标语言` 分开，页面顶部明确写出正在编辑哪一套——
 * 否则用户会在英文那套里录中文译名而毫无察觉。
 *
 * ## 人工值不会被自动覆盖
 *
 * 这里写入的条目一律 `manual = true`：将来自动抽取的词表只能新增或更新自动条目，
 * 不会把用户逐字校对过的译名冲掉（仓储层保证，见 TranslationRepositoryImpl）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(
    container: AppContainer,
    mangaId: String,
    onBack: () -> Unit,
    viewModel: GlossaryViewModel = viewModel(
        key = "glossary-$mangaId",
        factory = GlossaryViewModel.factory(container, mangaId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<GlossaryEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showLocalizedSnackbar(context, it)
            viewModel.consumeMessage()
        }
    }

    if (creating || editing != null) {
        val entry = editing
        GlossaryEditDialog(
            title = if (entry == null) "新增译名" else "修改译名",
            initialSource = entry?.source.orEmpty(),
            initialTarget = entry?.target.orEmpty(),
            onConfirm = { source, target ->
                viewModel.save(source, target, entry?.source)
                creating = false
                editing = null
            },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("译名管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新增译名")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "这部作品的原词 → 译名对照表（${state.entries.size} 条）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            Text(
                text = "这些译名会要求模型优先采用；人工录入的条目不会被自动抽取覆盖。" +
                    "字典只跟这部作品有关，不随目标语言变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.entries.isEmpty() && !state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("这套字典还是空的")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { creating = true }) { Text("新增第一条") }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.entries, key = { it.source }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.source) },
                            supportingContent = { Text(entry.target) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { editing = entry }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "修改")
                                    }
                                    IconButton(onClick = { viewModel.delete(entry) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                                    }
                                }
                            },
                            modifier = Modifier.clickable { editing = entry },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun GlossaryEditDialog(
    title: String,
    initialSource: String,
    initialTarget: String,
    onConfirm: (source: String, target: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf(initialSource) }
    var target by remember { mutableStateOf(initialTarget) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    singleLine = true,
                    label = { Text("原词（作品里的写法）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    singleLine = true,
                    label = { Text("译名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(source, target) },
                enabled = source.isNotBlank() && target.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
