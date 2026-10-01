package com.lmreader.core.vision

import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated fixtures only; this test package does not open or modify the reader's library. */
@RunWith(AndroidJUnit4::class)
class SegRegionIsolationTest {
    @Test fun realSegAndOcrRetainBothTextsInConnectedBalloonFixture() = runBlocking {
        val image = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.LTGRAY)
        val balloon = Path().apply { addOval(40f, 50f, 600f, 410f, Path.Direction.CW) }
        val second = Path().apply { addOval(410f, 280f, 970f, 650f, Path.Direction.CW) }
        balloon.op(second, Path.Op.UNION)
        canvas.drawPath(balloon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawPath(balloon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("HELLO FRIEND", 115f, 200f, paint)
        canvas.drawText("SEE YOU", 530f, 440f, paint)
        canvas.drawText("TOMORROW", 490f, 505f, paint)
        val engine = LocalVisionEngine(ApplicationProvider.getApplicationContext()) {
            VisionExecutionSettings(segGpu = false, ocrBackend = OcrBackend.CPU, segConcurrency = 1, ocrConcurrency = 1)
        }
        try {
            val seg = engine.segment("connected-fixture", image)
            val targets = selectSegRegions(seg, SegTextScope.BUBBLES)
            val recognized = targets.map { engine.recognizeRegion(seg.imageId, image, LocalOcrLanguage.ENGLISH, it, seg.regions).translationText }
            Log.i("SegIsolation", "raw=${seg.regions}; targets=${targets.size}; recognized=$recognized")
            assertTrue("No detected balloon targets", targets.isNotEmpty())
            assertTrue("First text missed: $recognized", recognized.any { it.contains("HELLO") && it.contains("FRIEND") })
            assertTrue("Second text missed: $recognized", recognized.any { it.contains("SEE YOU") && it.contains("TOMORROW") })
            assertTrue("Connected texts combined: $recognized", recognized.none { it.contains("HELLO") && it.contains("TOMORROW") })
        } finally { engine.releaseModels(); image.recycle() }
    }
    @Test fun connectedBubbleTargetsHaveSeparatePixelsAndOcr() = runBlocking {
        val image = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        val bounds = PixelRect(20f, 20f, 780f, 480f)
        val contour = listOf(PixelPoint(20f, 20f), PixelPoint(780f, 20f), PixelPoint(780f, 480f), PixelPoint(20f, 480f))
        val bubble = SegRegion("connected", RegionKind.BUBBLE, bounds, .95f, contour)
        val first = SegRegion("first", RegionKind.FREE_TEXT, PixelRect(70f, 100f, 330f, 200f), .95f)
        val second = SegRegion("second", RegionKind.FREE_TEXT, PixelRect(450f, 270f, 730f, 370f), .95f)
        val raw = listOf(bubble, first, second)
        val targets = selectSegRegions(SegResult("p", 800, 500, raw, 0, "test"), SegTextScope.BUBBLES)
        val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 58f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("HELLO", 90f, 175f, paint); canvas.drawText("WORLD", 470f, 345f, paint)
        val engine = LocalVisionEngine(ApplicationProvider.getApplicationContext()) {
            VisionExecutionSettings(segGpu = false, ocrBackend = OcrBackend.CPU, ocrConcurrency = 1)
        }
        try {
            assertEquals(2, targets.size)
            val a = engine.recognizeRegion("p", image, LocalOcrLanguage.ENGLISH, targets[0], raw)
            val b = engine.recognizeRegion("p", image, LocalOcrLanguage.ENGLISH, targets[1], raw)
            assertTrue(a.translationText.contains("HELLO")); assertFalse(a.translationText.contains("WORLD"))
            assertTrue(b.translationText.contains("WORLD")); assertFalse(b.translationText.contains("HELLO"))
            assertEquals(image.width, b.width); assertEquals(image.height, b.height)
            assertTrue(b.lines.all { it.bounds.left > 400f && it.bounds.top > 200f })
        } finally { engine.releaseModels(); image.recycle() }
    }

    @Test fun freeTextCropExcludesBalloonPixelsUsingContour() {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val bubble = SegRegion("b", RegionKind.BUBBLE, PixelRect(0f, 0f, 160f, 160f), .9f,
            listOf(PixelPoint(0f, 0f), PixelPoint(160f, 0f), PixelPoint(0f, 160f)))
        val free = SegRegion("f", RegionKind.FREE_TEXT, PixelRect(60f, 60f, 200f, 200f), .9f)
        try {
            cropSegRegion(image, free, listOf(bubble, free)).use { crop ->
                assertEquals(Color.WHITE, crop.bitmap.getPixel(1, 1))
                assertEquals(Color.BLACK, crop.bitmap.getPixel(90, 90))
            }
            // Selection is free-text-only, but the raw bubble still masks its own pixels.
            assertEquals(listOf("f"), selectSegRegions(SegResult("p", 200, 200, listOf(bubble, free), 0, "test"), SegTextScope.FREE_TEXT).map { it.id })
        } finally { image.recycle() }
    }

    @Test fun bubbleCropWhitesOutCaptionOutsideIrregularContour() {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val bubble = SegRegion("b", RegionKind.BUBBLE, PixelRect(0f, 0f, 200f, 200f), .9f,
            listOf(PixelPoint(0f, 0f), PixelPoint(200f, 0f), PixelPoint(0f, 200f)))
        try {
            cropSegRegion(image, bubble, listOf(bubble)).use { crop ->
                assertEquals(Color.BLACK, crop.bitmap.getPixel(40, 40))
                assertEquals(Color.WHITE, crop.bitmap.getPixel(160, 160))
            }
            assertEquals(Color.BLACK, image.getPixel(160, 160))
        } finally { image.recycle() }
    }
}
