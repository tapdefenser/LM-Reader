package com.lmreader.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 点按区域与阅读偏好解析的单元测试。
 *
 * 这些用例保护两类**容易静默出错**的行为：
 * 1. 区域矩形的边界语义（Mihon 用左闭右开，相邻区域共享边界时结果由列表顺序决定）；
 * 2. "跟随阅读模式"与"自动缩放起点"这类**派生值**的解析规则。
 * 两者错了都不会崩溃，只会让点击落到错误的动作上，靠真机很难逐点回归。
 */
class ReaderNavigationTest {

    // ------------------------------------------------------------ 区域矩形语义

    @Test
    fun `矩形命中是左闭右开`() {
        val rect = NormalizedRect(0f, 0f, 0.33f, 1f)

        assertTrue(rect.contains(0f, 0.5f), "左边界属于本区域")
        assertFalse(rect.contains(0.33f, 0.5f), "右边界不属于本区域")
        assertFalse(rect.contains(0.1f, -0.01f), "上边界之外")
        assertFalse(rect.contains(0.1f, 1f), "下边界之外")
    }

    @Test
    fun `水平反转按竖直中线镜像`() {
        val left = NormalizedRect(0f, 0f, 0.33f, 1f)

        val mirrored = left.invert(TapInvert.HORIZONTAL)

        assertEquals(0.67f, mirrored.left, TOLERANCE)
        assertEquals(1f, mirrored.right, TOLERANCE)
        assertEquals(0f, mirrored.top, TOLERANCE)
        assertEquals(1f, mirrored.bottom, TOLERANCE)
    }

    @Test
    fun `垂直反转按水平中线镜像且不动左右`() {
        val top = NormalizedRect(0f, 0f, 1f, 0.33f)

        val mirrored = top.invert(TapInvert.VERTICAL)

        assertEquals(0f, mirrored.left, TOLERANCE)
        assertEquals(1f, mirrored.right, TOLERANCE)
        assertEquals(0.67f, mirrored.top, TOLERANCE)
        assertEquals(1f, mirrored.bottom, TOLERANCE)
    }

    @Test
    fun `两者都反转等价于一百八十度点对称`() {
        val rect = NormalizedRect(0.33f, 0f, 1f, 0.33f)

        val mirrored = rect.invert(TapInvert.BOTH)

        assertEquals(0f, mirrored.left, TOLERANCE)
        assertEquals(0.67f, mirrored.right, TOLERANCE)
        assertEquals(0.67f, mirrored.top, TOLERANCE)
        assertEquals(1f, mirrored.bottom, TOLERANCE)
    }

    @Test
    fun `不反转时矩形不变`() {
        val rect = NormalizedRect(0.1f, 0.2f, 0.3f, 0.4f)

        assertEquals(rect, rect.invert(TapInvert.NONE))
    }

    // ------------------------------------------------------------ 各布局命中

    @Test
    fun `左右两栏中间三分之一是菜单区`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        assertEquals(TapAction.PAN_LEFT, hit(TapZones.RIGHT_AND_LEFT, TapInvert.NONE, mode, 0.10f, 0.5f))
        assertEquals(TapAction.MENU, hit(TapZones.RIGHT_AND_LEFT, TapInvert.NONE, mode, 0.50f, 0.5f))
        assertEquals(TapAction.PAN_RIGHT, hit(TapZones.RIGHT_AND_LEFT, TapInvert.NONE, mode, 0.90f, 0.5f))
    }

    @Test
    fun `L形上方整条与中部左侧是上一页`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        assertEquals(TapAction.PREVIOUS, hit(TapZones.L_SHAPED, TapInvert.NONE, mode, 0.10f, 0.10f))
        assertEquals(TapAction.PREVIOUS, hit(TapZones.L_SHAPED, TapInvert.NONE, mode, 0.50f, 0.10f))
        assertEquals(TapAction.PREVIOUS, hit(TapZones.L_SHAPED, TapInvert.NONE, mode, 0.10f, 0.50f))
        assertEquals(TapAction.NEXT, hit(TapZones.L_SHAPED, TapInvert.NONE, mode, 0.90f, 0.50f))
        assertEquals(TapAction.NEXT, hit(TapZones.L_SHAPED, TapInvert.NONE, mode, 0.50f, 0.90f))
    }

    @Test
    fun `L形中部正中是菜单区`() {
        // (0.33,0.33)-(0.66,0.66) 这一块不在任何区域里，因此落到菜单。
        assertEquals(
            TapAction.MENU,
            hit(TapZones.L_SHAPED, TapInvert.NONE, ReadingMode.RIGHT_TO_LEFT, 0.50f, 0.50f),
        )
    }

    @Test
    fun `Kindle式上方三分之一是菜单区`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        assertEquals(TapAction.MENU, hit(TapZones.KINDLISH, TapInvert.NONE, mode, 0.10f, 0.10f))
        assertEquals(TapAction.MENU, hit(TapZones.KINDLISH, TapInvert.NONE, mode, 0.50f, 0.10f))
        assertEquals(TapAction.PREVIOUS, hit(TapZones.KINDLISH, TapInvert.NONE, mode, 0.10f, 0.50f))
        assertEquals(TapAction.NEXT, hit(TapZones.KINDLISH, TapInvert.NONE, mode, 0.50f, 0.50f))
    }

    @Test
    fun `边缘布局左右两条是下一页且底部中间是上一页`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        assertEquals(TapAction.NEXT, hit(TapZones.EDGE, TapInvert.NONE, mode, 0.10f, 0.50f))
        assertEquals(TapAction.NEXT, hit(TapZones.EDGE, TapInvert.NONE, mode, 0.90f, 0.50f))
        assertEquals(TapAction.PREVIOUS, hit(TapZones.EDGE, TapInvert.NONE, mode, 0.50f, 0.90f))
        // 第 2 行中间不在任何区域里。
        assertEquals(TapAction.MENU, hit(TapZones.EDGE, TapInvert.NONE, mode, 0.50f, 0.50f))
    }

    @Test
    fun `禁用点按时全部落到菜单而不是没有反应`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        // Mihon 的 DisabledNavigation 是空区域表，而 fallback 返回 MENU，
        // 所以"禁用"的语义是"整屏都能唤出菜单"，不是"点按无反应"。
        assertEquals(TapAction.MENU, hit(TapZones.DISABLED, TapInvert.NONE, mode, 0.10f, 0.10f))
        assertEquals(TapAction.MENU, hit(TapZones.DISABLED, TapInvert.NONE, mode, 0.50f, 0.50f))
        assertEquals(TapAction.MENU, hit(TapZones.DISABLED, TapInvert.NONE, mode, 0.90f, 0.90f))
    }

    @Test
    fun `水平反转把左右两栏对调`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        assertEquals(TapAction.PAN_RIGHT, hit(TapZones.RIGHT_AND_LEFT, TapInvert.HORIZONTAL, mode, 0.10f, 0.5f))
        assertEquals(TapAction.PAN_LEFT, hit(TapZones.RIGHT_AND_LEFT, TapInvert.HORIZONTAL, mode, 0.90f, 0.5f))
        // 中间三分之一仍然在反转后落在中间。
        assertEquals(TapAction.MENU, hit(TapZones.RIGHT_AND_LEFT, TapInvert.HORIZONTAL, mode, 0.50f, 0.5f))
    }

    @Test
    fun `垂直反转把L形的上下对调`() {
        val mode = ReadingMode.RIGHT_TO_LEFT

        // 未反转时上方整条是上一页；反转后上方整条变成下一页。
        assertEquals(TapAction.PREVIOUS, hit(TapZones.L_SHAPED, TapInvert.NONE, mode, 0.50f, 0.10f))
        assertEquals(TapAction.NEXT, hit(TapZones.L_SHAPED, TapInvert.VERTICAL, mode, 0.50f, 0.10f))
        assertEquals(TapAction.PREVIOUS, hit(TapZones.L_SHAPED, TapInvert.VERTICAL, mode, 0.50f, 0.90f))
    }

    // ------------------------------------------------------------ 跟随阅读模式

    @Test
    fun `默认点按区域跟随阅读模式`() {
        // Mihon 的 PagerConfig.defaultNavigation()：竖向给 L 形，其余给左右两栏。
        assertEquals(
            NavigationRegions.L_SHAPED,
            NavigationRegions.resolve(TapZones.DEFAULT, ReadingMode.VERTICAL),
        )
        assertEquals(
            NavigationRegions.RIGHT_AND_LEFT,
            NavigationRegions.resolve(TapZones.DEFAULT, ReadingMode.RIGHT_TO_LEFT),
        )
        assertEquals(
            NavigationRegions.RIGHT_AND_LEFT,
            NavigationRegions.resolve(TapZones.DEFAULT, ReadingMode.LEFT_TO_RIGHT),
        )
        // 条漫也是竖向，因此同样落到 L 形（Mihon WebtoonConfig 显式返回 LNavigation）。
        assertEquals(
            NavigationRegions.L_SHAPED,
            NavigationRegions.resolve(TapZones.DEFAULT, ReadingMode.WEBTOON),
        )
    }

    // ------------------------------------------------------------ 阅读模式本身

    @Test
    fun `阅读模式的方向与连续性标记正确`() {
        assertEquals(ReadingDirection.HORIZONTAL, ReadingMode.LEFT_TO_RIGHT.direction)
        assertFalse(ReadingMode.LEFT_TO_RIGHT.continuous)

        assertEquals(ReadingDirection.HORIZONTAL, ReadingMode.RIGHT_TO_LEFT.direction)
        assertTrue(ReadingMode.RIGHT_TO_LEFT.isRightToLeft)

        assertEquals(ReadingDirection.VERTICAL, ReadingMode.VERTICAL.direction)
        assertFalse(ReadingMode.VERTICAL.continuous, "竖向翻页是整页吸附，不是连续滚动")

        assertEquals(ReadingDirection.VERTICAL, ReadingMode.WEBTOON.direction)
        assertTrue(ReadingMode.WEBTOON.continuous)

        assertEquals(ReadingDirection.VERTICAL, ReadingMode.CONTINUOUS_VERTICAL.direction)
        assertTrue(ReadingMode.CONTINUOUS_VERTICAL.continuous)
    }

    @Test
    fun `无法识别的持久化值回退到默认而不是抛异常`() {
        assertEquals(ReadingMode.DEFAULT, ReadingMode.fromStorage(null))
        assertEquals(ReadingMode.DEFAULT, ReadingMode.fromStorage(""))
        assertEquals(ReadingMode.DEFAULT, ReadingMode.fromStorage("NOPE"))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromStorage("WEBTOON"))

        assertEquals(TapZones.DEFAULT, TapZones.fromStorage("NOPE"))
        assertEquals(TapInvert.NONE, TapInvert.fromStorage("NOPE"))
        assertEquals(ZoomStart.AUTOMATIC, ZoomStart.fromStorage("NOPE"))
        assertEquals(ImageScaleType.FIT_SCREEN, ImageScaleType.fromStorage("NOPE"))
        assertEquals(ReaderTheme.BLACK, ReaderTheme.fromStorage("NOPE"))
        assertEquals(ReaderHideThreshold.DEFAULT, ReaderHideThreshold.fromStorage("NOPE"))
        // 方向的可空哨兵：解析不出来就是"未设置"（跟随系统）。
        assertEquals(null, ReaderOrientation.fromStorage("NOPE"))
    }

    @Test
    fun `默认阅读模式是从右到左`() {
        assertEquals(ReadingMode.RIGHT_TO_LEFT, ReadingMode.DEFAULT)
        assertEquals(ReaderHideThreshold.LOW, ReaderHideThreshold.DEFAULT)
        assertEquals(31, ReaderHideThreshold.DEFAULT.thresholdPx)
    }

    // ------------------------------------------------------------ 缩放起点解析

    @Test
    fun `自动缩放起点按阅读方向解析`() {
        // 从左到右先看左半，从右到左先看右半，竖向看图心。
        assertEquals(ZoomStart.LEFT, ZoomStart.AUTOMATIC.resolve(ReadingMode.LEFT_TO_RIGHT))
        assertEquals(ZoomStart.RIGHT, ZoomStart.AUTOMATIC.resolve(ReadingMode.RIGHT_TO_LEFT))
        assertEquals(ZoomStart.CENTER, ZoomStart.AUTOMATIC.resolve(ReadingMode.VERTICAL))
        assertEquals(ZoomStart.CENTER, ZoomStart.AUTOMATIC.resolve(ReadingMode.WEBTOON))
    }

    @Test
    fun `显式缩放起点不随阅读方向改变`() {
        for (mode in ReadingMode.entries) {
            assertEquals(ZoomStart.LEFT, ZoomStart.LEFT.resolve(mode))
            assertEquals(ZoomStart.RIGHT, ZoomStart.RIGHT.resolve(mode))
            assertEquals(ZoomStart.CENTER, ZoomStart.CENTER.resolve(mode))
        }
    }

    // ------------------------------------------------------------ 设置快照派生

    @Test
    fun `条漫与分页各用自己的一套点按偏好`() {
        val settings = ReaderSettings(
            pagerTapZones = TapZones.EDGE,
            webtoonTapZones = TapZones.KINDLISH,
            pagerTapInvert = TapInvert.HORIZONTAL,
            webtoonTapInvert = TapInvert.VERTICAL,
        )

        assertEquals(TapZones.EDGE, settings.copy(readingMode = ReadingMode.RIGHT_TO_LEFT).tapZones)
        assertEquals(
            TapInvert.HORIZONTAL,
            settings.copy(readingMode = ReadingMode.RIGHT_TO_LEFT).tapInvert,
        )
        assertEquals(TapZones.KINDLISH, settings.copy(readingMode = ReadingMode.WEBTOON).tapZones)
        assertEquals(TapInvert.VERTICAL, settings.copy(readingMode = ReadingMode.WEBTOON).tapInvert)
        // 竖向分页属于 Pager，所以用分页那一套偏好，不是条漫那一套。
        assertEquals(TapZones.EDGE, settings.copy(readingMode = ReadingMode.VERTICAL).tapZones)
        assertEquals(
            TapInvert.HORIZONTAL,
            settings.copy(readingMode = ReadingMode.VERTICAL).tapInvert,
        )
    }

    @Test
    fun `裁白边按是否连续模式选偏好键`() {
        val settings = ReaderSettings(cropBorders = true, cropBordersWebtoon = false)

        assertTrue(settings.copy(readingMode = ReadingMode.RIGHT_TO_LEFT).effectiveCropBorders)
        // 竖向翻页是"分页"，仍然用分页的裁白边偏好。
        assertTrue(settings.copy(readingMode = ReadingMode.VERTICAL).effectiveCropBorders)
        assertFalse(settings.copy(readingMode = ReadingMode.WEBTOON).effectiveCropBorders)
        assertFalse(settings.copy(readingMode = ReadingMode.CONTINUOUS_VERTICAL).effectiveCropBorders)
    }

    @Test
    fun `设置默认值对齐 Mihon`() {
        val settings = ReaderSettings()

        assertEquals(ReadingMode.RIGHT_TO_LEFT, settings.readingMode)
        assertEquals(null, settings.orientation)
        assertEquals(TapZones.DEFAULT, settings.pagerTapZones)
        assertEquals(TapInvert.NONE, settings.pagerTapInvert)
        assertEquals(ImageScaleType.FIT_SCREEN, settings.imageScaleType)
        assertEquals(ZoomStart.AUTOMATIC, settings.zoomStart)
        assertTrue(settings.pageTransitions)
        assertEquals(500, settings.doubleTapAnimMillis)
        assertFalse(settings.volumeKeys)
        assertTrue(settings.longTapActions)
        assertTrue(settings.panWideImages)
        assertTrue(settings.landscapeZoom)
        assertEquals(0, settings.webtoonSidePadding)
        assertTrue(settings.webtoonDoubleTapZoom)
        assertFalse(settings.webtoonDisableZoomOut)
        assertEquals(ReaderTheme.BLACK, settings.theme)
        assertTrue(settings.showPageNumber)
        assertTrue(settings.fullscreen)
        assertFalse(settings.keepScreenOn)
        assertTrue(settings.showChapterTransitions)
        assertEquals(ReaderSettings.PRELOAD_PAGES_DEFAULT, settings.preloadPages)
        assertFalse(settings.grayscale)
        assertFalse(settings.invertedColors)
    }

    private fun hit(
        zones: TapZones,
        invert: TapInvert,
        mode: ReadingMode,
        x: Float,
        y: Float,
    ): TapAction = NavigationRegions.hitTest(zones, invert, mode, x, y)

    private companion object {
        /** 区域边界是 0.33 / 0.66 这类十进制分数的近似值，比较需要容差。 */
        const val TOLERANCE = 0.0001f
    }
}
