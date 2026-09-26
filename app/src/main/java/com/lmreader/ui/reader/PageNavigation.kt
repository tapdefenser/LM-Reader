package com.lmreader.ui.reader

import kotlin.math.roundToInt

/**
 * 页码换算辅助函数。
 *
 * 单独放一处而不是散在各组件里：滑杆位置、页码文案与跳页三处必须用**同一套**舍入规则，
 * 否则会出现"滑杆拖到最右但显示的不是最后一页"这类不一致。
 */

/** 供页码显示用的 1 基页码；页数为 0 时返回 0，避免出现 `0 / 0` 之外的空态歧义。 */
internal fun displayPageNumber(pageIndex: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (pageIndex + 1).coerceIn(1, pageCount)

/** 页码比例，用于滑杆位置。只有一页时返回 0（滑杆被禁用，位置无所谓）。 */
internal fun pageFraction(pageIndex: Int, pageCount: Int): Float =
    if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1)

/** 由滑杆比例反推页码并夹到合法区间。 */
internal fun pageFromFraction(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (fraction * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)

/**
 * 按原图比例把一页在条带里的高度算出来（单位与 [viewportWidth] 一致，调用方传 dp）。
 *
 * [geometry] 为空表示尺寸尚未探测出来，此时返回 [fallbackHeight]——**不能**返回 0，
 * 否则这一项在滚动布局里被折叠，滚动位置会在图片解码前后跳变。
 */
internal fun stripHeightDp(
    geometry: com.lmreader.core.storage.reader.PageGeometry?,
    viewportWidth: Float,
    fallbackHeight: Float,
): Float {
    if (geometry == null || viewportWidth <= 0f) return fallbackHeight
    return (viewportWidth * geometry.aspectRatio).coerceAtLeast(1f)
}
