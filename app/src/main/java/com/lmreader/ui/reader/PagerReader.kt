package com.lmreader.ui.reader

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingDirection
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * 分页阅读器：承载 Mihon 的三种 Pager 模式
 * （`Paged (left to right)` / `Paged (right to left)` / `Paged (vertical)`）。
 *
 * ## 三种方向为什么能用一套代码
 *
 * 关键事实（来自 Mihon `PagerViewerAdapter.setChapters` 的末行）：**右到左并没有反转
 * ViewPager 或设置 RTL 布局方向**，它只是把适配器里的项列表反转了。于是"索引更大的项
 * 画在右边"这条平台默认行为不变，而阅读顺序自然变成从右向左。
 *
 * 这里用同一手法：[reverseLayout] = true 让索引更大的项排在左侧。因此**不要**再额外
 * 翻转布局方向或翻转点按区域——Mihon 都不翻转，多翻一次就会反向。
 *
 * 竖向分页用 [VerticalPager]（整页吸附），与条漫的连续滚动是两件不同的事。
 *
 * ## 项列表里不只有页面
 *
 * [items] 还包含**章节过渡项**（Mihon `ChapterTransition`）：读者翻过末页之后进入过渡项，
 * 再往前就是下一章的页。所以这里按 [ReaderItem] 的密封类型分派渲染，
 * 而不是假定"每一项都是一页"——那正是之前多章节目录不通的表现。
 */
@Composable
internal fun PagerReader(
    items: List<ReaderItem>,
    settings: ReaderSettings,
    currentIndex: Int,
    itemsRevision: Long,
    scrollRequest: Long,
    onItemSettled: (String) -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    onLongPress: () -> Unit,
    onTransitionAction: (ReaderItem.Transition) -> Unit,
    /** 页面字节的预取缓存；命中时不必再过一次 SAF。 */
    prefetcher: PagePrefetcher? = null,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val horizontal = settings.readingMode.direction == ReadingDirection.HORIZONTAL

    /**
     * 视口外保留几页。
     *
     * 与「预载页数」挂钩：那个设置表达的是"我读多少页之内不想等"，因此它同时决定
     * 预读多少**字节**（[com.lmreader.ui.reader.PagePrefetcher]）与保留多少**已解码的
     * 页**。每侧最多 1 页：图片引擎仍会整图解码，同时保留 5 页会逼近真机 256MB 堆。
     */
    val adjacentPagesAlive = (settings.preloadPages / 4).coerceIn(0, 1)

    // initialPage 只在首次组合时生效，因此换章与预载导致的下标平移要靠下面的
    // LaunchedEffect 同步。
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(items.indices),
        pageCount = { items.size },
    )

    val latestItemsRevision by rememberUpdatedState(itemsRevision)
    var settledItemsRevision by remember { mutableLongStateOf(-1L) }
    var handledScrollRequest by remember { mutableLongStateOf(scrollRequest) }

    // 落页：回报稳定 key 而不是下标。窗口滑动会让下标整体平移，快速滑动产生的旧下标
    // 若套到新列表上，恰好就会表现成“过渡页正确，翻过去却还是本章”。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            if (settledItemsRevision != latestItemsRevision) null else {
                val page = pagerState.settledPage
                pagerState.layoutInfo.visiblePagesInfo
                    .firstOrNull { it.index == page }?.key as? String
            }
        }.collect { key ->
            key?.let(onItemSettled)
        }
    }
    // 外部位置变化（点按翻页、滑杆、恢复进度、换章落点）驱动分页器滚动。
    //
    // key 用**当前位置的项身份**，而不是"下标 + 项数"。窗口重排会让某一项的下标变而
    // 项数不变（提升章节后当前章页面从下标 9 变到 1 就是这种），只盯项数的话不会触发
    // 归位，分页器就停在旧下标上——那个下标现在指向另一页，于是"往回翻"跳到不相干的
    // 位置，而状态机仍认为自己在原处。真机上表现为往回翻时乱跳。
    LaunchedEffect(itemsRevision, scrollRequest) {
        // 用户手势上报 currentIndex 不应触发反向滚动；仅列表换代或明确的导航命令归位。
        settledItemsRevision = -1L
        val target = currentIndex.coerceIn(items.indices)
        val positionKey = items[target].key
        val laidOutKey = pagerState.layoutInfo.visiblePagesInfo
            .firstOrNull { it.index == pagerState.currentPage }
            ?.key
        val explicitlyRequested = scrollRequest != handledScrollRequest
        handledScrollRequest = scrollRequest
        if (explicitlyRequested || pagerState.currentPage != target || laidOutKey != positionKey) {
            if (explicitlyRequested && settings.pageTransitions && abs(pagerState.currentPage - target) == 1) {
                pagerState.animateScrollToPage(target)
            } else {
                pagerState.scrollToPage(target)
            }
        }
        // 一帧回调不等于目标项已完成布局；等目标下标真正对应目标 key 且已落页。
        snapshotFlow {
            pagerState.settledPage == target &&
                pagerState.layoutInfo.visiblePagesInfo.any {
                    it.index == target && it.key == positionKey
                }
        }.first { it }
        settledItemsRevision = itemsRevision
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val renderItem: @Composable (Int) -> Unit = { index ->
            when (val item = items[index]) {
                is ReaderItem.PageItem -> EnginePageView(
                    source = item.chapter.source,
                    page = item.page,
                    settings = settings,
                    onSingleTap = onTap,
                    onLongPress = if (settings.longTapActions) onLongPress else null,
                    prefetcher = prefetcher,
                )

                is ReaderItem.Transition -> ChapterTransitionView(
                    transition = item,
                    settings = settings,
                    onRetry = { onTransitionAction(item) },
                    onTap = onTap,
                )
            }
        }

        if (horizontal) {
            HorizontalPager(
                state = pagerState,
                modifier = modifier,
                // 右到左：索引更大的项排在左侧，与 Mihon 反转适配器列表等价。
                reverseLayout = settings.readingMode.isRightToLeft,
                // 相邻页保持存活，否则会在"滑动结束的那一刻"闪一下。
                //
                // 成因：`0` 会在滑动过程中把相邻页的组合销毁。落页时 Compose 新建一个
                // 引擎视图，而它的解码是异步的——于是有一帧什么都没有，看起来就是闪一下。
                // 保留一页之后，相邻页在滑动期间就已经解码完成，落页直接是成品。
                //
                // 代价是同时最多解码 3 页。真机曾经因为同时存活两页 OOM 过，但那时是
                // **整图 ARGB_8888 且没有降采样**；现在超过长边 3000 的页会先降采样
                // （见 `ReaderImageView.buildImageSource`），因此这个数量是可承受的。
                // 页数由「预载页数」控制，读者可以把内存换回速度。
                beyondViewportPageCount = adjacentPagesAlive,
                key = { items[it].key },
            ) { index -> renderItem(index) }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = modifier,
                beyondViewportPageCount = 0,
                key = { items[it].key },
            ) { index -> renderItem(index) }
        }
    }
}
