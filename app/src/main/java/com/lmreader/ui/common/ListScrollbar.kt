package com.lmreader.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 长列表右侧的滚动条。
 *
 * ## 为什么自己画
 *
 * `LazyColumn` / `LazyVerticalGrid` 本身不提供滚动条（Compose 没有内置的），而引入
 * 第三方库只为画一条细杠不值得。这里的实现只读 `layoutInfo`、不改滚动行为。
 *
 * ## 位置按"平均项高"估算，不追求像素级精确
 *
 * 滚动条要表达的是"我在列表的什么位置、前面还有多少"，而不是复刻每一页的高度。
 * 因此项高取**可见项的平均值**再乘总项数得到内容总长。列表项高度不一（封面尺寸、
 * 两行/三行标题）时会有几像素的出入，视觉上察觉不到，而实现简单得多——
 * 精确做法要给每一项测量高度，那等于把整个列表布局一遍。
 *
 * ## 自动淡出
 *
 * 只在滚动时显示，停止后 [HIDE_DELAY_MS] 毫秒淡出。理由是它覆盖在内容右侧，
 * 常驻会一直挡着封面边缘；而"要不要滚动"这件事本身就是即时可见的，不需要常驻提示。
 */
@Composable
fun ListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    ScrollbarBody(
        scrollState = state,
        thumb = thumbFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            viewportSize = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset,
            visible = state.visibleItems(),
        ),
        modifier = modifier,
    )
}

/** 网格版；与 [ListScrollbar] 同一实现，只是取几何的地方不同。 */
@Composable
fun GridScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    ScrollbarBody(
        scrollState = state,
        thumb = thumbFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            viewportSize = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset,
            visible = state.visibleItems(),
        ),
        modifier = modifier,
    )
}

/** 滑块在轨道里的位置与长度，都是 0..1。 */
private data class ScrollbarThumb(
    /** 滑块顶端位置，0 = 顶部，1 = 底部（相对**可滚动范围**，不是内容总长）。 */
    val offsetFraction: Float,
    /** 滑块长度占轨道全长的比例；越小表示列表越长。 */
    val sizeFraction: Float,
)

@Composable
private fun ScrollbarBody(
    scrollState: ScrollableState,
    thumb: ScrollbarThumb?,
    modifier: Modifier = Modifier,
) {
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    active = true
                } else {
                    // collectLatest：这段时间里又开始滚动的话这次淡出会被取消，
                    // 因此快速来回滑动不会让滚动条闪来闪去。
                    delay(HIDE_DELAY_MS)
                    active = false
                }
            }
    }
    val alpha by animateFloatAsState(
        targetValue = if (active && thumb != null) 1f else 0f,
        label = "scrollbarAlpha",
    )
    if (alpha <= 0.01f || thumb == null) return

    // 滑块长度按"可见项平均高"估算，而可见项集合会变（页脚比卡片矮得多），
    // 于是滚动中长度会跳一下。平滑一下让它看起来是渐变的，而不是闪动。
    // **位置不平滑**：位置必须紧跟手指，滞后会让人觉得滚动条脱手。
    val length by animateFloatAsState(
        targetValue = thumb.sizeFraction,
        animationSpec = tween(durationMillis = 120),
        label = "scrollbarLength",
    )

    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .width(TRACK_WIDTH),
    ) {
        val trackHeight = size.height
        // 极长的列表算出来的滑块只有一两像素，既看不见也点不到；给一个下限。
        val thumbHeight = (trackHeight * length).coerceAtLeast(MIN_THUMB_HEIGHT.toPx())
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        val top = travel * thumb.offsetFraction.coerceIn(0f, 1f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.38f * alpha),
            topLeft = Offset(0f, top),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
        )
    }
}

/** 一个可见项：下标、相对视口顶端的位置、自身高度。 */
private data class VisibleItem(val index: Int, val offsetY: Int, val height: Int)

/** 列表版可见项。 */
private fun LazyListState.visibleItems(): List<VisibleItem> =
    layoutInfo.visibleItemsInfo.map { VisibleItem(it.index, it.offset, it.size) }

/** 网格版可见项；网格项的尺寸是 `IntSize`，只取高。 */
private fun LazyGridState.visibleItems(): List<VisibleItem> =
    layoutInfo.visibleItemsInfo.map { VisibleItem(it.index, it.offset.y, it.size.height) }

/**
 * 从"可见项"推出滑块几何。
 *
 * ## 按**行**算，不是按项算
 *
 * 内容总长是"行数 × 行距"，而一行里有几项取决于布局（网格一行能放好几个）。
 * 曾经按"项数 × 平均项高"算，于是网格里 7 项被当成 7 行、内容长度虚高约三倍——
 * 结果**内容根本占不满一屏却画出了滚动条**，而且滑块长度也不对。
 *
 * 行是**从可见项自己推出来的**（共享同一个 `offsetY` 的项属于同一行），而不是去问
 * "网格有几列"：这样列表与网格共用一套逻辑，列表自然得到"一行一项"。
 *
 * 行距优先取**相邻两行的位置差**（已含行间距），只有一行可见时才退回项高。
 *
 * ## 位置按估算，不追求像素级精确
 *
 * 滚动条只需表达"我在列表的什么位置、前面还有多少"。项高往往不一致（封面尺寸、
 * 标题行数），逐项测量等于把整个列表布局一遍。
 */
private fun thumbFrom(
    totalItems: Int,
    viewportSize: Int,
    visible: List<VisibleItem>,
): ScrollbarThumb? {
    if (totalItems <= 0 || visible.isEmpty() || viewportSize <= 0) return null

    val rows = visible.groupBy { it.offsetY }
    val itemsPerRow = (visible.size / rows.size).coerceAtLeast(1)
    val rowOffsets = rows.keys.sorted()
    val rowPitch = if (rowOffsets.size >= 2) {
        (rowOffsets[1] - rowOffsets[0]).toFloat()
    } else {
        rows.values.maxOf { row -> row.maxOf { it.height } }.toFloat()
    }
    if (rowPitch <= 0f) return null

    val rowCount = ceil(totalItems.toFloat() / itemsPerRow).toInt()
    val contentSize = rowPitch * rowCount
    // 内容还没占满一屏：没有可滚动的东西，不画。
    if (contentSize <= viewportSize) return null

    val firstIndex = visible.minOf { it.index }
    val lastIndex = visible.maxOf { it.index }
    val firstRow = firstIndex / itemsPerRow
    val firstOffset = visible.first { it.index == firstIndex }.offsetY
    // 已经滚过的像素 = 前面那些行的估计高度 + 首行被滚出去的部分。
    val scrolled = firstRow * rowPitch - firstOffset
    val maxScroll = contentSize - viewportSize

    // 两端用**精确锚点**，中间用估算。
    //
    // 为什么需要：`contentSize` 是按"行距 × 行数"估的，而列表末尾常有一个矮页脚
    // （"共 N 部收藏"），它被当成整行算，于是 maxScroll 偏高、滑块到不了底
    // （实测差 46px）。两端的状态是确定的（首项/末项就在可见集合里），直接夹到端点
    // 既准确又**稳定**——不会像"用实测范围反推内容总长"那样让滑块长度随滚动变化。
    val offsetFraction = when {
        lastIndex >= totalItems - 1 -> 1f
        firstIndex <= 0 && firstOffset >= 0 -> 0f
        else -> (scrolled / maxScroll).coerceIn(0f, 1f)
    }
    return ScrollbarThumb(
        offsetFraction = offsetFraction,
        sizeFraction = (viewportSize / contentSize).coerceIn(0.05f, 1f),
    )
}

/** 轨道宽度。细到不挡封面，又粗到看得见。 */
private val TRACK_WIDTH = 3.dp

/** 滑块最小长度：极长的列表也要能看见、能判断位置。 */
private val MIN_THUMB_HEIGHT = 24.dp

/** 停止滚动后多久淡出。 */
private const val HIDE_DELAY_MS = 900L
