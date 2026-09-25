package com.lmreader.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.StyleMode
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.CoverRequest
import com.lmreader.ui.common.EndSideDrawer
import com.lmreader.ui.common.LoadingState
import com.lmreader.ui.common.MangaCardItem
import com.lmreader.ui.common.MessageState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 书架页（开发文档 8.2）。默认展示全部收藏，右上分类按钮打开右侧分类栏。
 *
 * 每次启动默认进入书架（除非尚未完成路径配置），因此这里也是"没有收藏"时
 * 最需要给出可操作指引的页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    container: AppContainer,
    onOpenMenu: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val viewModel: BookshelfViewModel = viewModel(factory = BookshelfViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val treeUris = rememberSourceTreeUris(container)

    var newCategoryDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var movingManga by remember { mutableStateOf<MangaCard?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to listState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - PREFETCH_DISTANCE }
            .collect { viewModel.onLoadMore() }
    }

    // 分类侧栏：吸附在**右侧**的抽屉（用户要求），与图库的图源筛选栏同一侧、同一套手势。
    // 点右上角筛选按钮可打开，也可以从屏幕右边缘向左滑打开。
    // 参考 EhViewer 的 DownloadsScreen：它的分类/筛选面板就是右侧面板。
    // 用自实现的 EndSideDrawer（Popup 覆盖层）而不是 ModalNavigationDrawer：
    // 后者只能吸附起始侧、会跑到左边；把子树设成 RTL 又会镜像面板内容。
    EndSideDrawer(
        open = state.sidePanelOpen,
        onOpen = { viewModel.setSidePanelOpen(true) },
        onDismiss = { viewModel.setSidePanelOpen(false) },
        drawerWidth = 300.dp,
    ) {
        CategorySidePanel(
            state = state,
            onSelect = viewModel::selectCategory,
            onCreate = { newCategoryDialog = true },
            onRename = { renaming = it.categoryId to it.name },
            onMove = viewModel::moveCategory,
            onDelete = viewModel::deleteCategory,
        )
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("书架 · ${state.selectedCategoryName}") },
                    navigationIcon = {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Filled.Menu, contentDescription = "主菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { viewModel.setSidePanelOpen(true) }) {
                            Icon(Icons.Filled.FilterList, contentDescription = "筛选与分类")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.error != null) {
                    MessageState(
                        message = state.error!!,
                        actionLabel = "重试",
                        onAction = viewModel::onRefresh,
                    )
                } else if (state.items.isEmpty() && state.loading) {
                    LoadingState()
                } else if (state.items.isEmpty()) {
                    MessageState(
                        message = "书架还是空的。在图库里长按漫画卡片即可加入书架，" +
                            "收藏不会复制原文件。",
                        actionLabel = "去图库",
                        onAction = onOpenLibrary,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.mangaId }) { card ->
                            MangaCardItem(
                                card = card,
                                coverRequest = card.coverRequest(treeUris),
                                onClick = { onOpenManga(card.mangaId) },
                                onLongClick = { movingManga = card },
                            )
                        }
                        if (state.exhausted) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "共 ${state.items.size} 部收藏",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    if (newCategoryDialog) {
        NameInputDialog(
            title = "新建分类",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { newCategoryDialog = false },
            onConfirm = { name ->
                viewModel.createCategory(name) { errorMessage = it }
                newCategoryDialog = false
            },
        )
    }

    renaming?.let { (categoryId, currentName) ->
        NameInputDialog(
            title = "重命名分类",
            initial = currentName,
            confirmLabel = "保存",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.renameCategory(categoryId, name) { errorMessage = it }
                renaming = null
            },
        )
    }

    // 长按卡片：选择目标分类（开发文档 8.2「加入书架/已在书架」的更换分类入口）。
    movingManga?.let { card ->
        AlertDialog(
            onDismissRequest = { movingManga = null },
            title = { Text(card.displayName) },
            text = {
                Column {
                    Text("移动到分类", style = MaterialTheme.typography.labelMedium)
                    state.categories.forEach { category ->
                        TextButton(
                            onClick = {
                                viewModel.moveToCategory(card.mangaId, category.categoryId)
                                movingManga = null
                            },
                        ) { Text(category.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromShelf(card.mangaId)
                        movingManga = null
                    },
                ) { Text("移出书架") }
            },
            dismissButton = {
                TextButton(onClick = { movingManga = null }) { Text("取消") }
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("无法完成操作") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("知道了") } },
        )
    }
}

/**
 * 右侧分类栏（开发文档 8.2）。
 *
 * 显示数量，并提供重命名/文风、上移、下移、删除。文风设置对翻译有直接影响
 * （开发文档 8.2「分类文风解析」），因此这里显式标注当前文风来源，而不是留一个
 * 看不出作用的开关。
 */
@Composable
private fun CategorySidePanel(
    state: BookshelfUiState,
    onSelect: (Long?) -> Unit,
    onCreate: () -> Unit,
    onRename: (com.lmreader.core.model.Category) -> Unit,
    onMove: (Long, Int) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxHeight().width(300.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让出导航栏高度，避免分类列表底部被手势条压住。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = "新建分类")
                }
            }
            HorizontalDivider()
            CategoryRow(
                name = "全部",
                selected = state.selectedCategoryId == null,
                onClick = { onSelect(null) },
            )
            state.categories.forEachIndexed { index, category ->
                CategoryRow(
                    name = category.name,
                    selected = state.selectedCategoryId == category.categoryId,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.categories.lastIndex,
                    description = styleDescription(category.styleMode),
                    onClick = { onSelect(category.categoryId) },
                    onRename = { onRename(category) },
                    onMoveUp = { onMove(category.categoryId, -1) },
                    onMoveDown = { onMove(category.categoryId, 1) },
                    // 内置「未分类」不可删（开发文档 1.3），因此没有删除项。
                    onDelete = if (category.categoryId == 0L) null else ({ onDelete(category.categoryId) }),
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    description: String? = null,
    onRename: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
                Column {
                    Text(name)
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (onRename != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "分类操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名与文风") },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("上移") },
                            enabled = canMoveUp,
                            onClick = {
                                menuOpen = false
                                onMoveUp?.invoke()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("下移") },
                            enabled = canMoveDown,
                            onClick = {
                                menuOpen = false
                                onMoveDown?.invoke()
                            },
                        )
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除分类") },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 文风来源说明；具体文风文本在翻译设置页编辑（P3）。 */
private fun styleDescription(mode: StyleMode): String = when (mode) {
    StyleMode.GLOBAL -> "文风：跟随全局默认"
    StyleMode.CATEGORY -> "文风：跟随类别（未设置时回退全局）"
    StyleMode.CUSTOM -> "文风：本分类自定义"
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val trimmed = value.trim()
    // 名称必填 1–40 字符（开发文档 8.2）；非法输入就地提示并禁用保存，
    // 不悄悄改成默认值。
    val valid = trimmed.isNotEmpty() && trimmed.length <= 40
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = value.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            text = when {
                                trimmed.isEmpty() -> "名称必填，1–40 个字符"
                                trimmed.length > 40 -> "名称最长 40 个字符"
                                else -> "${trimmed.length}/40"
                            },
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun MangaCard.coverRequest(treeUris: Map<String, String>): CoverRequest? {
    val documentId = coverDocumentId ?: return null
    val treeUri = treeUris[sourceId] ?: return null
    return CoverRequest(treeUri = treeUri, documentId = documentId)
}

@Composable
private fun rememberSourceTreeUris(container: AppContainer): Map<String, String> {
    val imageSources by container.sourceRepository
        .observeSources(SourceKind.IMAGE_DIRECTORY)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val archiveSources by container.sourceRepository
        .observeSources(SourceKind.ARCHIVE_IMPORT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(imageSources, archiveSources) {
        (imageSources + archiveSources).associate { it.sourceId to it.treeUri }
    }
}

private const val PREFETCH_DISTANCE = 6
