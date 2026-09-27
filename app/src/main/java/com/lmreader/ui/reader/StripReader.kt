package com.lmreader.ui.reader

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ZoomStart

/**
 * 条漫阅读器：承载 Mihon 的 `Long strip` 与 `Long strip with gaps` 两种模式。
 *
 * ## 两种模式的区别只有一处
 *
 * Mihon 里 `isContinuous` 只被读一次，用来决定 `WebtoonPageHolder` 的 `bottomMargin`：
 * `WEBTOON`（`isContinuous = true`）**零间隔**，`CONTINUOUS_VERTICAL`
 * （`isContinuous = false`）每页 15dp 下边距。手势、解码、进度语义完全相同。
 * 这里照搬：`pageGap` 就是那个 15dp。
 *
 * 注意 Mihon 的命名是反的——`isContinuous = true` 表示**没有**间隔。
 *
 * ## "当前项"的判定与分页不同
 *
 * Mihon 的 `WebtoonLayoutManager.findLastEndVisibleItemPosition` 自底向上找
 * **页底已越过视口底部**的那一项，即"读完这一页"才算当前页，而不是"这一页出现在视口里"。
 * 这直接改变进度保存的时机，因此照搬同一规则。
 *
 * ## 项列表里不只有页面
 *
 * 与分页一样，[items] 含章节过渡项：条带到达当前章末尾会自然接上过渡页，再往下就是
 * 下一章的页——下一章在预载完成时已经拼进同一个列表，所以过章是连续的、没有跳变
 * （Mihon 靠 DiffUtil 只往尾部插入达到同样效果）。
 *
 * ## 进度粒度
 *
 * Mihon 的条漫**不记录页内偏移**：恢复时把该页顶部对齐视口顶部，页内位置丢弃。
 * 本项目照搬（`intraPageRatio` 恒为 0）。
 */
@Composable
internal fun StripReader(
    items: List<ReaderItem>,
    mode: ReadingMode,
    settings: ReaderSettings,
    currentIndex: Int,
    onItemSettled: (Int) -> Unit,
    onPageHeightMeasured: (pageId: String, heightDp: Int) -> Unit,
    measureHeightDp: suspend (ReaderItem.PageItem, Float) -> Int?,
    onTap: (x: Float, y: Float) -> Unit,
    onTransitionAction: (ReaderItem.Transition) -> Unit,
    onScrollDelta: (Int) -> Unit = {},
    /** 页面字节的预取缓存；命中时不必再过一次 SAF。 */
    prefetcher: PagePrefetcher? = null,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(items.indices),
    )
    val configuration = LocalConfiguration.current

    // 侧边距把条带内容缩窄（Mihon `webtoon_side_padding`，0..25%）。
    val sidePaddingDp = (configuration.screenWidthDp * settings.webtoonSidePadding / 100f).dp
    // 页宽 = 屏幕宽 - 两侧留白。整条布局**以 dp 计算**：像素只在探测与解码时用得到，
    // 布局里掺进像素值会在不同密度设备上得到不同结果。
    val contentWidthDp = (configuration.screenWidthDp - sidePaddingDp.value * 2).coerceAtLeast(1f)
    val viewportHeightDp = configuration.screenHeightDp.toFloat()

    // 两种连续模式的唯一差别：带间隔时每页下方留 15dp（Mihon 的常量）。
    val pageGap = if (mode == ReadingMode.CONTINUOUS_VERTICAL) 15.dp else 0.dp

    /**
     * 当前项判定：自底向上找第一个"底边已越过视口底部"的可见项。
     *
     * 用 `visibleItemsInfo` 而不是 `firstVisibleItemIndex`：后者是"顶边进入视口"，
     * 会让进度在页面刚露头时就前移，与 Mihon 的"读完再记"不同。
     * 若没有任何一项的底边越过视口底（例如刚打开、第一页比视口还高），退回到第一项。
     */
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val viewportEnd = info.viewportEndOffset
                val candidate = info.visibleItemsInfo
                    .lastOrNull { item -> item.offset + item.size <= viewportEnd }
                    ?: info.visibleItemsInfo.firstOrNull()
                candidate?.index?.let { index ->
                    if (index in items.indices) onItemSettled(index)
                }
            }
    }

    // 外部位置变化驱动滚动；只在该项不在视口内时滚动，避免读者正在阅读时被"纠正"到页顶。
    LaunchedEffect(currentIndex, items.size) {
        val target = currentIndex.coerceIn(items.indices)
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
        if (!visible) listState.scrollToItem(target)
    }

    /**
     * 滚动增量上报，用于"滚动即隐藏控制栏"。
     *
     * 这里**监视**列表状态而不是套一层 `scrollable` 修饰符：`LazyColumn` 自己已经处理
     * 滚动，外层再加一个可滚动修饰符会与它争抢手势，表现为滚动时卡顿或干脆不动。
     * 监视首项的偏移变化既能拿到真实的滚动增量，又不干预滚动本身。
     */
    LaunchedEffect(listState, items.size) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // 首项换了时，偏移量会从小值重新开始；跨项那一刻不能把差值当成滚动量，
                // 否则换项会被误判成一次大滚动而立刻隐藏控制栏。
                if (index == previousIndex) {
                    val delta = offset - previousOffset
                    if (delta != 0) onScrollDelta(delta)
                }
                previousIndex = index
                previousOffset = offset
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sidePaddingDp),
    ) {
        items(
            count = items.size,
            key = { index -> items[index].key },
        ) { index ->
            when (val item = items[index]) {
                is ReaderItem.PageItem -> {
                    var heightDp by remember(item.page.pageId) { mutableStateOf<Int?>(null) }
                    LaunchedEffect(item.page.pageId, contentWidthDp) {
                        val measured = measureHeightDp(item, contentWidthDp)
                        if (measured != null) {
                            heightDp = measured
                            onPageHeightMeasured(item.page.pageId, measured)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 尺寸未知时用视口高度占位：**不能**用 0，否则该项在滚动
                            // 布局里被折叠，滚动位置会在图片解码前后跳变。
                            .height((heightDp ?: viewportHeightDp.toInt()).coerceAtLeast(1).dp)
                            .padding(bottom = pageGap),
                    ) {
                        EnginePageView(
                            source = item.chapter.source,
                            page = item.page,
                            settings = settings.copy(
                                // 条漫固定按宽度适配：Mihon 把 minimumScaleType 硬编码为
                                // SCALE_TYPE_FIT_WIDTH，六种缩放类型在条漫里不可选。
                                imageScaleType = ImageScaleType.FIT_WIDTH,
                                // 条漫没有"翻页"，起始可见位置居中即可。
                                zoomStart = ZoomStart.CENTER,
                            ),
                            onSingleTap = onTap,
                            prefetcher = prefetcher,
                        )
                    }
                }

                is ReaderItem.Transition -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(viewportHeightDp.toInt().coerceAtLeast(1).dp),
                ) {
                    ChapterTransitionView(
                        transition = item,
                        settings = settings,
                        onRetry = { onTransitionAction(item) },
                        onTap = onTap,
                    )
                }
            }
        }
    }
}
