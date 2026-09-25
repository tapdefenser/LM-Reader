package com.lmreader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage

/**
 * 条漫阅读器：承载 Mihon 的 `Long strip` 与 `Long strip with gaps` 两种模式。
 *
 * ## 两种模式的区别只有一处
 *
 * Mihon 里 `isContinuous` 只被读一次，用来决定 `WebtoonPageHolder` 的
 * `bottomMargin`：`WEBTOON`（`isContinuous = true`）**零间隔**，
 * `CONTINUOUS_VERTICAL`（`isContinuous = false`）每页 15dp 下边距。
 * 手势、解码、进度语义完全相同。这里照搬：[pageGap] 就是那个 15dp。
 *
 * 注意 Mihon 的命名是反的——`isContinuous = true` 表示**没有**间隔。
 *
 * ## "当前页"的判定与分页不同
 *
 * Mihon 的 `WebtoonLayoutManager.findLastEndVisibleItemPosition` 自底向上找**页底已越过
 * 视口底部**的那一页，即"读完这一页"才算当前页；而不是"这一页出现在视口里"。
 * 这个差别会直接改变进度保存的时机，因此这里照搬同一规则。
 *
 * ## 进度粒度
 *
 * Mihon 的条漫**不记录页内偏移**：恢复时把该页顶部对齐视口顶部，页内位置丢弃。
 * 本项目照搬（`intraPageRatio` 恒为 0）。
 */
@Composable
internal fun StripReader(
    source: PageSource,
    pages: List<ReaderPage>,
    mode: com.lmreader.core.model.ReadingMode,
    settings: ReaderSettings,
    currentPageIndex: Int,
    loader: ReaderImageLoader,
    onPageSettled: (Int) -> Unit,
    onPageHeightMeasured: (pageId: String, heightPx: Int) -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentPageIndex.coerceIn(pages.indices),
    )
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // 侧边距把条带内容缩窄（Mihon `webtoon_side_padding`，0..25%）。
    val sidePaddingDp = (configuration.screenWidthDp * settings.webtoonSidePadding / 100f).dp
    // 条带里的页宽 = 屏幕宽 - 两侧留白。高度按原图比例换算，因此整条布局**以 dp 计算**：
    // 像素与 dp 只有在解码时才需要换算（采样目标长边），布局不该掺进像素值。
    val contentWidthDp = (configuration.screenWidthDp - sidePaddingDp.value * 2).coerceAtLeast(1f)

    // 两种连续模式的唯一差别：带间隔时每页下方留 15dp（Mihon 的常量）。
    val pageGap = if (mode == com.lmreader.core.model.ReadingMode.CONTINUOUS_VERTICAL) 15.dp else 0.dp

    /**
     * 当前页判定：自底向上找第一个"页底已越过视口底部"的可见项。
     *
     * 用 `visibleItemsInfo` 而不是 `firstVisibleItemIndex`：后者是"页顶进入视口"，
     * 那会让进度在页面刚露头时就前移，与 Mihon 的"读完再记"不同。
     * 若没有任何一页的底部越过视口底（例如刚打开、第一页比视口还高），
     * 退回到第一页，与 Mihon 返回 `-1` 时保持上一个当前页的行为等价（首个滚动前无变化）。
     */
    LaunchedEffect(listState, pages.size) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val viewportEnd = info.viewportEndOffset
                val candidate = info.visibleItemsInfo
                    .lastOrNull { item -> item.offset + item.size <= viewportEnd }
                    ?: info.visibleItemsInfo.firstOrNull()
                candidate?.index?.let { index ->
                    if (index in pages.indices) onPageSettled(index)
                }
            }
    }

    // 外部页码变化（点按翻页、章节切换、恢复进度）驱动滚动。只在该页不在视口内时滚动，
    // 避免用户正在阅读时被"纠正"回页顶而跳动。
    LaunchedEffect(currentPageIndex, pages.size) {
        val target = currentPageIndex.coerceIn(pages.indices)
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
        if (!visible) listState.scrollToItem(target)
    }

    val tapModifier = Modifier.pointerInput(mode, pages.size) {
        detectTapGestures { offset ->
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width <= 0f || height <= 0f) return@detectTapGestures
            onTap(offset.x / width, offset.y / height)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().then(tapModifier),
        contentPadding = PaddingValues(horizontal = sidePaddingDp),
    ) {
        items(
            count = pages.size,
            key = { index -> pages[index].pageId },
        ) { index ->
            val page = pages[index]
            // 先按"尺寸未知"占位（视口高度），探测完成后用真实比例替换。
            val measured = rememberPageGeometry(
                loader = loader,
                source = source,
                page = page,
                viewportWidthPx = with(density) { contentWidthDp.dp.toPx().toInt() },
                fallbackHeightPx = configuration.screenHeightDp,
                onHeightMeasured = onPageHeightMeasured,
            )
            val heightDp = stripHeightDp(measured, contentWidthDp, configuration.screenHeightDp.toFloat())
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp)
                    .padding(bottom = pageGap),
            ) {
                val state = rememberPageImage(loader, source, page)
                // 条漫固定按宽度适配（Mihon 把 minimumScaleType 硬编码为 FIT_WIDTH）。
                PageContent(
                    state = state,
                    page = page,
                    scaleType = ImageScaleType.FIT_WIDTH,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
