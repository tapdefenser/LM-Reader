package com.lmreader.ui.reader.translation

import com.lmreader.core.model.*
import org.junit.Test
import org.junit.Assert.*

class ReaderOverlayGeometryTest {
    private val geometry=ReaderOverlayGeometry(800,1000,PixelRect(100f,200f,700f,800f))
    @Test fun cropOffsetAndSamplingApplyToTextAndHitTestingCoordinates() {
        assertEquals(PixelPoint(0f,0f),geometry.enginePoint(100f,200f,300,300))
        assertEquals(PixelPoint(150f,150f),geometry.enginePoint(400f,500f,300,300))
        assertEquals(PixelPoint(300f,300f),geometry.enginePoint(700f,800f,300,300))
    }
    @Test fun allQuarterTurnsUseEngineRotationWithoutLosingCropOffsets() {
        assertEquals(PixelPoint(120f,50f),geometry.enginePoint(400f,300f,240,300,0))
        assertEquals(PixelPoint(250f,120f),geometry.enginePoint(400f,300f,240,300,90))
        assertEquals(PixelPoint(120f,250f),geometry.enginePoint(400f,300f,240,300,180))
        assertEquals(PixelPoint(50f,120f),geometry.enginePoint(400f,300f,240,300,270))
    }
}
