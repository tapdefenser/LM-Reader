package com.lmreader.ui.reader

import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ZoomStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ReaderSettings.imageConfig] 的纯单元测试：验证分页/连续两套模式各自投影出的图片参数。
 *
 * 只断言数据投影，不涉及 Android 框架、解码器或任何设备能力。
 */
class ReaderImageConfigTest {

    private val pagedModes = listOf(
        ReadingMode.LEFT_TO_RIGHT,
        ReadingMode.RIGHT_TO_LEFT,
        ReadingMode.VERTICAL,
    )

    private val continuousModes = listOf(
        ReadingMode.WEBTOON,
        ReadingMode.CONTINUOUS_VERTICAL,
    )

    @Test
    fun `all six paged scale choices are preserved`() {
        assertEquals(6, ImageScaleType.entries.size)
        for (mode in pagedModes) {
            for (scale in ImageScaleType.entries) {
                val config = ReaderSettings(readingMode = mode, imageScaleType = scale).imageConfig()
                assertEquals(scale, config.scaleType, "mode=$mode scale=$scale")
            }
        }
    }

    @Test
    fun `automatic zoom start resolves left right and center by direction`() {
        val ltr = ReaderSettings(
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            zoomStart = ZoomStart.AUTOMATIC,
        ).imageConfig()
        assertEquals(ZoomStart.LEFT, ltr.zoomStart)

        val rtl = ReaderSettings(
            readingMode = ReadingMode.RIGHT_TO_LEFT,
            zoomStart = ZoomStart.AUTOMATIC,
        ).imageConfig()
        assertEquals(ZoomStart.RIGHT, rtl.zoomStart)

        val vertical = ReaderSettings(
            readingMode = ReadingMode.VERTICAL,
            zoomStart = ZoomStart.AUTOMATIC,
        ).imageConfig()
        assertEquals(ZoomStart.CENTER, vertical.zoomStart)
    }

    @Test
    fun `explicit zoom start positions are preserved on paged modes`() {
        for (mode in pagedModes) {
            for (zoom in listOf(ZoomStart.LEFT, ZoomStart.RIGHT, ZoomStart.CENTER)) {
                val config = ReaderSettings(readingMode = mode, zoomStart = zoom).imageConfig()
                assertEquals(zoom, config.zoomStart, "mode=$mode zoom=$zoom")
            }
        }
    }

    @Test
    fun `continuous modes normalize scale zoom and landscape`() {
        for (mode in continuousModes) {
            val config = ReaderSettings(
                readingMode = mode,
                imageScaleType = ImageScaleType.ORIGINAL_SIZE,
                zoomStart = ZoomStart.LEFT,
                landscapeZoom = true,
            ).imageConfig()

            assertEquals(ImageScaleType.FIT_WIDTH, config.scaleType, "mode=$mode")
            assertEquals(ZoomStart.CENTER, config.zoomStart, "mode=$mode")
            assertFalse(config.landscapeZoom, "mode=$mode")
        }
    }

    @Test
    fun `continuous modes use webtoon crop borders and ignore paged crop borders`() {
        for (mode in continuousModes) {
            val webtoonCrop = ReaderSettings(
                readingMode = mode,
                cropBorders = false,
                cropBordersWebtoon = true,
            ).imageConfig()
            assertTrue(webtoonCrop.cropBorders, "mode=$mode should use cropBordersWebtoon")

            val pagedCropOnly = ReaderSettings(
                readingMode = mode,
                cropBorders = true,
                cropBordersWebtoon = false,
            ).imageConfig()
            assertFalse(pagedCropOnly.cropBorders, "mode=$mode should ignore cropBorders")
        }
    }

    @Test
    fun `paged modes use paged crop borders and ignore webtoon crop borders`() {
        val pagedCrop = ReaderSettings(
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            cropBorders = true,
            cropBordersWebtoon = false,
        ).imageConfig()
        assertTrue(pagedCrop.cropBorders)

        val webtoonCropOnly = ReaderSettings(
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            cropBorders = false,
            cropBordersWebtoon = true,
        ).imageConfig()
        assertFalse(webtoonCropOnly.cropBorders)
    }

    @Test
    fun `webtoon disable zoom out applies only to continuous modes`() {
        for (mode in continuousModes) {
            assertTrue(
                ReaderSettings(readingMode = mode, webtoonDisableZoomOut = true).imageConfig().disableZoomOut,
                "mode=$mode",
            )
            assertFalse(
                ReaderSettings(readingMode = mode, webtoonDisableZoomOut = false).imageConfig().disableZoomOut,
                "mode=$mode",
            )
        }

        for (mode in pagedModes) {
            assertFalse(
                ReaderSettings(readingMode = mode, webtoonDisableZoomOut = true).imageConfig().disableZoomOut,
                "mode=$mode",
            )
        }
    }

    @Test
    fun `theme preload and brightness do not affect the image config`() {
        val base = ReaderSettings(
            readingMode = ReadingMode.RIGHT_TO_LEFT,
            imageScaleType = ImageScaleType.SMART_FIT,
            zoomStart = ZoomStart.RIGHT,
            cropBorders = true,
            landscapeZoom = true,
        )
        val expected = base.imageConfig()

        val changed = base.copy(
            theme = ReaderTheme.WHITE,
            preloadPages = 17,
            customBrightness = true,
            customBrightnessValue = -40,
        )
        assertEquals(expected, changed.imageConfig())
    }

    @Test
    fun `each relevant paged image property changes the image config`() {
        val base = ReaderSettings(
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            imageScaleType = ImageScaleType.FIT_SCREEN,
            zoomStart = ZoomStart.LEFT,
            cropBorders = false,
            landscapeZoom = false,
        )
        val baseConfig = base.imageConfig()

        assertNotEquals(baseConfig, base.copy(imageScaleType = ImageScaleType.FIT_HEIGHT).imageConfig())
        assertNotEquals(baseConfig, base.copy(zoomStart = ZoomStart.RIGHT).imageConfig())
        assertNotEquals(baseConfig, base.copy(cropBorders = true).imageConfig())
        assertNotEquals(baseConfig, base.copy(landscapeZoom = true).imageConfig())
    }

    @Test
    fun `webtoon only properties do not change the paged image config`() {
        val base = ReaderSettings(
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            imageScaleType = ImageScaleType.FIT_SCREEN,
            zoomStart = ZoomStart.LEFT,
            cropBorders = false,
            cropBordersWebtoon = false,
            landscapeZoom = false,
            webtoonDisableZoomOut = false,
        )
        val baseConfig = base.imageConfig()

        assertEquals(baseConfig, base.copy(cropBordersWebtoon = true).imageConfig())
        assertEquals(baseConfig, base.copy(webtoonDisableZoomOut = true).imageConfig())
    }
}
