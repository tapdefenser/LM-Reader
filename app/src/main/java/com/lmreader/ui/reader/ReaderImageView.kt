package com.lmreader.ui.reader

import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ZoomStart
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage

/**
 * 一页的渲染视口：由 Mihon 使用的图片引擎承担缩放、平移、分块解码与裁白边。
 *
 * ## 这一层是薄壳，这是刻意的
 *
 * Mihon 的 `ReaderPageImageView` 同样只做"设属性 + 读返回值"三件事：
 * `setMinimumScaleType` / `setCropBorders` / `setDoubleTapZoomStyle`、
 * `getPanRemaining`、`animateScaleAndCenter`。所有变换数学都在库里。
 * 因此本文件**不实现任何矩阵运算**——那部分抄不来，也不该重写。
 *
 * ## 为什么用 `ImageSource.inputStream` 而不是先解码成 Bitmap
 *
 * 传 Bitmap 会让库失去分块解码的能力（内存已经按整图付过一次了）。
 * 传流时库自己对文件做区域解码，超大跨页才不会 OOM——这正是本阶段之前手写采样解码
 * 想要、但做不到的那件事。
 *
 * ## 缩放类型映射（对照 Mihon `PagerPageHolder` → `ReaderPageImageView`）
 *
 * | 我们的值 | 库常量 |
 * |---|---|
 * | [ImageScaleType.FIT_SCREEN] | `SCALE_TYPE_CENTER_INSIDE` |
 * | [ImageScaleType.FIT_WIDTH] | `SCALE_TYPE_FIT_WIDTH` |
 * | [ImageScaleType.FIT_HEIGHT] | `SCALE_TYPE_FIT_HEIGHT` |
 * | [ImageScaleType.ORIGINAL_SIZE] | `SCALE_TYPE_CENTER_INSIDE` + 最小缩放 1（见下） |
 * | [ImageScaleType.STRETCH] | `SCALE_TYPE_FIT_XY` |
 * | [ImageScaleType.SMART_FIT] | 库没有对应值；按方向选宽度或高度（见 [smartFitScaleType]） |
 */
@Composable
internal fun EnginePageView(
    source: PageSource,
    page: ReaderPage,
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    onSingleTap: (x: Float, y: Float) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onReady: () -> Unit = {},
    /** 页面字节的预取缓存；命中时不必再过一次 SAF。 */
    prefetcher: PagePrefetcher? = null,
) {
    // 视图实例随页面身份重建：库内部持有解码状态与瓦片缓存，复用实例会让上一页的
    // 缩放位置与瓦片残留到下一页（Mihon 在 `ReaderPageImageView.recycle()` 里显式清理
    // 同一个实例，我们选择更简单且不会串页的做法）。
    var view by remember(page.pageId) {
        mutableStateOf<TapAwareSubsamplingImageView?>(null)
    }
    var decodeFailed by remember(page.pageId) { mutableStateOf(false) }

    AndroidView(
        factory = { context ->
            TapAwareSubsamplingImageView(
                context = context,
                onSingleTap = onSingleTap,
                onLongPress = onLongPress,
            ).also { created ->
                // 解码完成之前视图是"什么都没有"，而分页器在滑动过程中就会把它画出来。
                // 不给底色的话，那一瞬间看到的是**下层内容透出来**（看起来就是"闪一下"）。
                // 给一个与阅读背景同色的不透明底色，同一帧里就是一块纯色，而不是穿帮。
                created.setBackgroundColor(android.graphics.Color.BLACK)
                configure(created, settings, onReady = { onReady() }, onError = { decodeFailed = true })
                view = created
            }
        },
        modifier = modifier.fillMaxSize(),
        // 等布局完成再载图。
        //
        // 尺寸为 0 时 `setImage` 的行为不可预期：真机日志里出现过
        // `setImage: 001.jpg view=0x0`，而库的瓦片初始化依赖视图尺寸。
        // 在那之前载图既可能白跑一次（随后尺寸变化还要重来），也是"同一页被解码
        // 多次"的一个来源，而每次解码都要一整张图的 ByteBuffer（见下）。
        update = { created -> view = created },
    )

    // 载入这一页的图像流。
    //
    // 两件事必须同时做到：
    // 1. **等视图有尺寸再 setImage**：尺寸为 0 时库的瓦片初始化行为不可预期，
    //    真机日志里出现过 `setImage: 001.jpg view=0x0`；
    // 2. **同一页只 setImage 一次**：见下面关于"整图解码"的说明。
    //
    // 流在协程里先准备好，布局回调只负责 `setImage`。
    LaunchedEffect(page.pageId, source, prefetcher) {
        val target = view ?: return@LaunchedEffect
        decodeFailed = false
        val imageSource = runCatching { buildImageSource(source, page, prefetcher) }.getOrNull()
        if (imageSource == null) {
            decodeFailed = true
            return@LaunchedEffect
        }
        target.doOnLayout {
            if (target.getTag(IMAGE_LOADED_TAG) == page.pageId) return@doOnLayout
            target.setTag(IMAGE_LOADED_TAG, page.pageId)
            target.setImage(imageSource)
        }
    }

    DisposableEffect(page.pageId) {
        val target = view
        onDispose {
            // 清理顺序有讲究：先摘监听器，再 recycle。
            //
            // `recycle()` 只释放解码器持有的瓦片与位图；正在跑的 `TilesInitTask` 是
            // 库内部的 AsyncTask，它完成时仍会回调监听器。先摘掉监听器可以避免
            // 已经离开屏幕的页面再触发一次状态更新（那会让 Compose 重新组合一个
            // 已经销毁的节点）。
            target?.setOnImageEventListener(null)
            target?.setTag(IMAGE_LOADED_TAG, null)
            target?.recycle()
            view = null
        }
    }
}

/** 标记"这一页已经载图"，防止重组时重复整图解码。见上面的载图分支。 */
private const val IMAGE_LOADED_TAG = -0x4C4D52 // 负数，避开库与框架可能使用的正数 tag key

/**
 * 构造交给引擎的图源，必要时先降采样。
 *
 * ## 为什么需要这一步（这是 OOM 的关键）
 *
 * 反编译 `com.davemorrissey.labs.subscaleview.decoder.Decoder` 后确认：这个 fork 的解码器
 * **不管输入是文件流还是 Bitmap，都会先把整张图完整解码出来**
 * （`Decoder.init` 无条件调用 `InputProvider.openStream()`，把结果读成一个 `ByteBuffer`
 * 再转 byte[]），而且位图格式**硬编码 `ARGB_8888`**。因此：
 *
 * - `setPreferredBitmapConfig(RGB_565)` 与 `setMaxTileSize` 对它**完全无效**；
 * - 每载一页都要付一次"整图 × 4 字节"的代价：3024×1700 约 20MB，
 *   而真机的堆增长上限是 256MB。真机上那笔失败的 `8294416` 字节分配就是它。
 *
 * 既然无法从外部换掉解码器（`TilesInitTask` 里是硬编码 `new Decoder(...)`，
 * `decoder` 字段 private 且无 setter），就改为**控制送进去的像素量**：
 * 超过 [MAX_ENGINE_LONG_EDGE] 的图先按 2 的幂降采样再编码成 PNG（无损，避免二次 JPEG 损失），
 * 于是那次必然发生的整图分配被压到预算内。
 *
 * ## 代价与边界
 *
 * 降采样会降低放大后的清晰度。阈值取 3000 是因为：真机屏宽 1440，
 * 库的 `minimumTileDpi(180)` 在 1440 宽下要的瓦片也在这个量级；而绝大多数漫画页
 * 长边不超过 3000，因此**这条路径通常根本不触发**。只有超大跨页才会被采样，
 * 那种图原本就是 OOM 的来源。
 *
 * ## 编码为什么用 PNG
 *
 * 页图多为 JPEG，再编一次 JPEG 会叠加有损损失；PNG 无损且这里只做"搬运"。
 * 代价是编码后的字节比 JPEG 大，但那只是一次性的内存内缓冲区，
 * 相比省下的整图 ARGB 分配是划算的。
 */
private suspend fun buildImageSource(
    source: PageSource,
    page: ReaderPage,
    prefetcher: PagePrefetcher?,
): ImageSource? {
    // 预取命中时优先走本地字节：省掉一次跨进程的 `openInputStream`，而且字节已经在磁盘上，
    // 尺寸可以从头部读出来，于是下面的降采样探测与二次读流也一并不必做。
    //
    // 尺寸仍然要看：超过 `MAX_ENGINE_LONG_EDGE` 的页必须降采样（那是真机上 OOM 的来源），
    // 而预取缓存里放的是**原始字节**，不保证已经够小。过大的页因此落回常规路径。
    if (prefetcher != null) {
        val bytes = prefetcher.stream(page.pageId)?.use { it.readBytes() }
        if (bytes != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest in 1..MAX_ENGINE_LONG_EDGE) {
                return ImageSource.provider { java.io.ByteArrayInputStream(bytes) }
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { source.open(page).use { BitmapFactory.decodeStream(it, null, bounds) } }
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
        // 读不到尺寸（格式不支持等）时不猜：把原始流交给库，由它给出失败回调。
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }

    val longest = maxOf(width, height)
    if (longest <= MAX_ENGINE_LONG_EDGE) {
        // 常见情形：不采样，直接把原始流交给库，一个字节都不多搬。
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }

    var sample = 1
    while (longest / (sample * 2) >= MAX_ENGINE_LONG_EDGE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val sampled = runCatching {
        source.open(page).use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull() ?: run {
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }

    // 立刻把降采样后的位图编成流并回收：库后面会自己再解一次流，
    // 因此这里不能把位图留着，否则峰值变成"位图 + 库的整图解码"两份。
    val bytes = runCatching {
        java.io.ByteArrayOutputStream().use { buffer ->
            sampled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, buffer)
            buffer.toByteArray()
        }
    }.getOrNull()
    sampled.recycle()
    if (bytes == null) {
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }
    return ImageSource.provider { java.io.ByteArrayInputStream(bytes) }
}

/** 交给引擎前允许的最长边；超过则按 2 的幂降采样。见 [buildImageSource]。 */
private const val MAX_ENGINE_LONG_EDGE = 3000

/**
 * 应用与 Mihon 同源的引擎配置。
 *
 * 数值全部对照 Mihon `ReaderPageImageView`：
 * - `maxScale = scale * 5`（`MAX_ZOOM_SCALE = 5F`）
 * - 双击目标 `scale * 2`
 * - `setDoubleTapZoomStyle(ZOOM_FOCUS_CENTER)`、`setPanLimit(PAN_LIMIT_INSIDE)`
 * - `setMinimumTileDpi(180)`、`setMinimumDpi(1)`
 */
private fun configure(
    view: SubsamplingScaleImageView,
    settings: ReaderSettings,
    onReady: () -> Unit,
    onError: () -> Unit,
) {
    view.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
    view.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
    view.setMinimumTileDpi(MIN_TILE_DPI)
    view.setMinimumDpi(MIN_DPI)
    // 限制单块瓦片的像素。默认值在 1440 宽的屏上会取到约 3000×1500 的块
    // （565 下约 9MB），而真机上已经出现过解码期 OOM。2048 把峰值压到约 4MB，
    // 代价只是每页多几次区域解码。
    view.setMaxTileSize(MAX_TILE_PX)
    view.setMinimumScaleType(settings.imageScaleType.toLibraryScaleType())
    // 裁白边是 fork 相对上游 SSIV 的新增能力，也是我们唯一无法自己等价实现的一项
    // （纯 Compose 只能裁掉溢出部分，做不到按内容裁白）。
    view.setCropBorders(settings.effectiveCropBorders)
    view.setDoubleTapZoomDuration(settings.doubleTapAnimMillis.coerceAtLeast(1))
    view.setOnImageEventListener(
        object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() {
                // 缩放上限与双击目标必须在图片就绪后设置：它们都以当前初始缩放为基准，
                // 而初始缩放要等库算出适配比例才存在（Mihon 的 `setupZoom` 同理）。
                val base = view.scale
                view.maxScale = base * MAX_ZOOM_SCALE
                view.setDoubleTapZoomScale(base * DOUBLE_TAP_ZOOM_FACTOR)
                applyZoomStart(view, settings)
                onReady()
            }

            override fun onImageLoadError(e: Exception) {
                onError()
            }
        },
    )
}


/**
 * 起始可见位置（Mihon `pref_zoom_start_key`）。
 *
 * 它**不改变缩放比例**，只移动可见窗口：宽图在初始缩放下本来就溢出屏幕，
 * 左右两种默认值让读者先看到该先看的那一半。因为库设了 `PAN_LIMIT_INSIDE`，
 * 请求的中心会被夹回合法范围。
 */
private fun applyZoomStart(view: SubsamplingScaleImageView, settings: ReaderSettings) {
    if (!view.isReady) return
    val scale = view.scale
    val target = when (settings.zoomStart.resolve(settings.readingMode)) {
        ZoomStart.LEFT -> PointF(0f, 0f)
        ZoomStart.RIGHT -> PointF(view.sWidth.toFloat(), 0f)
        ZoomStart.CENTER, ZoomStart.AUTOMATIC ->
            PointF(view.sWidth / 2f, view.sHeight / 2f)
    }
    view.setScaleAndCenter(scale, target)
}

/**
 * 缩放类型映射。
 *
 * 这个 fork 的常量集合**正好覆盖** Mihon 的六种 `ImageScaleType`
 * （上游 3.10.0 只有 `CENTER_INSIDE` / `CENTER_CROP` / `CUSTOM` / `START` 四个，
 * 且没有 `setCropBorders`——因此这里必须用 fork，不能用上游）：
 *
 * | 我们的值 | 库常量 |
 * |---|---|
 * | [ImageScaleType.FIT_SCREEN] | `SCALE_TYPE_CENTER_INSIDE` |
 * | [ImageScaleType.FIT_WIDTH] | `SCALE_TYPE_FIT_WIDTH` |
 * | [ImageScaleType.FIT_HEIGHT] | `SCALE_TYPE_FIT_HEIGHT` |
 * | [ImageScaleType.ORIGINAL_SIZE] | `SCALE_TYPE_ORIGINAL_SIZE` |
 * | [ImageScaleType.SMART_FIT] | `SCALE_TYPE_SMART_FIT` |
 * | [ImageScaleType.STRETCH] | `SCALE_TYPE_CENTER_CROP` |
 *
 * `STRETCH`（"拉伸填满"）是本组里唯一的近似：库没有"不保持比例地拉伸"这一项，
 * `CENTER_CROP` 是"填满并使短边对齐、超出部分裁掉"。两者都会铺满视口，差别在于
 * 是否变形。之所以不自己算矩阵去实现真拉伸：那会绕开库的分块绘制路径，
 * 在超大图上反而丢掉内存保护。
 */
private fun ImageScaleType.toLibraryScaleType(): Int = when (this) {
    ImageScaleType.FIT_SCREEN -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
    ImageScaleType.FIT_WIDTH -> SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
    ImageScaleType.FIT_HEIGHT -> SubsamplingScaleImageView.SCALE_TYPE_FIT_HEIGHT
    ImageScaleType.ORIGINAL_SIZE -> SubsamplingScaleImageView.SCALE_TYPE_ORIGINAL_SIZE
    ImageScaleType.SMART_FIT -> SubsamplingScaleImageView.SCALE_TYPE_SMART_FIT
    ImageScaleType.STRETCH -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
}

/** Mihon `ReaderPageImageView.MAX_ZOOM_SCALE`。 */
private const val MAX_ZOOM_SCALE = 5F

/** Mihon 双击缩放目标：初始缩放的 2 倍。 */
private const val DOUBLE_TAP_ZOOM_FACTOR = 2f

/** Mihon `setMinimumTileDpi(180)` / `setMinimumDpi(1)`。 */
private const val MIN_TILE_DPI = 180
private const val MIN_DPI = 1

/** 单块瓦片的最大边长（像素）。见 `configure` 里的内存说明。 */
private const val MAX_TILE_PX = 2048
