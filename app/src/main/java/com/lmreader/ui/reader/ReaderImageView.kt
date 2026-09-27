package com.lmreader.ui.reader

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
    // 2. **同一页只 setImage 一次**：每次载图都会把整张图解码进一个 ByteBuffer
    //    （ARGB_8888 下 3024×1700 约 20MB），而进程堆上限 256MB。重组或尺寸变化
    //    触发第二次整图解码是 OOM 最可能的来源，因此用视图上的标记挡住。
    //
    // 流在协程里先打开（`PageSource.open` 是 suspend），布局回调只负责 `setImage`。
    LaunchedEffect(page.pageId, source) {
        val target = view ?: return@LaunchedEffect
        decodeFailed = false
        val stream = runCatching { source.open(page) }.getOrNull()
        if (stream == null) {
            decodeFailed = true
            return@LaunchedEffect
        }
        target.doOnLayout {
            if (target.getTag(IMAGE_LOADED_TAG) == page.pageId) return@doOnLayout
            target.setTag(IMAGE_LOADED_TAG, page.pageId)
            target.setImage(ImageSource.inputStream(stream))
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
