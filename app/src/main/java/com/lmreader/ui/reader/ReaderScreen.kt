package com.lmreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.ReadingDirection
import com.lmreader.core.model.TapZones
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.di.AppContainer
import kotlinx.coroutines.launch

/**
 * 阅读器（开发文档 12）。
 *
 * 结构对齐 Mihon 的三层划分：
 * 1. 本文件是**宿主**：控制栏、状态、错误与章节导航；
 * 2. [PagerReader] 承载三种分页模式（左到右、右到左、竖向分页）；
 * 3. [StripReader] 承载两种连续模式（条漫、条漫带间隔）。
 *
 * 分派依据只有 [com.lmreader.core.model.ReaderSettings.readingMode]：Mihon 用
 * `ReadingMode.toViewer` 做同一件事。UI 不自己判断"是不是条漫"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    mangaId: String,
    chapterId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        key = "$mangaId:$chapterId",
        factory = ReaderViewModel.factory(container, mangaId, chapterId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * 位图加载器随阅读模式重建。
     *
     * 槽位数不同：分页只需前/当前/后三页，条漫可见页更多。用 `key` 让模式切换时
     * 旧缓存被释放（`clear`），否则换模式后会短暂保留两套预算。
     */
    val loader = remember(state.readingMode) {
        ReaderImageLoader(
            context = context,
            slots = if (state.isContinuous) STRIP_CACHE_SLOTS else PAGER_CACHE_SLOTS,
            targetLongEdge = TARGET_LONG_EDGE_PX,
        )
    }
    DisposableEffect(loader) {
        onDispose { scope.launch { loader.clear() } }
    }

    val leave = {
        viewModel.saveProgress()
        onBack()
    }
    BackHandler(onBack = leave)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundFor(state.settings.theme)),
    ) {
        // 阅读内容必须占据**整个** Box，而不是被控制栏挤压出剩余空间。
        //
        // 这样做有两个必须满足的性质：
        // 1. 页图按整屏尺寸适配，控制栏浮在它上面（Mihon 的阅读器也是浮层）；
        // 2. 点按区域与屏幕等大。若内容被控制栏挤小，归一化坐标的基准就不是屏幕，
        //    Mihon 那套 0.33 / 0.66 的分区会整体偏移。
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.error != null -> ReaderError(state.error.orEmpty(), viewModel::reload, leave)

                state.pages.isNotEmpty() && state.pageSource != null -> {
                    // 智能转换在这里不成立（`pageSource` 是 data class 的 val，编译器不保证
                    // 跨 `when` 分支仍然非空），因此显式取一次并断言。
                    val source = requireNotNull(state.pageSource)
                    // 换章必须重建阅读组件：滚动位置、分页器实例、页尺寸缓存都与章节绑定。
                    key(state.currentChapter?.chapterId, state.readingMode) {
                        ReaderContent(
                            state = state,
                            source = source,
                            loader = loader,
                            onPageSettled = viewModel::onScrolledToPage,
                            onPageHeightMeasured = viewModel::onPageHeightMeasured,
                            onTap = viewModel::onTap,
                        )
                    }
                }
            }
        }

        // 控制栏与遮罩放在内容**之后**（Compose 中后绘制者在上层），于是它们接收
        // 落在自己身上的点击、其余点击继续下传给阅读内容。
        if (state.tapZoneOverlayVisible) {
            TapZoneOverlay(
                state = state,
                onDismiss = viewModel::hideTapZoneOverlay,
            )
        }

        if (state.chromeVisible && state.error == null && state.pages.isNotEmpty()) {
            Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                ReaderTopBar(state, leave)
            }
            ReaderBottomBar(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // 首次进入（或偏好要求时）短暂显示点按区域，让用户知道分区在哪 ——
    // 对应 Mihon `ReaderNavigationOverlayView` 的一次性闩锁与 `..._on_start` 偏好。
    LaunchedEffect(state.pages.isNotEmpty()) {
        if (state.pages.isNotEmpty() && state.settings.showTapZoneOverlayOnce) {
            viewModel.showTapZoneOverlay()
            kotlinx.coroutines.delay(TAP_ZONE_OVERLAY_MILLIS)
            viewModel.hideTapZoneOverlay()
        }
    }
}

/** 按阅读模式把内容分派给分页或条漫实现。 */
@Composable
private fun ReaderContent(
    state: ReaderUiState,
    source: PageSource,
    loader: ReaderImageLoader,
    onPageSettled: (Int) -> Unit,
    onPageHeightMeasured: (String, Int) -> Unit,
    onTap: (Float, Float) -> Unit,
) {
    val mode = state.readingMode
    if (state.isContinuous) {
        StripReader(
            source = source,
            pages = state.pages,
            mode = mode,
            settings = state.settings,
            currentPageIndex = state.currentPageIndex,
            loader = loader,
            onPageSettled = onPageSettled,
            onPageHeightMeasured = onPageHeightMeasured,
            onTap = onTap,
        )
    } else {
        PagerReader(
            source = source,
            pages = state.pages,
            mode = mode,
            scaleType = state.settings.imageScaleType,
            currentPageIndex = state.currentPageIndex,
            loader = loader,
            onPageSettled = onPageSettled,
            onTap = onTap,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(state: ReaderUiState, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(state.mangaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // 显示当前模式，因为同一部漫画在不同模式下页码位置会不同，
                    // 用户需要一个能确认"现在是哪个模式"的地方（Mihon `pref_show_reading_mode`）。
                    "${state.currentChapter?.title.orEmpty()} · ${state.readingMode.label}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.72f),
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
        ),
    )
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    modifier: Modifier,
) {
    var sliderValue by remember(state.currentChapter?.chapterId) {
        mutableFloatStateOf(pageFraction(state.currentPageIndex, state.pages.size))
    }
    LaunchedEffect(state.currentPageIndex, state.pages.size) {
        sliderValue = pageFraction(state.currentPageIndex, state.pages.size)
    }
    Surface(color = Color.Black.copy(alpha = 0.76f), modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 控制栏贴屏幕底边时会被系统导航栏/手势条压住：模式切换 chip 恰好落在
                // 手势区内，点它反而触发"回到桌面"。必须留出导航栏高度。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    viewModel.jumpToPage(pageFromFraction(sliderValue, state.pages.size))
                },
                valueRange = 0f..1f,
                // 页数不足两页时滑杆没有可移动区间，禁用而不是让 steps 变成负数
                // （早前真机因为 -1 上限崩溃过）。
                enabled = state.pages.size > 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::openPreviousChapter, enabled = state.hasPreviousChapter) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一章",
                        tint = if (state.hasPreviousChapter) Color.White else Color.Gray,
                    )
                }
                Text(
                    "${displayPageNumber(state.currentPageIndex, state.pages.size)} / ${state.pages.size}",
                    color = Color.White,
                )
                IconButton(onClick = viewModel::openNextChapter, enabled = state.hasNextChapter) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一章",
                        tint = if (state.hasNextChapter) Color.White else Color.Gray,
                    )
                }
            }
            ReadingModeSelector(
                current = state.readingMode,
                onSelect = viewModel::setReadingMode,
            )
        }
    }
}

/**
 * 阅读模式切换（Mihon 底部栏第一个按钮的简化形态）。
 *
 * 为什么现在就要有：五种阅读模式是本阶段的主要产出，没有切换入口就无法在真机上
 * 验证它们；而且用户改模式后应当**立刻**换布局并停在原页，而不是退出重进。
 *
 * 用可横向滚动的 `Row` 而不是 `LazyRow`：只有五项，全部组合起来代价可以忽略，
 * 而 `LazyRow` 会把屏幕外的项留到滚动时才组合——在横向空间不足的机型上，
 * 最后一个模式（条漫带间隔）就点不到。
 *
 * 完整的阅读设置界面（含点击区域、缩放类型、滤镜等）是后续阶段的工作；
 * 这里只放模式本身，避免提前把六十项设置塞进阅读器。
 */
@Composable
private fun ReadingModeSelector(
    current: com.lmreader.core.model.ReadingMode,
    onSelect: (com.lmreader.core.model.ReadingMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in com.lmreader.core.model.ReadingMode.entries) {
            androidx.compose.material3.FilterChip(
                selected = mode == current,
                onClick = { onSelect(mode) },
                label = { Text(mode.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/**
 * 点按区域遮罩层（Mihon `ReaderNavigationOverlayView`）。
 *
 * 首次进入阅读器时短暂显示，让用户知道每一块点击区会做什么；任意点击即消退。
 * 区域矩形与颜色取自 [com.lmreader.core.model.NavigationRegions]，与命中检测共用
 * 同一份数据，因此**画出来的分区一定等于实际生效的分区**——这正是 Mihon 把这个
 * 视图绑在 `ViewerNavigation` 上的原因。
 */
@Composable
private fun TapZoneOverlay(state: ReaderUiState, onDismiss: () -> Unit) {
    val zones = state.settings.tapZones
    val mode = state.readingMode
    val invert = state.settings.tapInvert
    val regions = com.lmreader.core.model.NavigationRegions.resolve(zones, mode)
    // Paint 复用：Canvas 的 draw lambda 会随滚动/重组频繁执行，每次 new 一个 Paint
    // 会产生可见的分配压力。
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 64f
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInputTap(onDismiss),
    ) {
        if (regions.isEmpty()) {
            Text(
                text = "点按区域已禁用：点击任意位置显示/隐藏控制栏",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            for ((rect, action) in regions) {
                val mirrored = rect.invert(invert)
                val left = mirrored.left * size.width
                val top = mirrored.top * size.height
                val right = mirrored.right * size.width
                val bottom = mirrored.bottom * size.height
                drawRect(
                    color = action.overlayColor(),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                )
                // 区域名画在每个矩形正中，与 Mihon 一致。用原生 canvas 绘制而不是
                // Compose 的文本 API：这里已经在 Canvas 作用域内，原生绘制少一层布局，
                // 而且中文字宽可以直接从 Paint 量出来用于居中。
                val label = action.overlayLabel()
                val paint = labelPaint
                val textWidth = paint.measureText(label)
                val baseline = top + (bottom - top) / 2f - (paint.descent() + paint.ascent()) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        label,
                        left + ((right - left) - textWidth) / 2f,
                        baseline,
                        paint,
                    )
                }
            }
        }
        Text(
            text = "点击任意位置关闭",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        )
    }
}

/** 遮罩层上的区域名，取自开发文档 12「点按」的用词。 */
private fun com.lmreader.core.model.TapAction.overlayLabel(): String = when (this) {
    com.lmreader.core.model.TapAction.MENU -> "菜单"
    com.lmreader.core.model.TapAction.PREVIOUS -> "上一页"
    com.lmreader.core.model.TapAction.NEXT -> "下一页"
    com.lmreader.core.model.TapAction.PAN_LEFT -> "左移"
    com.lmreader.core.model.TapAction.PAN_RIGHT -> "右移"
}

/** 遮罩层的分区配色，照搬 Mihon `ViewerNavigation.NavigationRegion` 的 ARGB。 */
private fun com.lmreader.core.model.TapAction.overlayColor(): Color = when (this) {
    com.lmreader.core.model.TapAction.MENU -> Color(0xCC, 0x95, 0x81, 0x8D)
    com.lmreader.core.model.TapAction.PREVIOUS -> Color(0xCC, 0xFF, 0x77, 0x33)
    com.lmreader.core.model.TapAction.NEXT -> Color(0xCC, 0x84, 0xE2, 0x96)
    com.lmreader.core.model.TapAction.PAN_LEFT -> Color(0xCC, 0x7D, 0x11, 0x28)
    com.lmreader.core.model.TapAction.PAN_RIGHT -> Color(0xCC, 0xA6, 0xCF, 0xD5)
}

/** 遮罩层的"点击任意处关闭"，与区域命中无关。 */
private fun Modifier.pointerInputTap(onTap: () -> Unit): Modifier =
    this.then(Modifier.pointerInput(onTap) { detectTapGestures { onTap() } })

@Composable
private fun ReaderError(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 阅读背景色（Mihon `pref_reader_theme_key`）。 */
private fun backgroundFor(theme: com.lmreader.core.model.ReaderTheme): Color = when (theme) {
    com.lmreader.core.model.ReaderTheme.BLACK -> Color.Black
    com.lmreader.core.model.ReaderTheme.GRAY -> Color(0xFF303030)
    com.lmreader.core.model.ReaderTheme.WHITE -> Color.White
    com.lmreader.core.model.ReaderTheme.AUTO -> Color.Black
}

/** 分页缓存槽位：前 / 当前 / 后。 */
private const val PAGER_CACHE_SLOTS = 3

/** 条漫可见页更多，但同样要有界。 */
private const val STRIP_CACHE_SLOTS = 5

/** 解码目标长边；与 Mihon 的 `minimumTileDpi` 思路一致：够清晰即可，不追原图。 */
private const val TARGET_LONG_EDGE_PX = 2560

/** 首次进入时点按区域遮罩的显示时长（Mihon 是 1000ms 淡入后等用户点击）。 */
private const val TAP_ZONE_OVERLAY_MILLIS = 1800L
