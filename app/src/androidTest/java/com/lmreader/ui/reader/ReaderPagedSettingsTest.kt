package com.lmreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.lmreader.MainActivity
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ZoomStart
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented behavior tests using generated images only. Run on MuMu, not the user's phone. */
@RunWith(AndroidJUnit4::class)
class ReaderPagedSettingsTest {
    @Test
    fun allScaleTypesResetCurrentPageAndStartingPositionUpdates() {
        Fixture().use { fixture ->
            // Start large, then switch smaller: merely changing minScale does not reset old zoom.
            val choices = listOf(ImageScaleType.FIT_HEIGHT, ImageScaleType.FIT_SCREEN) + ImageScaleType.entries
            for (choice in choices) {
                fixture.update(fixture.settings.value.copy(imageScaleType = choice))
                fixture.check { engine ->
                    val widthFit = engine.width.toFloat() / engine.sWidth
                    val heightFit = engine.height.toFloat() / engine.sHeight
                    val expected = when (choice) {
                        ImageScaleType.FIT_SCREEN -> minOf(widthFit, heightFit)
                        ImageScaleType.STRETCH -> maxOf(widthFit, heightFit)
                        ImageScaleType.FIT_WIDTH -> widthFit
                        ImageScaleType.FIT_HEIGHT -> heightFit
                        ImageScaleType.ORIGINAL_SIZE -> 1f
                        ImageScaleType.SMART_FIT -> heightFit
                    }
                    assertEquals("Scale must reset for $choice", expected, engine.scale, 0.02f)
                    assertEquals("Max zoom must follow the new base", expected * 5f, engine.maxScale, 0.03f)
                }
            }
            for (position in listOf(ZoomStart.LEFT, ZoomStart.RIGHT, ZoomStart.CENTER)) {
                fixture.update(fixture.settings.value.copy(
                    imageScaleType = ImageScaleType.FIT_HEIGHT, zoomStart = position,
                ))
                fixture.check { engine ->
                    val center = engine.center!!
                    val imageCenter = engine.sWidth / 2f
                    when (position) {
                        ZoomStart.LEFT -> assertTrue("Left start must reveal the left side", center.x < imageCenter)
                        ZoomStart.RIGHT -> assertTrue("Right start must reveal the right side", center.x > imageCenter)
                        else -> assertEquals(imageCenter, center.x, 1f)
                    }
                }
            }
        }
    }

    @Test
    fun cropToggleRedecodesCurrentImageAndCanRestoreBorders() {
        Fixture(borders = true).use { fixture ->
            fixture.check { engine ->
                assertEquals(1200, engine.sWidth)
                assertEquals(400, engine.sHeight)
            }
            fixture.update(fixture.settings.value.copy(cropBorders = true))
            fixture.check { engine ->
                assertTrue("Crop must actually change decoded width", engine.sWidth in 1000..1100)
                assertTrue("Crop must actually change decoded height", engine.sHeight in 200..300)
            }
            fixture.update(fixture.settings.value.copy(cropBorders = false))
            fixture.check { engine ->
                assertEquals("Disabling crop must restore the original image", 1200, engine.sWidth)
                assertEquals(400, engine.sHeight)
            }
        }
    }

    @Test
    fun landscapeZoomStartsOnSelectionAndDisablingItRestoresFitScreen() {
        Fixture(selectedInitially = false).use { fixture ->
            fixture.update(fixture.settings.value.copy(landscapeZoom = true, zoomStart = ZoomStart.RIGHT))
            SystemClock.sleep(1200)
            fixture.check { engine -> assertEquals(engine.minScale, engine.scale, 0.02f) }
            fixture.scenario.onActivity { fixture.selected.value = true }
            SystemClock.sleep(1500)
            fixture.check { engine ->
                assertEquals(engine.height.toFloat() / engine.sHeight, engine.scale, 0.02f)
                assertTrue(engine.center!!.x > engine.sWidth / 2f)
            }
            fixture.update(fixture.settings.value.copy(landscapeZoom = false))
            fixture.check { engine -> assertEquals(engine.minScale, engine.scale, 0.02f) }
            // Landscape auto zoom only applies to FIT_SCREEN, just like Mihon.
            fixture.update(fixture.settings.value.copy(
                landscapeZoom = true, imageScaleType = ImageScaleType.FIT_WIDTH,
            ))
            SystemClock.sleep(1200)
            fixture.check { engine -> assertEquals(engine.width.toFloat() / engine.sWidth, engine.scale, 0.02f) }
        }
    }

    private class Fixture(borders: Boolean = false, selectedInitially: Boolean = true) : AutoCloseable {
        val settings = mutableStateOf(ReaderSettings(landscapeZoom = false))
        val selected = mutableStateOf(selectedInitially)
        val scenario: ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)
        private val readyCount = AtomicInteger()

        init {
            val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (borders) Color.WHITE else Color.BLUE)
            if (borders) Canvas(bitmap).drawRect(80f, 80f, 1120f, 320f, Paint().apply { color = Color.BLUE })
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            val page = ReaderPage("settings-page", 0, "generated.png", "generated.png")
            val source = object : PageSource {
                override suspend fun pages() = listOf(page)
                override suspend fun open(page: ReaderPage) = ByteArrayInputStream(bytes)
                override suspend fun probe(page: ReaderPage) = PageGeometry(1200, 400)
            }
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize()) {
                        EnginePageView(source, page, settings.value,
                            onSingleTap = { _, _ -> }, isSelected = selected.value,
                            onReady = { readyCount.incrementAndGet() })
                    }
                }
            }
            awaitReady(1)
        }

        fun update(next: ReaderSettings) {
            val changesImage = next.imageConfig() != settings.value.imageConfig()
            val expected = readyCount.get() + if (changesImage) 1 else 0
            scenario.onActivity { settings.value = next }
            awaitReady(expected)
        }

        private fun awaitReady(expected: Int) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (readyCount.get() < expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals("Image configuration must reload and become ready", expected, readyCount.get())
            SystemClock.sleep(150) // Apply pending initial scale/center on the next draw.
        }

        fun check(assertion: (SubsamplingScaleImageView) -> Unit) {
            scenario.onActivity { activity ->
                val engine = findEngine(activity.window.decorView) ?: error("Image view missing")
                assertTrue(engine.isReady)
                assertion(engine)
            }
        }

        override fun close() = scenario.close()
    }
}

private fun findEngine(view: View): SubsamplingScaleImageView? {
    if (view is SubsamplingScaleImageView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        findEngine(view.getChildAt(index))?.let { return it }
    }
    return null
}
