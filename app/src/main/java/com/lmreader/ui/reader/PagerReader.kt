package com.lmreader.ui.reader

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingDirection

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
    onItemSettled: (Int) -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    onTransitionAction: (ReaderItem.Transition) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val horizontal = settings.readingMode.direction == ReadingDirection.HORIZONTAL

    // initialPage 只在首次组合时生效，因此换章与预载导致的下标平移要靠下面的
    // LaunchedEffect 同步。
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(items.indices),
        pageCount = { items.size },
    )

    // 落页：回报给状态机——跨章判定与进度落库都只有那一处。
    LaunchedEffect(pagerState, items.size) {
        snapshotFlow { pagerState.settledPage }.collect(onItemSettled)
    }
    // 外部位置变化（点按翻页、滑杆、恢复进度、预载平移）驱动分页器滚动。
    LaunchedEffect(currentIndex, items.size) {
        val target = currentIndex.coerceIn(items.indices)
        if (!pagerState.isScrollInProgress && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val renderItem: @Composable (Int) -> Unit = { index ->
            when (val item = items[index]) {
                is ReaderItem.PageItem -> EnginePageView(
                    source = item.chapter.source,
                    page = item.page,
                    settings = settings,
                    onSingleTap = onTap,
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
                // 0 而不是 Mihon 的 1：每一页都要解码成位图，相邻页同时存活会让
                // 解码峰值翻倍，真机上已因此 OOM。代价是相邻页不预载，滑动时
                // 多一次短暂占位。
                beyondViewportPageCount = 0,
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
