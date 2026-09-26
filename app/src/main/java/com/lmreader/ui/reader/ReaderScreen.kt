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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.NavigationRegions
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.TapAction
import com.lmreader.di.AppContainer
import kotlin.math.roundToInt

/**
 * 阅读器宿主（开发文档 12）。
 *
 * 三层结构：
 * 1. 本文件：状态、控制栏、点按遮罩、章节导航与错误处理；
 * 2. [PagerReader]：三种分页模式（左到右、右到左、竖向分页）；
 * 3. [StripReader]：两种连续模式（条漫、条漫带间隔）。
 *
 * 分派依据只有 [com.lmreader.core.model.ReaderSettings.readingMode]，UI 不自己判断
 * "是不是条漫"——Mihon 用 `ReadingMode.toViewer` 做同一件事。
 *
 * ## 页面渲染交给引擎
 *
 * 缩放、平移、分块解码与裁白边由 [EnginePageView] 内的 SubsamplingScaleImageView
 * 承担——那也是 Mihon 用的引擎。本文件**不含任何变换数学**。
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
    val measureHeightDp = rememberStripHeightMeasurer()

    val leave = {
        viewModel.saveProgress()
        onBack()
    }
    BackHandler(onBack = leave)

    // 音量键翻页（Mihon `reader_volume_keys`，默认关）。
    //
    // 两个必须照搬的约束：
    // 1. **只在控制栏隐藏时生效**——控制栏可见时用户多半在看菜单，此时吃掉音量键会
    //    让"调音量"这个原始功能失灵；
    // 2. 只在 `KeyUp` 响应，否则按住不放会连续翻很多页。
    // 另外这里响应的是**阅读顺序**而不是物理键：`volumeKeysInverted` 交换两者。
    val volumeKeyModifier = if (state.settings.volumeKeys) {
        Modifier.onPreviewKeyEvent { event ->
            if (state.chromeVisible || event.type != KeyEventType.KeyUp) {
                return@onPreviewKeyEvent false
            }
            val forward = when (event.key) {
                Key.VolumeDown -> !state.settings.volumeKeysInverted
                Key.VolumeUp -> state.settings.volumeKeysInverted
                else -> return@onPreviewKeyEvent false
            }
            viewModel.turnPage(if (forward) 1 else -1)
            true
        }
    } else {
        Modifier
    }

    // 显示效果（亮度/灰度/反色/全屏/常亮）包在最外层：它们都是**整屏**作用，
    // 必须覆盖页面、控制栏与遮罩全部内容，而不是只作用于页面。
    ReaderDisplayEffects(settings = state.settings, modifier = Modifier.fillMaxSize()) {
        var settingsOpen by remember { mutableStateOf(false) }
        ReaderChrome(
            state = state,
            viewModel = viewModel,
            onLeave = leave,
            volumeKeyModifier = volumeKeyModifier,
            measureHeightDp = measureHeightDp,
            onOpenSettings = { settingsOpen = true },
        )

        if (settingsOpen) {
            ReaderSettingsDialog(
                state = state,
                onDismiss = { settingsOpen = false },
                onReadingMode = viewModel::setReadingMode,
                onClearReadingMode = viewModel::clearReadingModeOverride,
                onOrientation = viewModel::setOrientationOverride,
                onUpdateGlobal = viewModel::updateGlobalSettings,
            )
        }
    }

    // 首次进入时短暂显示点按区域，让用户知道分区在哪 —— 对应 Mihon
    // `ReaderNavigationOverlayView` 的一次性闩锁与 `..._on_start` 偏好。
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isNotEmpty() && state.settings.showTapZoneOverlayOnce) {
            viewModel.showTapZoneOverlay()
            kotlinx.coroutines.delay(TAP_ZONE_OVERLAY_MILLIS)
            viewModel.hideTapZoneOverlay()
        }
    }
}

/** 阅读器主体：内容 + 浮层控制栏 + 点按遮罩。与控制栏的显隐逻辑分离，便于阅读。 */
@Composable
private fun ReaderChrome(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onLeave: () -> Unit,
    volumeKeyModifier: Modifier,
    measureHeightDp: suspend (ReaderItem.PageItem, Float) -> Int?,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundFor(state.settings.theme))
            .then(volumeKeyModifier),
    ) {
        // 阅读内容占满整屏，控制栏作为浮层**后绘制**（Compose 中后绘制者在上层）。
        //
        // 两个必须满足的性质：页图按整屏尺寸适配；点按区域与屏幕等大。
        // 若内容被控制栏挤小，归一化坐标的基准就不是屏幕，Mihon 那套 0.33/0.66
        // 分区会整体偏移。
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.error != null -> ReaderError(state.error.orEmpty(), viewModel::reload, onLeave)

                state.items.isNotEmpty() -> {
                    // 换章必须重建阅读组件：分页器实例、滚动位置与条带页高缓存都与章节绑定。
                    key(state.currentChapter?.chapterId, state.readingMode) {
                        if (state.isContinuous) {
                            StripReader(
                                items = state.items,
                                mode = state.readingMode,
                                settings = state.settings,
                                currentIndex = state.currentPageIndex,
                                onItemSettled = viewModel::onItemSettled,
                                onPageHeightMeasured = viewModel::onPageHeightMeasured,
                                measureHeightDp = measureHeightDp,
                                onTap = viewModel::onTap,
                                onTransitionAction = viewModel::retryNeighbor,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            PagerReader(
                                items = state.items,
                                settings = state.settings,
                                currentIndex = state.currentPageIndex,
                                onItemSettled = viewModel::onItemSettled,
                                onTap = viewModel::onTap,
                                onTransitionAction = viewModel::retryNeighbor,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        if (state.tapZoneOverlayVisible) {
            TapZoneOverlay(state = state, onDismiss = viewModel::hideTapZoneOverlay)
        }

        if (state.chromeVisible && state.error == null && state.items.isNotEmpty()) {
            ReaderTopBar(state, onLeave, modifier = Modifier.align(Alignment.TopCenter))
            ReaderBottomBar(
                state = state,
                viewModel = viewModel,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(state: ReaderUiState, onBack: () -> Unit, modifier: Modifier) {
    TopAppBar(
        title = {
            Column {
                Text(state.mangaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // 显示当前模式：同一部漫画在不同模式下页码位置不同，用户需要一处能
                    // 确认"现在是哪个模式"的地方（Mihon `pref_show_reading_mode`）。
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
        modifier = modifier,
    )
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val pageCount = state.currentPages.size
    var sliderValue by remember(state.currentChapter?.chapterId) {
        mutableFloatStateOf(pageFraction(state.localPageIndex, pageCount))
    }
    LaunchedEffect(state.localPageIndex, pageCount) {
        sliderValue = pageFraction(state.localPageIndex, pageCount)
    }
    Surface(color = Color.Black.copy(alpha = 0.76f), modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 贴屏幕底边会被系统导航栏/手势条压住：模式 chip 恰好落在手势区内，
                // 点它反而触发"回到桌面"。必须留出导航栏高度。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    viewModel.jumpToPage(pageFromFraction(sliderValue, pageCount))
                },
                valueRange = 0f..1f,
                // 页数不足两页时滑杆没有可移动区间，禁用而不是让 steps 变成负数
                // （早前真机因为 -1 上限崩溃过）。
                enabled = pageCount > 1,
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
                    "${displayPageNumber(state.localPageIndex, pageCount)} / $pageCount",
                    color = Color.White,
                )
                IconButton(onClick = viewModel::openNextChapter, enabled = state.hasNextChapter) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一章",
                        tint = if (state.hasNextChapter) Color.White else Color.Gray,
                    )
                }
                // Mihon 底部栏的四个按钮里就有设置入口；没有它的话，所有阅读设置都只能
                // 在阅读器之外改，而"这部漫画"的覆盖又必须在阅读器里设。
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "阅读设置", tint = Color.White)
                }
            }
            ReadingModeSelector(current = state.readingMode, onSelect = viewModel::setReadingMode)
        }
    }
}

/**
 * 阅读模式切换（Mihon 底部栏第一个按钮的简化形态）。
 *
 * 用可横向滚动的 `Row` 而不是 `LazyRow`：只有五项，全部组合代价可忽略，而 `LazyRow`
 * 会把屏幕外的项留到滚动时才组合——横向空间不足的机型上最后一个模式就点不到。
 *
 * 完整的阅读设置界面（点击区域、缩放类型、滤镜等）属后续阶段；这里只放模式本身。
 */
@Composable
private fun ReadingModeSelector(current: ReadingMode, onSelect: (ReadingMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ReadingMode.entries) {
            FilterChip(
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
 * 区域矩形与颜色取自 [NavigationRegions]，与命中检测**共用同一份数据**，因此画出来的
 * 分区一定等于实际生效的分区——这正是 Mihon 把这个视图绑在 `ViewerNavigation` 上的原因。
 */
@Composable
private fun TapZoneOverlay(state: ReaderUiState, onDismiss: () -> Unit) {
    val regions = NavigationRegions.resolve(state.settings.tapZones, state.readingMode)
    val invert = state.settings.tapInvert
    // Paint 复用：Canvas 的绘制 lambda 会随滚动/重组频繁执行，每次 new 一个 Paint
    // 会产生可见的分配压力。
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 64f
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
        Canvas(modifier = Modifier.fillMaxSize()) {
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
                val label = action.overlayLabel()
                val textWidth = labelPaint.measureText(label)
                val baseline = top + (bottom - top) / 2f -
                    (labelPaint.descent() + labelPaint.ascent()) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        label,
                        left + ((right - left) - textWidth) / 2f,
                        baseline,
                        labelPaint,
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

/** 遮罩层的分区配色，照搬 Mihon `ViewerNavigation.NavigationRegion` 的 ARGB。 */
private fun TapAction.overlayColor(): Color = when (this) {
    TapAction.MENU -> Color(0xCC, 0x95, 0x81, 0x8D)
    TapAction.PREVIOUS -> Color(0xCC, 0xFF, 0x77, 0x33)
    TapAction.NEXT -> Color(0xCC, 0x84, 0xE2, 0x96)
    TapAction.PAN_LEFT -> Color(0xCC, 0x7D, 0x11, 0x28)
    TapAction.PAN_RIGHT -> Color(0xCC, 0xA6, 0xCF, 0xD5)
}

/** 遮罩层上的区域名，取自开发文档 12「点按」的用词。 */
private fun TapAction.overlayLabel(): String = when (this) {
    TapAction.MENU -> "菜单"
    TapAction.PREVIOUS -> "上一页"
    TapAction.NEXT -> "下一页"
    TapAction.PAN_LEFT -> "左移"
    TapAction.PAN_RIGHT -> "右移"
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
private fun backgroundFor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.BLACK -> Color.Black
    ReaderTheme.GRAY -> Color(0xFF303030)
    ReaderTheme.WHITE -> Color.White
    ReaderTheme.AUTO -> Color.Black
}

/** 首次进入时点按区域遮罩的显示时长。 */
private const val TAP_ZONE_OVERLAY_MILLIS = 1800L
