package com.lmreader.core.storage.settings

import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderHideThreshold
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.TapInvert
import com.lmreader.core.model.TapZones
import com.lmreader.core.model.ZoomStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ReaderSettingsCodec] 的往返与容错测试。
 *
 * 这组用例保护的是"用户设置静默丢失"这一类问题：编码只写与默认值不同的字段，
 * 所以一旦某个字段被漏写、键名被改、或解析时的默认值取错，用户改动就会在下次启动
 * 无声地消失——没有任何崩溃或日志。因此除了逐字段往返，还专门覆盖：
 * - 默认值整体往返（空串路径）；
 * - 显式关闭与默认值相反的情况（防止"默认值翻转后旧数据被误读"）；
 * - 未知键、损坏行、越界数值的容错。
 */
class ReaderSettingsCodecTest {

    @Test
    fun `默认设置往返后仍然是默认值`() {
        val defaults = ReaderSettings()

        val encoded = ReaderSettingsCodec.encode(defaults)

        assertEquals("", encoded, "默认设置不该写出任何字段")
        assertEquals(defaults, ReaderSettingsCodec.decode(encoded))
    }

    @Test
    fun `空值与空白输入回退默认设置`() {
        assertEquals(ReaderSettings(), ReaderSettingsCodec.decode(null))
        assertEquals(ReaderSettings(), ReaderSettingsCodec.decode(""))
        assertEquals(ReaderSettings(), ReaderSettingsCodec.decode("   "))
        assertEquals(ReaderSettings(), ReaderSettingsCodec.decode("\n\n"))
    }

    @Test
    fun `每个字段单独往返都保持一致`() {
        // 逐字段构造"与默认值不同"的设置，确保没有一个字段被漏写或写错键名。
        val variants = listOf(
            ReaderSettings(readingMode = ReadingMode.WEBTOON),
            ReaderSettings(readingMode = ReadingMode.LEFT_TO_RIGHT),
            ReaderSettings(readingMode = ReadingMode.VERTICAL),
            ReaderSettings(readingMode = ReadingMode.CONTINUOUS_VERTICAL),
            ReaderSettings(orientation = ReaderOrientation.LOCKED_LANDSCAPE),
            ReaderSettings(orientation = ReaderOrientation.REVERSE_PORTRAIT),
            ReaderSettings(pagerTapZones = TapZones.EDGE),
            ReaderSettings(webtoonTapZones = TapZones.KINDLISH),
            ReaderSettings(pagerTapInvert = TapInvert.HORIZONTAL),
            ReaderSettings(webtoonTapInvert = TapInvert.BOTH),
            ReaderSettings(imageScaleType = ImageScaleType.SMART_FIT),
            ReaderSettings(zoomStart = ZoomStart.CENTER),
            ReaderSettings(pageTransitions = false),
            ReaderSettings(doubleTapAnimMillis = 250),
            ReaderSettings(volumeKeys = true),
            ReaderSettings(volumeKeysInverted = true),
            ReaderSettings(longTapActions = false),
            ReaderSettings(cropBorders = true),
            ReaderSettings(cropBordersWebtoon = true),
            ReaderSettings(panWideImages = false),
            ReaderSettings(landscapeZoom = false),
            ReaderSettings(webtoonSidePadding = 25),
            ReaderSettings(webtoonDoubleTapZoom = false),
            ReaderSettings(webtoonDisableZoomOut = true),
            ReaderSettings(hideThreshold = ReaderHideThreshold.HIGHEST),
            ReaderSettings(theme = ReaderTheme.WHITE),
            ReaderSettings(showPageNumber = false),
            ReaderSettings(fullscreen = false),
            ReaderSettings(keepScreenOn = true),
            ReaderSettings(alwaysShowChapterTransition = false),
            ReaderSettings(showTapZoneOverlayOnStart = true),
            ReaderSettings(showTapZoneOverlayOnce = false),
            ReaderSettings(showReadingMode = false),
            ReaderSettings(grayscale = true),
            ReaderSettings(invertedColors = true),
        )

        for (variant in variants) {
            val roundTripped = ReaderSettingsCodec.decode(ReaderSettingsCodec.encode(variant))
            assertEquals(variant, roundTripped, "字段往返不一致：$variant")
        }
    }

    @Test
    fun `全字段同时改动也能完整往返`() {
        val everything = ReaderSettings(
            readingMode = ReadingMode.CONTINUOUS_VERTICAL,
            orientation = ReaderOrientation.LANDSCAPE,
            pagerTapZones = TapZones.L_SHAPED,
            webtoonTapZones = TapZones.RIGHT_AND_LEFT,
            pagerTapInvert = TapInvert.VERTICAL,
            webtoonTapInvert = TapInvert.HORIZONTAL,
            imageScaleType = ImageScaleType.FIT_HEIGHT,
            zoomStart = ZoomStart.RIGHT,
            pageTransitions = false,
            doubleTapAnimMillis = 1,
            volumeKeys = true,
            volumeKeysInverted = true,
            longTapActions = false,
            cropBorders = true,
            cropBordersWebtoon = true,
            panWideImages = false,
            landscapeZoom = false,
            webtoonSidePadding = 13,
            webtoonDoubleTapZoom = false,
            webtoonDisableZoomOut = true,
            hideThreshold = ReaderHideThreshold.LOWEST,
            theme = ReaderTheme.AUTO,
            showPageNumber = false,
            fullscreen = false,
            keepScreenOn = true,
            alwaysShowChapterTransition = false,
            showTapZoneOverlayOnStart = true,
            showTapZoneOverlayOnce = false,
            showReadingMode = false,
            grayscale = true,
            invertedColors = true,
        )

        assertEquals(everything, ReaderSettingsCodec.decode(ReaderSettingsCodec.encode(everything)))
    }

    @Test
    fun `显式关闭默认开启的字段不会被默认值覆盖`() {
        // 这是本编码格式最关键的用例。若 putFlag 只写"与默认相反"的那一侧，
        // 默认 true 的字段被用户关掉后，默认值一旦翻转就会被静默读回 true。
        val settings = ReaderSettings(
            pageTransitions = false,
            longTapActions = false,
            panWideImages = false,
            landscapeZoom = false,
            webtoonDoubleTapZoom = false,
            showPageNumber = false,
            fullscreen = false,
            alwaysShowChapterTransition = false,
            showReadingMode = false,
        )

        val encoded = ReaderSettingsCodec.encode(settings)

        assertTrue(encoded.contains("transitions=false"), "必须显式写出 false：$encoded")
        val decoded = ReaderSettingsCodec.decode(encoded)
        assertFalse(decoded.pageTransitions)
        assertFalse(decoded.longTapActions)
        assertFalse(decoded.panWideImages)
        assertFalse(decoded.landscapeZoom)
        assertFalse(decoded.webtoonDoubleTapZoom)
        assertFalse(decoded.showPageNumber)
        assertFalse(decoded.fullscreen)
        assertFalse(decoded.alwaysShowChapterTransition)
        assertFalse(decoded.showReadingMode)
    }

    @Test
    fun `显式开启默认关闭的字段不会被默认值覆盖`() {
        val settings = ReaderSettings(volumeKeys = true, grayscale = true, keepScreenOn = true)

        val decoded = ReaderSettingsCodec.decode(ReaderSettingsCodec.encode(settings))

        assertTrue(decoded.volumeKeys)
        assertTrue(decoded.grayscale)
        assertTrue(decoded.keepScreenOn)
    }

    @Test
    fun `方向未设置时往返仍是未设置`() {
        val settings = ReaderSettings(orientation = null)

        assertNull(ReaderSettingsCodec.decode(ReaderSettingsCodec.encode(settings)).orientation)
        assertNull(ReaderSettingsCodec.decode(null).orientation)
    }

    @Test
    fun `未知键被忽略且不影响已识别字段`() {
        val encoded = ReaderSettingsCodec.encode(ReaderSettings(readingMode = ReadingMode.WEBTOON))
        val tampered = "$encoded\nsomeFutureKey=whatever\nanother=1"

        val decoded = ReaderSettingsCodec.decode(tampered)

        assertEquals(ReadingMode.WEBTOON, decoded.readingMode)
    }

    @Test
    fun `无法识别的枚举值回退该字段默认值而不是丢弃整份设置`() {
        // 模拟旧版本写下的模式名，或外部改坏了这一个字段。
        val tampered = "mode=NOT_A_MODE\ntheme=WHITE\npadding-not-a-key=3\nwebtoonPadding=7"

        val decoded = ReaderSettingsCodec.decode(tampered)

        assertEquals(ReadingMode.DEFAULT, decoded.readingMode, "坏值只回退本字段")
        assertEquals(ReaderTheme.WHITE, decoded.theme, "其余字段仍然生效")
        assertEquals(7, decoded.webtoonSidePadding)
    }

    @Test
    fun `损坏的行被跳过而不影响其余字段`() {
        val tampered = "=noKeyValue\nmode=WEBTOON\nnoSeparatorLine\ntheme=GRAY"

        val decoded = ReaderSettingsCodec.decode(tampered)

        assertEquals(ReadingMode.WEBTOON, decoded.readingMode)
        assertEquals(ReaderTheme.GRAY, decoded.theme)
    }

    @Test
    fun `越界的数值被夹到合法区间`() {
        assertEquals(
            25,
            ReaderSettingsCodec.decode("webtoonPadding=9999").webtoonSidePadding,
            "侧边距上限是 25（Mihon WEBTOON_PADDING_MAX）",
        )
        assertEquals(
            0,
            ReaderSettingsCodec.decode("webtoonPadding=-5").webtoonSidePadding,
        )
        assertEquals(
            2000,
            ReaderSettingsCodec.decode("doubleTapAnim=999999").doubleTapAnimMillis,
        )
        // 完全不是数字时回退默认值 500。
        assertEquals(
            500,
            ReaderSettingsCodec.decode("doubleTapAnim=abc").doubleTapAnimMillis,
        )
    }

    @Test
    fun `只写与默认值不同的字段`() {
        val encoded = ReaderSettingsCodec.encode(ReaderSettings(readingMode = ReadingMode.WEBTOON))

        assertEquals("mode=WEBTOON", encoded)
    }

    @Test
    fun `二次编码结果稳定`() {
        val once = ReaderSettingsCodec.encode(ReaderSettings(theme = ReaderTheme.GRAY, volumeKeys = true))

        val twice = ReaderSettingsCodec.encode(ReaderSettingsCodec.decode(once))

        assertEquals(once, twice, "解码再编码必须得到同一份文本")
    }

    @Test
    fun `布尔字段只接受小写 true 与 false`() {
        // 只有 "true" / "false" 是合法布尔字面量，其余一律回退默认值（volumeKeys 默认 false）。
        assertFalse(ReaderSettingsCodec.decode("volumeKeys=TRUE").volumeKeys)
        assertFalse(ReaderSettingsCodec.decode("volumeKeys=1").volumeKeys)
        assertFalse(ReaderSettingsCodec.decode("volumeKeys=").volumeKeys)
    }
}
