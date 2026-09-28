package com.lmreader.ui.reader

import kotlin.math.max
import kotlin.math.min

/**
 * 页面送进图片引擎前的采样决策。
 *
 * ## 为什么要有这一层（以及为什么规则长这样）
 *
 * 引擎（Mihon 同源的 `SubsamplingScaleImageView`）在分页模式下**自己会解码整张图**：
 * 它的 `Decoder.init` 走 `ca.mpreg.imagedecoder`（libvips 系），先把整图解进一个
 * `ByteBuffer`，再 `Bitmap.createBitmap(..., ARGB_8888)`（硬编码，`setPreferredBitmapConfig` 无效）。
 * 因此"交给引擎的像素量"直接决定峰值内存——这正是交接文档里记的那次 OOM 的根因。
 *
 * 原先的对策是"长边超过 3000 就整图解码 + 编成 PNG 再交给引擎"。它有两个问题：
 * 阈值判断 off-by-one（2550×3299 时算出的 sample 仍是 1，等于没降采样），而且
 * **编 PNG 本身就是秒级开销，还发生在主线程上**——真机实测翻页单帧 2.3~3.7 秒。
 *
 * 现在改成与上游一致的做法（EhViewer `image/Image.kt` 用 `min(屏宽,屏高) * 4/3` 作为
 * 解码目标；Mihon 条漫同样按视图尺寸解码成位图再 `ImageSource.bitmap`）：
 *
 * 1. 只有"长边 ≥ 2×目标"时才降采样。差一点点（例如 3299 对目标 1440）用一个 2 的幂
 *    刚好把它降到目标之上，尺寸仍然够屏（1275×1650 在 1080 宽的屏上仍是 1:1 以上）；
 * 2. 降采样后**直接把位图交给引擎**，不再编成任何格式——省掉的就是那秒级开销，
 *    同时绕开 libvips 的整图 ByteBuffer；
 * 3. 不满足条件的一律原样交流，一个字节都不多搬（保持引擎的分块解码能力）。
 *
 * 代价：降采样过的那一页放大超过采样分辨率时是插值放大。EhViewer 用同一个代价
 * （`Precision.INEXACT`），Mihon 条漫路径同样接受。
 */
internal object ReaderImageSampling {

    /**
     * 解码目标（长边，像素）。
     *
     * 取"屏幕短边 × 4/3"，与 EhViewer 的 `targetSize = minOf(widthPixels, heightPixels) * 4 / 3`
     * 一致。4/3 是留给缩放的余量：正常 1× 显示时不会插值，放大 1.33 倍以内也还有余量。
     */
    fun targetLongEdge(screenWidthPx: Int, screenHeightPx: Int): Int {
        val shortSide = min(screenWidthPx, screenHeightPx)
        return max(shortSide, 1) * 4 / 3
    }

    /**
     * 是否需要降采样。
     *
     * 判据是 `长边 >= 2 × 目标` 而不是 `长边 > 目标`：2 的幂采样只能把尺寸减半，
     * 刚刚超过目标一点点的图（2550×3299 对目标 1440）减半后会低于目标，反而在
     * 1× 显示时就要插值放大——那才是真正的画质损失。这种情况原样交流最划算。
     */
    fun needsSampling(longestEdge: Int, targetLongEdge: Int): Boolean =
        targetLongEdge > 0 && longestEdge >= targetLongEdge * 2

    /**
     * 采样率：2 的幂，且保证降采样后**两个方向都仍然 ≥ 目标**。
     *
     * 与 AOSP/Glide/Coil 的 `calculateInSampleSize` 同规则（`half >= target` 才继续翻倍），
     * 写成"两个方向都要成立"是刻意的：只按长边算会让细长条漫在宽度上掉到目标以下。
     */
    fun sampleSizeFor(width: Int, height: Int, targetLongEdge: Int): Int {
        if (width <= 0 || height <= 0 || targetLongEdge <= 0) return 1
        val halfWidth = width / 2
        val halfHeight = height / 2
        var sample = 1
        while (halfWidth / sample >= targetLongEdge && halfHeight / sample >= targetLongEdge) {
            sample *= 2
        }
        return sample
    }
}

/** 一次解码决策：要么原样交流，要么按 [sampleSize] 解码成位图。 */
internal data class PageDecodePlan(
    val sampleSize: Int,
) {
    /** true = 直接把手上的流交给引擎（保持引擎的分块解码）。 */
    val passThrough: Boolean get() = sampleSize <= 1
}

/** 按已读到的原始尺寸算解码计划。 */
internal fun planPageDecode(width: Int, height: Int, targetLongEdge: Int): PageDecodePlan {
    val longest = max(width, height)
    if (width <= 0 || height <= 0) return PageDecodePlan(sampleSize = 1)
    if (!ReaderImageSampling.needsSampling(longest, targetLongEdge)) {
        return PageDecodePlan(sampleSize = 1)
    }
    return PageDecodePlan(
        sampleSize = ReaderImageSampling.sampleSizeFor(width, height, targetLongEdge),
    )
}
