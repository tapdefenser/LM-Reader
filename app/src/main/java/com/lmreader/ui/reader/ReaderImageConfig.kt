package com.lmreader.ui.reader

import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ZoomStart

/** Image properties that Mihon's PagerConfig refreshes rather than mutating on a loaded decoder. */
internal data class ReaderImageConfig(
    val scaleType: ImageScaleType,
    val cropBorders: Boolean,
    val zoomStart: ZoomStart,
    val landscapeZoom: Boolean,
    val disableZoomOut: Boolean,
)

internal fun ReaderSettings.imageConfig(): ReaderImageConfig = ReaderImageConfig(
    scaleType = if (readingMode.continuous) ImageScaleType.FIT_WIDTH else imageScaleType,
    cropBorders = effectiveCropBorders,
    zoomStart = if (readingMode.continuous) ZoomStart.CENTER else zoomStart.resolve(readingMode),
    landscapeZoom = !readingMode.continuous && landscapeZoom,
    disableZoomOut = readingMode.continuous && webtoonDisableZoomOut,
)
