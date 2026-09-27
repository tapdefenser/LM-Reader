package com.lmreader.ui.reader

import android.app.Activity
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lmreader.core.model.ReaderSettings
import kotlin.math.abs

/**
 * 阅读器显示效果：亮度、灰度、反色、全屏与"阅读时常亮"。
 *
 * ## 为什么整屏效果放在这里而不是分散到各组件
 *
 * 灰度与反色是**整屏作用**的（页面、控制栏、遮罩都要一起变），而 Compose 的
 * `ColorFilter` 只能作用于单个绘制节点。放在最外层的一个 `drawWithCache` 里叠加
 * 整屏 Paint，是唯一能覆盖全部子内容、又不必给每个子节点都传滤镜的做法。
 *
 * ## 亮度的三段语义（照搬 Mihon `customBrightnessValue`）
 *
 * | 取值 | 行为 |
 * |---|---|
 * | `0` | 不干预，跟随系统 |
 * | `1..100` | 窗口亮度设为 `v/100` |
 * | `-75..-1` | 窗口亮度钉在最低，再叠一层半透明黑（alpha = `\|v\|/100`） |
 *
 * 负值区间为什么不用窗口亮度实现：窗口亮度的下限在各设备上不一致，用叠加层才能得到
 * 可控的"更暗"。这也是 Mihon 的做法。
 *
 * 退出阅读器必须把窗口亮度恢复成 `BRIGHTNESS_OVERRIDE_NONE`，否则回到书架后屏幕会
 * 一直停在用户为阅读设的暗度上。
 */
@Composable
internal fun ReaderDisplayEffects(
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val window = remember(view) { (view.context as? Activity)?.window }

    // 常亮：只在阅读器存活期间生效，离开即清除。
    DisposableEffect(window, settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 亮度：按三段语义设置窗口亮度，离开时恢复。
    DisposableEffect(window, settings.customBrightness, settings.customBrightnessValue) {
        val attributes = window?.attributes
        if (window != null && attributes != null) {
            attributes.screenBrightness = when {
                !settings.customBrightness -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                settings.customBrightnessValue > 0 -> settings.customBrightnessValue / 100f
                settings.customBrightnessValue < 0 -> MIN_WINDOW_BRIGHTNESS
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = attributes
        }
        onDispose {
            val current = window?.attributes
            if (window != null && current != null) {
                current.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = current
            }
        }
    }

    // 全屏：隐藏系统栏；离开时恢复，否则用户回到书架会看不到状态栏。
    DisposableEffect(window, view, settings.fullscreen) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (settings.fullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 暗化叠加层的 alpha：只在负值区间生效。
    val dimAlpha = if (settings.customBrightness && settings.customBrightnessValue < 0) {
        (abs(settings.customBrightnessValue) / 100f).coerceIn(0f, MAX_DIM_ALPHA)
    } else {
        0f
    }

    // 灰度/反色用一个整屏 Paint 叠加。Paint 只在设置变化时重建，而不是每帧重建——
    // 后者会在滚动时产生可见的分配压力。
    val colorFilterPaint = remember(settings.grayscale, settings.invertedColors) {
        if (!settings.grayscale && !settings.invertedColors) {
            null
        } else {
            Paint().apply {
                colorFilter = ColorMatrixColorFilter(buildColorMatrix(settings))
                isAntiAlias = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val paint = colorFilterPaint
                val alpha = dimAlpha
                val dimArgb = android.graphics.Color.argb((alpha * 255f).toInt(), 0, 0, 0)
                onDrawWithContent {
                    if (paint == null) {
                        drawContent()
                    } else {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val checkpoint = native.saveLayer(
                                0f, 0f, size.width, size.height, paint,
                            )
                            try {
                                drawContent()
                            } finally {
                                native.restoreToCount(checkpoint)
                            }
                        }
                    }
                    // 暗化应在滤镜之后叠加，否则反色会把黑色暗化层变白。
                    if (alpha > 0f) drawIntoCanvas { it.nativeCanvas.drawColor(dimArgb) }
                }
            },
    ) {
        content()
    }
}

/**
 * 构造整屏颜色矩阵。
 *
 * 顺序有讲究：先反色再灰度。反过来做时灰度已经把三通道压成同一个值，
 * 再取反会得到"灰底反色"，与"反色后变灰"是两种不同观感，后者才是用户预期的叠加。
 */
private fun buildColorMatrix(settings: ReaderSettings): ColorMatrix {
    val matrix = ColorMatrix()
    if (settings.invertedColors) {
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    if (settings.grayscale) {
        matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })
    }
    return matrix
}

/** 负值亮度区间把窗口亮度钉在这个值（与 Mihon 一致）。 */
private const val MIN_WINDOW_BRIGHTNESS = 0.01f

/** 暗化上限：再暗下去页面就完全不可读了。 */
private const val MAX_DIM_ALPHA = 0.75f
