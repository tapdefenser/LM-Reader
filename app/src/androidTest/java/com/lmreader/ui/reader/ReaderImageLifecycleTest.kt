package com.lmreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.lmreader.MainActivity
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated image only: no library, SAF permissions, or user manga are involved. */
@RunWith(AndroidJUnit4::class)
class ReaderImageLifecycleTest {
    @Test
    fun imageSurvivesFirstCompositionAndSettingsUpdateUntilViewReleased() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val page = ReaderPage("lifecycle-page", 0, "generated.png", "generated.png")
        val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val source = object : PageSource {
            override suspend fun pages() = listOf(page)
            override suspend fun open(page: ReaderPage) = ByteArrayInputStream(bytes)
            override suspend fun probe(page: ReaderPage) = PageGeometry(128, 256)
        }
        val settings = mutableStateOf(ReaderSettings(landscapeZoom = false))
        val shown = mutableStateOf(true)
        val readyCount = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize()) {
                        if (shown.value) EnginePageView(
                            source = source,
                            page = page,
                            settings = settings.value,
                            onSingleTap = { _, _ -> },
                            onReady = { readyCount.incrementAndGet() },
                        )
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (readyCount.get() == 0 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals("Initial recomposition must not recycle the active decoder", 1, readyCount.get())
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            try {
                // Display color conversion can turn #00ff00 into #00fc00 on MuMu.
                val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                assertTrue("Decoded image must actually be green, not blank black",
                    Color.green(pixel) >= 240 && Color.red(pixel) <= 10 && Color.blue(pixel) <= 10)
            } finally {
                screenshot.recycle()
            }
            var engine: SubsamplingScaleImageView? = null
            scenario.onActivity { activity ->
                engine = findEngine(activity.window.decorView)
                assertNotNull(engine)
                assertTrue(engine!!.isReady)
                settings.value = settings.value.copy(theme = ReaderTheme.GRAY)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                assertTrue("Settings must retain the same view", engine === findEngine(activity.window.decorView))
                assertTrue("Settings recomposition must keep the image ready", engine!!.isReady)
                assertEquals(1, readyCount.get())
                shown.value = false
            }
            instrumentation.waitForIdleSync()
            // waitForIdleSync does not wait for the next Compose frame/recomposition.
            var released = false
            val releaseDeadline = SystemClock.uptimeMillis() + 3_000
            while (!released && SystemClock.uptimeMillis() < releaseDeadline) {
                scenario.onActivity { activity ->
                    released = findEngine(activity.window.decorView) == null && !engine!!.isReady
                }
                if (!released) SystemClock.sleep(50)
            }
            scenario.onActivity {
                assertFalse("Actual AndroidView release must recycle its decoder", engine!!.isReady)
                assertTrue("Released image must leave the view hierarchy", released)
                shown.value = true
            }
            val reenterDeadline = SystemClock.uptimeMillis() + 10_000
            while (readyCount.get() < 2 && SystemClock.uptimeMillis() < reenterDeadline) {
                SystemClock.sleep(50)
            }
            assertEquals("Reentering must create a fresh working decoder", 2, readyCount.get())
            scenario.onActivity { activity ->
                val replacement = findEngine(activity.window.decorView)
                assertNotNull(replacement)
                assertTrue(replacement !== engine)
                assertTrue(replacement!!.isReady)
            }
        }
    }

    private fun findEngine(view: View): SubsamplingScaleImageView? {
        if (view is SubsamplingScaleImageView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEngine(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
