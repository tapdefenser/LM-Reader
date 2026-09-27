package com.lmreader.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlinx.coroutines.launch

/**
 * 长列表右侧的滚动条，**可以拖动**。
 *
 * ## 为什么自己画
 *
 * Compose **没有内置滚动条**：`LazyColumn` / `LazyVerticalGrid` 都不提供。
 * 因此只有两条路——自己画一条，或者把列表换成原生 `RecyclerView`（那要重写适配器、
 * 丢掉 Compose 的列表能力）。这里取前者。
 *
 * ## 常驻显示，颜色取自主题
 *
 * 这两点都是踩过坑之后改的，详见 [ScrollbarBody] 的说明：曾经"滚动时才显示"导致
 * 读者**完全看不到**，曾经写死白色导致**浅色模式下隐形**。
 *
 * ## 拖动换算：拖一个滑块长 = 滚一屏
 *
 * 滑块长度占轨道的比例就是"视口占内容的比例"（`sizeFraction = viewport / content`）。
 * 于是把滑块往下拖 `dy` 像素，内容应当滚 `dy / sizeFraction` 像素；而滑块高
 * `thumbHeight = track * sizeFraction`，代进去正好得到：
 *
 * ```
 * 滚动量 = dy × 视口高 / 滑块高
 * ```
 *
 * 也就是"拖动量等于滑块自身高度时，正好走过一屏"。不需要额外维护内容总长的估算值，
 * 因此**拖动与位置估算的误差无关**——估算只影响滑块看起来多长，不影响拖动的比例。
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
    val scope = rememberCoroutineScope()
    ScrollbarLayout(
        thumb = thumbFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            viewportSize = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset,
            visible = state.visibleItems(),
        ),
        viewportSize = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset,
        onScrollBy = { delta -> scope.launch { state.scrollBy(delta) } },
        modifier = modifier,
    )
}

/** 网格版；与 [ListScrollbar] 同一实现，只是取几何的地方不同。 */
@Composable
fun GridScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    ScrollbarLayout(
        thumb = thumbFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            viewportSize = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset,
            visible = state.visibleItems(),
        ),
        viewportSize = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset,
        onScrollBy = { delta -> scope.launch { state.scrollBy(delta) } },
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
 * 滚动条的触摸层与视觉条。
 *
 * ## 触摸层必须**只覆盖右侧一条窄带**
 *
 * 试过一个"更稳"的做法：让触摸层铺满整个列表区（同样是同级最上层，命中必然可靠），
 * 在手势里判断手指是否落在右侧滑块上，不在就放行。
 *
 * **不行**：实测铺满之后**整个列表的点击都失效了**（连屏幕正中间的卡片都点不开）。
 * 也就是说 Compose 里重叠的兄弟节点并不是"不消费就穿透"——上面那层只要在命中路径里，
 * 下面那层就收不到点击。这与直觉相反，因此记在这里。
 *
 * 结论：触摸层只能覆盖右侧那一条，把影响范围限制在 [TOUCH_WIDTH] 之内。
 *
 * ## 视觉条画在带子的**右边缘**
 *
 * 带子比视觉条宽，因此 Canvas 铺满带子、把圆角矩形画在最右侧，视觉上仍然贴着屏幕右边缘，
 * 与"带子有多宽"无关。
 */
@Composable
private fun ScrollbarLayout(
    thumb: ScrollbarThumb?,
    viewportSize: Int,
    onScrollBy: (Float) -> Unit,
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
    // 手势回调里要用到的最新值。用 rememberUpdatedState 而不是给 pointerInput 加
    // key：加 key 会在长度动画的每一帧重启手势检测器，拖动过程会被打断。
    val currentLength by rememberUpdatedState(length)
    val currentOffset by rememberUpdatedState(thumb.offsetFraction)
    val currentViewport by rememberUpdatedState(viewportSize.toFloat())
    val currentOnScrollBy by rememberUpdatedState(onScrollBy)
    val grabSlopPx = with(LocalDensity.current) { GRAB_SLOP.toPx() }

    // 外层铺满、内层贴右边：**组件自己保证贴右侧**，不依赖调用方写 align。
    // 踩过的坑：改窄带时把调用方的 `Modifier.align(Alignment.CenterEnd)` 删了，
    // 于是它按 Box 的默认对齐跑到了屏幕左边。让组件自对齐就不会再犯。
    //
    // 外层没有 pointerInput，因此只是布局包装，不会被加入命中路径、不影响列表的点击；
    // 只有内层那条窄带有手势（见下面的说明）。
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(TOUCH_WIDTH)
                // 手势挂在**这条带子**上（不是里面那条 4dp 的视觉条上——挂在细条上会让
                // 有效触摸区等于 4dp，"写多少都没用"）。
                //
                // 按不到滑块就原样返回、什么都不 consume，于是带子内的滚动与点击照旧。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val thumbHeight = size.height * currentLength
                        if (thumbHeight <= 0f) return@awaitEachGesture
                        val top = (size.height - thumbHeight) * currentOffset
                        val onThumb = down.position.y >= top - grabSlopPx &&
                            down.position.y <= top + thumbHeight + grabSlopPx
                        if (!onThumb) return@awaitEachGesture

                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            val dy = change.positionChange().y
                            if (dy != 0f) {
                                change.consume()
                                // 符号**由读者真机测试定**，不要靠推理改。
                                //
                                // 这个映射的含义是：**内容跟着手指走**——手指往下拖，列表
                                // 内容也往下走（看到更早的内容）。与"手指拖动内容"这个最直觉
                                // 的手感一致。
                                //
                                // 走过一次弯路：我根据 `LazyListState.scrollBy` 的正负号
                                // 推理，把它改成了相反的符号，结果读者反馈方向反了。
                                // 还试过用截图测量来"证明"，但那次测量是**无效的**——
                                // 网格分页在滚动时又加载了一批内容，滑块长度从 436px 变成
                                // 226px；而"内容变多、位置不变"本身就会让滑块顶端上移，
                                // 与"反向滚动"产生同样的数字，无法区分。
                                currentOnScrollBy(dy * currentViewport / thumbHeight)
                            }
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackHeight = size.height
                val barWidth = TRACK_WIDTH.toPx()
                // 极长的列表算出来的滑块只有一两像素，既看不见也抓不住；给一个下限。
                val thumbHeight = (trackHeight * length).coerceAtLeast(MIN_THUMB_HEIGHT.toPx())
                val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
                val top = travel * thumb.offsetFraction.coerceIn(0f, 1f)
                drawRoundRect(
                    color = color,
                    // 视觉条贴带子的右边缘 = 屏幕右边缘。
                    topLeft = Offset(size.width - barWidth, top),
                    size = Size(barWidth, thumbHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
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

/** 视觉条的宽度。细到不挡封面，又粗到看得见。 */
private val TRACK_WIDTH = 4.dp

/**
 * 触摸区宽度（从屏幕右边缘往里算）。
 *
 * 取 28dp 这个"手指能稳稳按住"的量级。**宽一点没有代价**：触摸层铺满整个列表区，
 * 只有在"手指落在这条带子里**且**按在滑块上"时才接管手势，其余情况一概不消费，
 * 事件原样走到列表与卡片（见 [ScrollbarLayout]）。因此它不会挤占列表右侧的点击。
 *
 * 曾经的教训：这个值一度写成 28dp 却完全摸不到——因为手势被挂在了里面那条 4dp 的
 * 视觉条上，"写多少都没用"。宽度只有在命中测试正确之后才有意义。
 */
private val TOUCH_WIDTH = 28.dp

/** 按在滑块上下边缘附近也算按在滑块上，让手指稍微偏一点也能抓住。 */
private val GRAB_SLOP = 12.dp

/** 滑块最小长度：极长的列表也要能看见、能抓住、能判断位置。 */
private val MIN_THUMB_HEIGHT = 24.dp
