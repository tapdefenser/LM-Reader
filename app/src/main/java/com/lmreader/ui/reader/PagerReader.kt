package com.lmreader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lmreader.core.model.ReadingDirection
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import kotlin.math.roundToInt

/**
 * 分页阅读器：承载 Mihon 的三种 Pager 模式
 * （`Paged (left to right)` / `Paged (right to left)` / `Paged (vertical)`）。
 *
 * ## 三种方向怎么用一套代码
 *
 * 关键事实（来自 Mihon `PagerViewerAdapter.setChapters` 最后一行）：**右到左模式并没有
 * 反转 ViewPager 或设置 RTL 布局方向**，它只是把适配器里的项目列表反转了。于是
 * "索引更大的页画在右边"这条平台默认行为保持不变，而阅读顺序自然变成从右向左。
 *
 * 本项目用同一手法：[reverseLayout] = true 让索引更大的页排在左侧。
 * 因此**不要**再额外翻转布局方向或翻转点按区域——Mihon 都不翻转，多翻一次就会反向。
 *
 * 竖向分页用 [VerticalPager]（整页吸附），与条漫的连续滚动是两件不同的事。
 *
 * ## 与 Mihon 的一处必要差异
 *
 * Mihon 的 `ViewPager.offscreenPageLimit = 1` 会**立即**创建相邻页的视图；配合它
 * Coil 的按视图尺寸解码，"预载"是有代价的。Compose 的 `beyondViewportPageCount = 1`
 * 同样会立刻组合相邻页，因此这里额外用 `currentPage ± 1` 限定要解码的页：
 * 组合范围内但不在窗口内的页显示占位，避免把整章都解进内存。
 */
@Composable
internal fun PagerReader(
    source: PageSource,
    pages: List<ReaderPage>,
    mode: ReadingMode,
    scaleType: com.lmreader.core.model.ImageScaleType,
    currentPageIndex: Int,
    loader: ReaderImageLoader,
    onPageSettled: (Int) -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    val horizontal = mode.direction == ReadingDirection.HORIZONTAL

    // initialPage 只在首次组合时生效，因此换章要靠下面的 LaunchedEffect 同步。
    val pagerState = rememberPagerState(
        initialPage = currentPageIndex.coerceIn(pages.indices),
        pageCount = { pages.size },
    )

    // 用户滑动/点击后落页：回报给状态机落库。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settled -> onPageSettled(settled) }
    }
    // 外部页码变化（点按翻页、滑杆、恢复进度）要驱动分页器滚动。
    LaunchedEffect(currentPageIndex, pages.size) {
        val target = currentPageIndex.coerceIn(pages.indices)
        if (!pagerState.isScrollInProgress && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    val tapModifier = Modifier.pointerInput(mode, pages.size) {
        detectTapGestures { offset ->
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width <= 0f || height <= 0f) return@detectTapGestures
            onTap(offset.x / width, offset.y / height)
        }
    }
    // 竖屏方向不参与镜像：右到左靠 reverseLayout 表达，见上面的说明。
    val layoutDirection = LayoutDirection.Ltr

    val decodeWindow = remember(pagerState.currentPage, pages.size) {
        // 只解码当前页与紧邻的一页；再远的一页即使被组合也保持占位。
        val center = pagerState.currentPage
        ((center - 1).coerceAtLeast(0))..((center + 1).coerceAtMost(pages.lastIndex))
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        val pageContent: @Composable (Int) -> Unit = { index ->
            val page = pages[index]
            val inWindow = index in decodeWindow
            if (inWindow) {
                val state = rememberPageImage(loader, source, page)
                Box(modifier = Modifier.fillMaxSize().then(tapModifier)) {
                    PageContent(state = state, page = page, scaleType = scaleType)
                }
            } else {
                // 组合范围内但不在解码窗口：留白占位，避免提前解码。
                Box(modifier = Modifier.fillMaxSize().then(tapModifier))
            }
        }

        if (horizontal) {
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
                // 右到左：索引更大的页排在左侧，与 Mihon 反转适配器列表等价。
                reverseLayout = mode.isRightToLeft,
                beyondViewportPageCount = 1,
                key = { pages[it].pageId },
            ) { index -> pageContent(index) }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { pages[it].pageId },
            ) { index -> pageContent(index) }
        }
    }
}

/** 供滑杆与页码显示用的 1 基页码；越界时夹到第一页，避免出现 `0 / 0`。 */
internal fun displayPageNumber(pageIndex: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (pageIndex + 1).coerceIn(1, pageCount)

/** 页码比例，用于滑杆位置；页数不足时返回 0。 */
internal fun pageFraction(pageIndex: Int, pageCount: Int): Float =
    if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1)

/** 由比例反推页码并夹到合法区间。 */
internal fun pageFromFraction(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (fraction * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
