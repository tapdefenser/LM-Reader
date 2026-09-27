package com.lmreader.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * 长列表右侧的滚动条。
 *
 * ## 为什么自己画
 *
 * Compose **没有内置的滚动条**：`LazyColumn` / `LazyVerticalGrid` 都不提供。
 * 因此只有两条路——自己画一条，或者把列表换成原生 `RecyclerView`（那要重写适配器、
 * 丢掉 Compose 的列表能力）。这里取前者，实现只读 `layoutInfo`、不碰滚动行为。
 *
 * ## 常驻显示，颜色取自主题
 *
 * 这两点都是踩过坑之后改的，详见 [ScrollbarBody] 的说明：曾经"滚动时才显示"导致
 * 读者**完全看不到**，曾经写死白色导致**浅色模式下隐形**。
 *
 * ## 位置按估算，两端用精确锚点
 *
 * 滚动条只需表达"我在列表的什么位置"，不需要复刻每一项的高度（那等于把列表再布局
 * 一遍）。因此中间位置按行距估算，而到达顶部/底部时直接夹到端点——端点状态是确定的，
 * 估算法在那里反而不准（列表末尾常有个矮页脚）。
 */
@Composable
fun ListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    ScrollbarBody(
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

/**
 * 画那条滑块。
 *
 * ## 为什么**常驻**显示，而不是滚动时才出现
 *
 * 最初做的是"滚动时显示、停手 900ms 后淡出"。读者反馈**完全看不到**——停下手指再去
 * 找它，它已经淡掉了。滚动条的价值是"随时能看出我在列表的哪个位置"，那就不能是
 * 一闪而过的。因此改成常驻。
 *
 * ## 颜色必须取自主题，不能写死白色
 *
 * 第一版用 `Color.White.copy(alpha = 0.38f)`，在深色背景下没问题，但本应用的配色
 * **跟随系统**（见 `LmReaderTheme`）：浅色模式下白叠白等于隐形——这正是"看不到"的
 * 另一半原因。改用 `onSurface`，浅色下近黑、深色下近白。
 */
@Composable
private fun ScrollbarBody(
    thumb: ScrollbarThumb?,
    modifier: Modifier = Modifier,
) {
    if (thumb == null) return
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    // 滑块长度按"可见项"估算，而可见集合会变（列表末尾常有个矮页脚），
    // 于是长度会跳一下。平滑掉它。**位置不平滑**：位置要准。
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
        // 极长的列表算出来的滑块只有一两像素，既看不见也判断不出位置；给一个下限。
        val thumbHeight = (trackHeight * length).coerceAtLeast(MIN_THUMB_HEIGHT.toPx())
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        val top = travel * thumb.offsetFraction.coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
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
private val TRACK_WIDTH = 4.dp

/** 滑块最小长度：极长的列表也要能看见、能判断位置。 */
private val MIN_THUMB_HEIGHT = 24.dp

