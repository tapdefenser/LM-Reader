package com.lmreader.ui.reader

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 阅读器页面位图加载与缓存。
 *
 * ## 为什么不直接用 Mihon 的数值
 *
 * Mihon 的阅读路径把整页解码成一个 bitmap（Coil 按视图尺寸采样），它的超长图保护
 * 来自**下载期切图**（`split_tall_images`：`h > w*3` 时按 `max(屏宽,屏高)*2` 切块）。
 * 本项目没有下载阶段（只读本地源），没有"已经切好的小图"这个前提，
 * 因此必须自己控制内存——见 docs/框架实现说明 6.5。
 *
 * ## 预算怎么定
 *
 * 不写死字节数，而是按**可用堆**推算：不同来源的图差别极大，固定值要么在低端机上
 * OOM、要么在高配机上白白浪费缓存。[maxCachedBytes] 取可用堆的 1/6，单页解码再按
 * 视口长边采样，于是"一页占多少内存"与屏幕尺寸挂钩。
 *
 * ## 淘汰为什么按槽位 + 字节双约束
 *
 * 只按条数淘汰时，一条超长条漫（单页几千万像素）会突破字节预算；只按字节淘汰时，
 * 大量小图会把内存碎片化到超过槽位预期。两个约束都要。
 * 被淘汰的位图**必须回收**——`Bitmap` 的原生内存不受 GC 及时管理。
 */
internal class ReaderImageLoader(
    context: Context,
    /** 同时驻留的页面数上限。分页用 3（前/当前/后），条漫用 5（可见数更多）。 */
    private val slots: Int,
    /** 单页解码的目标长边像素。 */
    private val targetLongEdge: Int,
) {
    private val bitmaps = LinkedHashMap<String, Bitmap>(slots, 0.75f, true)
    private var cachedBytes = 0L

    /**
     * 串行化缓存访问与解码。
     *
     * 用协程互斥而不是 `synchronized`：解码是阻塞 IO，放进监视器锁会占住线程并让
     * `recycle` 也一起等；而完全不加锁时，同一页被两个可见项同时请求会解码两次并
     * 互相覆盖，还可能回收掉别人正在绘制的那张。互斥 + 锁内二次检查两者都避免。
     */
    private val mutex = Mutex()

    /** 可用堆的 1/6，作为全部缓存位图的总预算。 */
    private val maxCachedBytes: Long = run {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        (info.availMem / 6).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
    }

    /**
     * 取一页的位图；已缓存则直接返回。
     *
     * 返回 null 表示这一页解不出来（文件损坏、格式不支持），调用方应显示错误占位，
     * 而**不是**继续转圈——否则用户永远等不到结果。
     */
    suspend fun load(source: PageSource, page: ReaderPage): Bitmap? = mutex.withLock {
        bitmaps[page.cacheKey()]?.let { return@withLock it }
        val decoded = withContext(Dispatchers.IO) { decode(source, page) } ?: return@withLock null
        // 在同一个临界区里再查一次：上面的解码已经在 IO 线程之外等待过，
        // 期间可能有另一个调用把同一页放进了缓存。
        val existing = bitmaps[page.cacheKey()]
        if (existing != null) {
            decoded.recycle()
            return@withLock existing
        }
        bitmaps[page.cacheKey()] = decoded
        cachedBytes += decoded.byteCount
        trimLocked()
        decoded
    }

    /**
     * 探测一页的原始像素尺寸。
     *
     * 走页源的 [PageSource.probe]（只读图像头部）。探测失败返回 null，由调用方按
     * "尺寸未知"占位——不得因此判定页面不可读。
     */
    suspend fun probe(source: PageSource, page: ReaderPage): PageGeometry? =
        runCatching { withContext(Dispatchers.IO) { source.probe(page) } }.getOrNull()

    /** 释放全部位图。退出阅读器或换章时调用。 */
    suspend fun clear() {
        mutex.withLock {
            bitmaps.values.forEach { it.recycle() }
            bitmaps.clear()
            cachedBytes = 0
        }
    }

    /** 在已持锁的前提下淘汰；调用方负责加锁。 */
    private fun trimLocked() {
        val iterator = bitmaps.entries.iterator()
        while (iterator.hasNext()) {
            if (bitmaps.size <= slots && cachedBytes <= maxCachedBytes) break
            val entry = iterator.next()
            iterator.remove()
            cachedBytes -= entry.value.byteCount
            entry.value.recycle()
        }
    }

    /**
     * 解码一页。
     *
     * 分两趟：先只读头部拿尺寸，再按 `inSampleSize` 解码。直接解码一张 5000×7000 的图
     * 会一次吃掉上百 MB，滚动必然 OOM。
     *
     * 采样率取 2 的幂：`inSampleSize` 非 2 的幂时部分解码器会向上取整，反而浪费内存。
     *
     * 这里**不复用** [probe] 的结果，而是自己再读一次头部：`probe` 的结果可能来自
     * 上一章的缓存，而换章后 `pageId` 变了不会命中；直接读一次头部代价很低，
     * 换来的是"解码所需尺寸一定来自当前这次打开"这个确定性。
     */
    private suspend fun decode(source: PageSource, page: ReaderPage): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = runCatching { source.open(page) }.getOrNull() ?: return null
        runCatching { opened.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetLongEdge ||
            bounds.outHeight / (sample * 2) >= targetLongEdge
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // RGB_565 省一半内存。漫画以灰阶/低饱和为主，代价可接受；
            // 需要精确取色的场合（翻译遮罩）不走这条路径。
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        // 每次解码都重新打开流：内容流是一次性的，不能复用上面读头部那个。
        val decodeStream = runCatching { source.open(page) }.getOrNull() ?: return null
        return runCatching { decodeStream.use { BitmapFactory.decodeStream(it, null, options) } }.getOrNull()
    }

    private companion object {
        /** 下限 24MB：低端设备上 1/6 可用堆可能小到连三页都放不下。 */
        const val MIN_BUDGET_BYTES = 24L * 1024 * 1024

        /** 上限 192MB：再高对阅读没有帮助，只会推迟 GC 触发点。 */
        const val MAX_BUDGET_BYTES = 192L * 1024 * 1024
    }
}

/**
 * 按原图比例把一页在条带里的高度算出来（单位与 [viewportWidth] 一致，调用方传 dp）。
 *
 * 尺寸未知时返回 [fallbackHeight]——**不能**返回 0，否则这一页在滚动布局里被折叠，
 * 滚动位置会在图片解码前后跳变。
 */
internal fun stripHeightDp(
    geometry: PageGeometry?,
    viewportWidth: Float,
    fallbackHeight: Float,
): Float {
    if (geometry == null || viewportWidth <= 0f) return fallbackHeight
    return (viewportWidth * geometry.aspectRatio).coerceAtLeast(1f)
}

/** 页面列表的稳定键；Compose 的 `key` 与缓存都用它，避免重排后串页。 */
internal fun ReaderPage.cacheKey(): String = pageId
