package com.lmreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.lmreader.core.storage.reader.PageGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 条漫的页高测量桥接。
 *
 * ## 为什么需要一层桥接
 *
 * 条带布局要按原图比例算出每页多高，而尺寸必须探测（读图像头部）才知道；
 * 探测结果又是**像素**，布局要的是 **dp**。把这两件事压在一个 suspend 函数里，
 * 是为了让 [StripReader] 完全不必知道像素与 dp 的区别，也不必自己缓存探测结果。
 *
 * ## 缓存放在这里而不是页源
 *
 * 页源已经有 `probe` 的尺寸缓存，但这里的缓存键是"页 ID → 几何"，作用是把**失败也缓存
 * 下来**（`null` 也要记住），否则一张损坏的图会在每次滚动时反复打开文件重试。
 *
 * @return 该页在条带里的高度（dp）；尺寸未知时返回 null，调用方用视口高度占位。
 */
@Composable
internal fun rememberStripHeightMeasurer(): suspend (ReaderItem.PageItem, Float) -> Int? {
    // density 变化（例如切换显示尺寸）会使 dp 换算失效，因此把它作为 remember 的键。
    val density = LocalDensity.current.density
    return remember(density) {
        val cache = HashMap<String, PageGeometry?>()
        suspend fun measure(item: ReaderItem.PageItem, widthDp: Float): Int? {
            val key = item.page.pageId
            val geometry = if (cache.containsKey(key)) {
                cache[key]
            } else {
                val probed = withContext(Dispatchers.IO) {
                    runCatching { item.chapter.source.probe(item.page) }.getOrNull()
                }
                cache[key] = probed
                probed
            }
            return stripHeightDp(geometry, widthDp, 0f).takeIf { it > 0f }?.roundToInt()
        }
        ::measure
    }
}
