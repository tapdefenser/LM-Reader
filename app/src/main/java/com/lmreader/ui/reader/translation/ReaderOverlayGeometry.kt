package com.lmreader.ui.reader.translation

import com.lmreader.core.model.PixelPoint
import com.lmreader.core.model.PixelRect

/** Maps the stored, full analysis page into the image engine's cropped and sampled source. */
data class ReaderOverlayGeometry(val width: Int, val height: Int, val crop: PixelRect) {
    fun enginePoint(x: Float, y: Float, engineWidth: Int, engineHeight: Int, rotation: Int = 0): PixelPoint {
        val px = (x - crop.left) * engineWidth / crop.width
        val py = (y - crop.top) * engineHeight / crop.height
        return when (rotation) {
            0 -> PixelPoint(px, py)
            90 -> PixelPoint(engineHeight - py, px)
            180 -> PixelPoint(engineWidth - px, engineHeight - py)
            270 -> PixelPoint(py, engineWidth - px)
            else -> error("Unsupported image rotation")
        }
    }
}
