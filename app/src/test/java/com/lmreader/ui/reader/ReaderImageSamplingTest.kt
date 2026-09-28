package com.lmreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码采样决策（真机卡顿的根因所在）。
 *
 * 这些数字全部来自真机实测，改动它们之前先看 [ReaderImageSampling] 的推导：
 * - Gift of the Magi 的页是 **2550×3299**（正好卡在旧阈值的空转区间）；
 * - Dragon Gem 的页是 827×1280（应当原样交流，一个字节都不多搬）；
 * - 真机屏幕 1080×2400 → 解码目标 = min(1080, 2400) * 4 / 3 = 1440。
 */
class ReaderImageSamplingTest {

    private val target = ReaderImageSampling.targetLongEdge(1080, 2400)

    @Test
    fun `解码目标取屏幕短边的四分之三倍`() {
        assertEquals(1440, target)
        // 横屏时短边变成高度，目标不会因此变大——它是"上限"，不是"当前宽度"。
        assertEquals(1440, ReaderImageSampling.targetLongEdge(2400, 1080))
    }

    @Test
    fun `小图不采样`() {
        // 827×1280：原样交给引擎（引擎自己分块解码），不该有任何预解码。
        assertFalse(ReaderImageSampling.needsSampling(1280, target))
        assertTrue(planPageDecode(827, 1280, target).passThrough)
    }

    @Test
    fun `2550x3299 这种刚过大图的页原样交流，不再整图解码再编 PNG`() {
        // 这是真机上卡顿那一页（Gift of the Magi）。
        //
        // 两个方向都要 ≥ 目标才降采样（与 Coil/EhViewer 的 calculateInSampleSize 同规则），
        // 而 2550 的一半是 1275 < 1440，因此**不采样**——这正是 Mihon 分页模式的做法：
        // 原样把流交给引擎，由它在自己的线程上解码。
        //
        // 卡顿的根因从来不是"没有降采样"，而是老实现为了采样在主线程上做了
        // 整图解码 + PNG 编码（秒级），而 off-by-one 又让那次采样等于没做。
        val plan = planPageDecode(2550, 3299, target)
        assertTrue(plan.passThrough)
    }

    @Test
    fun `两侧都超过两倍目标时才降采样`() {
        // 6000×8000：逐级减半到 1500×2000——再减一级（750 宽）就低于目标了，不能减。
        val plan = planPageDecode(6000, 8000, target)
        assertFalse(plan.passThrough)
        assertEquals(4, plan.sampleSize)
        assertTrue(6000 / plan.sampleSize >= 1080)
        assertTrue(8000 / plan.sampleSize >= 1080)
    }

    @Test
    fun `窄屏上 2550x3299 会降采样（模拟器 1600x900 的实测组合）`() {
        // 模拟器是横屏 1600×900 → 目标 = 900 * 4 / 3 = 1200。
        // 此时 2550/2 = 1275 ≥ 1200 成立，因此这一页会被降到 1275×1650 交给引擎，
        // 而不是像宽屏那样原样交流——同一份代码在不同屏幕上给出不同但都合理的决策。
        val emulatorTarget = ReaderImageSampling.targetLongEdge(1600, 900)
        assertEquals(1200, emulatorTarget)
        val plan = planPageDecode(2550, 3299, emulatorTarget)
        assertFalse(plan.passThrough)
        assertEquals(2, plan.sampleSize)
    }

    @Test
    fun `两倍以内的图原样交流`() {
        // 2500 < 2×1440：减半后会掉到目标以下，1× 显示就要插值放大，得不偿失。
        assertFalse(ReaderImageSampling.needsSampling(2500, target))
        assertTrue(planPageDecode(2500, 3300, target).passThrough)
    }

    @Test
    fun `超大跨页逐级降采样`() {
        val plan = planPageDecode(12000, 16000, target)
        assertEquals(8, plan.sampleSize)
        assertTrue(12000 / plan.sampleSize >= target)
        // 不能只按长边算：细长条漫在宽度上必须也满足目标之上的约束。
        val tall = planPageDecode(3000, 30000, target)
        assertTrue(3000 / tall.sampleSize >= target)
        assertTrue(30000 / tall.sampleSize >= target)
    }

    @Test
    fun `尺寸读不到时不采样`() {
        assertTrue(planPageDecode(0, 0, target).passThrough)
        assertTrue(planPageDecode(-1, 500, target).passThrough)
        assertEquals(1, ReaderImageSampling.sampleSizeFor(0, 0, target))
    }

    @Test
    fun `目标为空时不采样`() {
        assertFalse(ReaderImageSampling.needsSampling(10000, 0))
        assertEquals(1, ReaderImageSampling.sampleSizeFor(10000, 10000, 0))
    }

    @Test
    fun `目标为非正数表示加载原图，任何尺寸都原样交流`() {
        // 「加载原图」开关就是通过"目标 = 0"接进来的：一条统一口径，
        // 不需要在解码路径上再加一个布尔参数。
        assertTrue(planPageDecode(2550, 3299, 0).passThrough)
        assertTrue(planPageDecode(12000, 16000, 0).passThrough)
        assertTrue(planPageDecode(827, 1280, -1).passThrough)
    }
}
