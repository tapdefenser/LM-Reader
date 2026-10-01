package com.lmreader.core.vision

import com.lmreader.core.model.*
import org.junit.Test
import kotlin.test.*

class SegTextRegionsTest {
    private fun bubble(id: String, bounds: PixelRect, contour: List<PixelPoint> = emptyList()) = SegRegion(id, RegionKind.BUBBLE, bounds, .9f, contour)
    private fun text(id: String, bounds: PixelRect) = SegRegion(id, RegionKind.FREE_TEXT, bounds, .9f)
    private fun page(vararg regions: SegRegion) = SegResult("p", 500, 500, regions.toList(), 0, "test")

    @Test fun `three scopes classify interior text before filtering`() {
        val b = bubble("bubble", PixelRect(0f, 0f, 150f, 150f))
        val inside = text("inside", PixelRect(30f, 30f, 100f, 70f))
        val outside = text("outside", PixelRect(200f, 40f, 300f, 70f))
        val seg = page(b, inside, outside)
        assertEquals(listOf("bubble"), selectSegRegions(seg, SegTextScope.BUBBLES).map { it.id })
        assertEquals(listOf("outside"), selectSegRegions(seg, SegTextScope.FREE_TEXT).map { it.id })
        assertEquals(2, selectSegRegions(seg).size)
        assertEquals(b.bounds, selectSegRegions(seg).first().bounds)
        assertNull(selectSegRegions(seg).first().extractionBounds)
    }
    @Test fun `one merged balloon retains both interior text blocks as independent targets`() {
        val b = bubble("connected", PixelRect(0f, 0f, 400f, 200f))
        val left = text("left", PixelRect(40f, 40f, 150f, 100f))
        val right = text("right", PixelRect(230f, 70f, 350f, 130f))
        val seg = page(b, left, right)
        val targets = selectSegRegions(seg, SegTextScope.BUBBLES)
        assertEquals(2, targets.size)
        assertTrue(targets.all { it.kind == RegionKind.BUBBLE })
        assertEquals(0f, overlap(targets[0].bounds, targets[1].bounds))
        assertEquals(targets.map { it.id }, selectSegRegions(seg).map { it.id })
        val lines = listOf(OcrLine("l", left.bounds, "FIRST", .9f), OcrLine("r", right.bounds, "SECOND", .9f))
        val grouped = groupPageText(seg, LocalOcrResult("p", 500, 500, LocalOcrLanguage.ENGLISH, lines, 0))
        assertEquals(listOf("FIRST", "SECOND"), grouped.map { it.sourceText })
        assertEquals(targets.map { it.id }, grouped.map { it.id })
    }
    @Test fun `caption in a balloon bounding rectangle but outside its mask stays free`() {
        val b = bubble("triangle", PixelRect(0f, 0f, 200f, 200f), listOf(PixelPoint(0f, 0f), PixelPoint(200f, 0f), PixelPoint(0f, 200f)))
        val outside = text("caption", PixelRect(140f, 140f, 190f, 180f))
        val inside = text("speech", PixelRect(20f, 20f, 100f, 50f))
        assertEquals(listOf("caption"), selectSegRegions(page(b, outside, inside), SegTextScope.FREE_TEXT).map { it.id })
    }
    @Test fun `adjacent overlapping balloons survive deduplication`() {
        val a = bubble("a", PixelRect(0f, 0f, 200f, 200f))
        val b = bubble("b", PixelRect(65f, 0f, 265f, 200f))
        assertTrue(iou(a.bounds, b.bounds) > .45f)
        assertFalse(duplicateSegBounds(a.bounds, b.bounds))
        assertEquals(2, keepSegRegions(listOf(a, b)).size)
        assertEquals(1, keepSegRegions(listOf(a, a.copy(id = "duplicate", bounds = PixelRect(2f, 2f, 202f, 202f)))).size)
    }
    @Test fun `nested merged prediction cannot suppress smaller occupied balloons`() {
        val outer = bubble("outer", PixelRect(0f, 0f, 400f, 250f))
        val a = bubble("a", PixelRect(0f, 0f, 180f, 200f))
        val b = bubble("b", PixelRect(210f, 0f, 400f, 200f))
        val seg = page(outer, a, b, text("ta", PixelRect(30f, 30f, 150f, 80f)), text("tb", PixelRect(250f, 30f, 350f, 80f)))
        assertEquals(3, keepSegRegions(listOf(outer, a, b)).size)
        assertEquals(listOf("a", "b"), selectSegRegions(seg, SegTextScope.BUBBLES).map { it.id })
    }
    @Test fun `free-only grouping does not resurrect speech from excluded balloons`() {
        val b = bubble("b", PixelRect(0f, 0f, 200f, 200f))
        val ocr = LocalOcrResult("p", 500, 500, LocalOcrLanguage.ENGLISH,
            listOf(OcrLine("speech", PixelRect(30f, 30f, 100f, 60f), "SPEECH", .9f)), 0)
        assertTrue(groupPageText(page(b), ocr, SegTextScope.FREE_TEXT).isEmpty())
    }
    @Test fun `clipped child contours cannot erase the neighboring text block`() {
        val points = listOf(PixelPoint(0f, 0f), PixelPoint(400f, 0f), PixelPoint(400f, 200f), PixelPoint(0f, 200f))
        val bounds = PixelRect(30f, 20f, 120f, 100f)
        val result = clipContour(points, bounds)
        assertEquals(bounds, contourBounds(result))
        assertTrue(result.all { it.x.isFinite() && it.y.isFinite() })
    }
}
