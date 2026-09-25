package com.lmreader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage

/**
 * 一页的解码结果。
 *
 * 把"位图"和"是否失败"合成一个状态而不是两个独立字段：两者必须同时成立才自洽，
 * 分成两个 `mutableStateOf` 会出现"有图又有错"这类中间态。
 */
internal sealed interface PageImageState {
    data object Loading : PageImageState

    data class Ready(val bitmap: Bitmap) : PageImageState

    data class Failed(val reason: String) : PageImageState
}

/**
 * 加载并持有一页的位图。
 *
 * 位图的回收由本函数负责（`onDispose`），因为只有它知道这张图什么时候不再被绘制。
 * 交给缓存回收是不安全的：缓存按字节预算淘汰，可能回收掉正在屏幕上的那一张。
 * 因此缓存里的位图在**被本 Composable 引用期间**不会淘汰——淘汰只发生在
 * 其他页加入时按 LRU 回收那些已经离开屏幕的页。
 */
@Composable
internal fun rememberPageImage(
    loader: ReaderImageLoader,
    source: PageSource,
    page: ReaderPage,
): PageImageState {
    var state by remember(page.pageId, source) { mutableStateOf<PageImageState>(PageImageState.Loading) }
    LaunchedEffect(page.pageId, source) {
        state = PageImageState.Loading
        val bitmap = loader.load(source, page)
        state = if (bitmap == null) {
            PageImageState.Failed("无法解码「${page.displayName}」")
        } else {
            PageImageState.Ready(bitmap)
        }
    }
    // 位图的回收统一由 ReaderImageLoader 按字节预算与槽位数做，本函数不 recycle：
    // 那张位图同时被缓存与绘制引用，在这里回收会造成 use-after-recycle。
    return state
}

/**
 * 探测一页的原始尺寸，并把换算出的高度回报给调用方。
 *
 * 条漫需要"每页有多高"来定位滚动。尺寸是异步得到的，因此先用 [fallbackHeightPx]
 * 占位、探测完成后回报真实高度并触发重组——这与 Mihon 用
 * `WebtoonPageHolder.onImageDecoded` 触发重新布局是同一手法。
 */
@Composable
internal fun rememberPageGeometry(
    loader: ReaderImageLoader,
    source: PageSource,
    page: ReaderPage,
    viewportWidthPx: Int,
    fallbackHeightPx: Int,
    onHeightMeasured: (pageId: String, heightPx: Int) -> Unit,
): PageGeometry? {
    var geometry by remember(page.pageId, source) { mutableStateOf<PageGeometry?>(null) }
    LaunchedEffect(page.pageId, source, viewportWidthPx) {
        if (viewportWidthPx <= 0) return@LaunchedEffect
        val probed = loader.probe(source, page)
        geometry = probed
        onHeightMeasured(page.pageId, stripHeightDp(probed, viewportWidthPx.toFloat(), fallbackHeightPx.toFloat()).toInt())
    }
    return geometry
}

/**
 * 绘制一页。
 *
 * 缩放类型只在**分页**模式生效：Mihon 的条漫把 `minimumScaleType` 硬编码为
 * `FIT_WIDTH`，六种缩放类型在条漫里没有意义，它的设置界面也不显示这一项。
 * 因此条漫调用方传 [ImageScaleType.FIT_WIDTH] 即可。
 */
@Composable
internal fun PageContent(
    state: PageImageState,
    page: ReaderPage,
    scaleType: ImageScaleType,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            PageImageState.Loading -> CircularProgressIndicator()
            is PageImageState.Failed -> Text(
                text = state.reason,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )

            is PageImageState.Ready -> {
                val scale = scaleType.resolve(state.bitmap.width, state.bitmap.height)
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = page.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = scale,
                    alignment = Alignment.Center,
                )
            }
        }
    }
}

/**
 * 缩放类型到 Compose `ContentScale` 的映射。
 *
 * 这里是**近似**而不是等价：Mihon 的六种类型由 `SubsamplingScaleImageView` 以矩阵实现，
 * 允许图片超出视口并配合平移（`navigate_pan`）查看溢出部分。Compose 的 `Image` 只做
 * 一次性适配，没有"溢出可平移"的概念，因此 [ImageScaleType.FIT_WIDTH] 等在内容超出
 * 视口时会**裁掉**而不是允许平移。真正的缩放平移是阶段 C 的工作（需要 `graphicsLayer`
 * 加手势），届时这层映射会被替换掉；现在先把选项接上，行为差距记录在此。
 *
 * @param widthPx 已解码位图的宽（用于"智能适配"判断方向）
 * @param heightPx 已解码位图的高
 */
internal fun ImageScaleType.resolve(widthPx: Int, heightPx: Int): ContentScale = when (this) {
    ImageScaleType.FIT_SCREEN -> ContentScale.Fit
    ImageScaleType.STRETCH -> ContentScale.FillBounds
    ImageScaleType.FIT_WIDTH -> ContentScale.FillWidth
    ImageScaleType.FIT_HEIGHT -> ContentScale.FillHeight
    // "原始尺寸"在 Compose 里没有"不缩放"的对应值；用 None 表示不放大不缩小，
    // 超出部分裁掉（同上面的近似说明）。
    ImageScaleType.ORIGINAL_SIZE -> ContentScale.None
    // "智能适配"在 Mihon 里按图片方向选适配方式；这里按同一意图实现：
    // 竖图适合高度、横图适合宽度——竖图不浪费两侧，横图只在高度上受限。
    ImageScaleType.SMART_FIT -> if (heightPx >= widthPx) ContentScale.FillHeight else ContentScale.FillWidth
}
