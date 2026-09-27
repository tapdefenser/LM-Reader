package com.lmreader.ui.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.ChapterKind
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.CoverImage
import com.lmreader.ui.common.CoverRequest

/**
 * 点章节行时从哪一页打开。
 *
 * - 这一章**就是进度里正在读的那一章**且读到过非首页 → 从那一页继续；
 * - 否则返回 null，表示"从头开始"。
 *
 * 为什么用 null 而不是 0 表示"从头"：导航参数是 Int，而 0 是合法的第一页。
 * 用可空把"用户明确要第一页"与"没有指定"分开，避免以后加"跳到某页"时混淆。
 */
private fun resumePageFor(isCurrentChapter: Boolean, readPage: Int): Int? =
    if (isCurrentChapter && readPage > 0) readPage else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    container: AppContainer,
    mangaId: String,
    onBack: () -> Unit,
    onReadChapter: (chapterId: String?, startPage: Int?) -> Unit,
    viewModel: MangaDetailViewModel = viewModel(
        key = mangaId,
        factory = MangaDetailViewModel.factory(container, mangaId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCategoryDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("选择书架分类") },
            text = {
                LazyColumn {
                    items(state.categories, key = { it.categoryId }) { category ->
                        TextButton(
                            onClick = {
                                showCategoryDialog = false
                                viewModel.addToShelf(category.categoryId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(category.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.manga?.displayName ?: "漫画详情", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::reload) { Text("重试") }
                }
            }

            state.manga != null -> DetailContent(
                state = state,
                contentPadding = padding,
                onSync = viewModel::syncChapters,
                onShelfClick = {
                    if (state.inShelf) viewModel.removeFromShelf() else showCategoryDialog = true
                },
                onReadChapter = onReadChapter,
                onToggleChapterSort = {
                    viewModel.setChapterSortDescending(!state.chapterSortDescending)
                },
            )
        }
    }
}

@Composable
private fun DetailContent(
    state: MangaDetailUiState,
    contentPadding: PaddingValues,
    onSync: () -> Unit,
    onShelfClick: () -> Unit,
    onReadChapter: (chapterId: String?, startPage: Int?) -> Unit,
    onToggleChapterSort: () -> Unit,
) {
    val manga = requireNotNull(state.manga)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    request = manga.coverDocumentId?.let { documentId ->
                        state.sourceTreeUri?.let { CoverRequest(it, documentId) }
                    },
                    contentDescription = manga.displayName,
                    modifier = Modifier.size(width = 120.dp, height = 170.dp),
                )
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        manga.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("作者：${manga.author ?: "未知"}")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.sourceDisplayPath ?: "来源路径不可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Text(manga.summary ?: "无简介", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShelfClick) {
                    Icon(
                        if (state.inShelf) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.inShelf) "移出书架" else "加入书架")
                }
                Button(onClick = onSync, enabled = !state.syncing) {
                    if (state.syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.syncing) "更新中" else "更新章节")
                }
            }
        }
        item {
            Button(
                onClick = { onReadChapter(null, null) },
                enabled = state.chapters.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("阅读 / 继续阅读")
            }
        }
        item {
            val countText = if (manga.chapterCountKnown) {
                "共 ${state.chapters.size} 章"
            } else {
                "已发现 ${state.chapters.size} 章，更新中"
            }
            // 表头：左边是章节数，右边是排序按钮。
            //
            // 排序按钮只在有章节时出现——空列表上给一个"从旧到新/从新到旧"的开关
            // 是没有意义的操作。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(countText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.chapters.size > 1) {
                    TextButton(onClick = onToggleChapterSort) {
                        Icon(
                            imageVector = Icons.Filled.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        // 显示**当前**顺序（而不是"点一下会变成什么"）：按钮上的字
                        // 描述状态比描述动作更不容易误读。
                        Text(state.chapterSortLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (state.chapters.isEmpty()) {
            item { Text("尚无章节索引") }
        } else {
            items(state.sortedChapters, key = { it.chapterId }) { chapter ->
                // 阅读进度只属于"当前正在读的那一章"，其它章节行不该跟着显示页码。
                val isCurrentChapter = state.progress?.chapterId == chapter.chapterId
                val readPage = state.progress?.pageOrdinal ?: 0
                ListItem(
                    modifier = Modifier.clickable { onReadChapter(chapter.chapterId, resumePageFor(isCurrentChapter, readPage)) },
                    headlineContent = { Text(chapter.title) },
                    supportingContent = {
                        // 以前这里显示"图片目录"，但那是**物理形式**，用户看章节列表时
                        // 想知道的是"这一章有多长、我读到哪了"。物理形式对阅读没有帮助。
                        Text(
                            buildString {
                                append(
                                    when (chapter.kind) {
                                        ChapterKind.IMAGE_DIRECTORY -> "图片目录"
                                        ChapterKind.ARCHIVE -> "归档 / PDF"
                                    },
                                )
                                chapter.pageCount?.let { append(" · 共 $it 页") }
                                if (isCurrentChapter && readPage > 0) {
                                    append(" · 读到第 ${readPage + 1} 页")
                                }
                            },
                        )
                    },
                    trailingContent = {
                        when {
                            // 已读过的章节显示进度，而不是总页数——总页数在标题下面。
                            isCurrentChapter && readPage > 0 -> Text(
                                "${readPage + 1}/${chapter.pageCount ?: "?"}",
                                color = MaterialTheme.colorScheme.primary,
                            )

                            else -> chapter.pageCount?.let { Text("$it 页") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
