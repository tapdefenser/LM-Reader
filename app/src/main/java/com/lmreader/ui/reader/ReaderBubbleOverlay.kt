package com.lmreader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.os.Build
import com.davemorrissey.labs.subscaleview.CropBorders
import com.davemorrissey.labs.subscaleview.ImageSource
import com.lmreader.core.model.PixelRect
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.core.vision.BubbleOverlaySource
import com.lmreader.ui.reader.translation.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class PreparedOverlayPage(val bitmap: Bitmap, val overlay: BubbleOverlaySource, val geometry: ReaderOverlayGeometry) {
    val imageSource get() = ImageSource.bitmap(bitmap)
}

private val overlayDecodeSlots = Semaphore(1)

/** Updates geometry/text on an already loaded uncropped page without decoding a display bitmap. */
internal suspend fun prepareOverlaySource(context: Context, source: PageSource, page: ReaderPage,
    saved: ReaderPageTranslation): BubbleOverlaySource = overlayDecodeSlots.withPermit {
    val temporary = File.createTempFile("reader-overlay-source-", ".image", context.cacheDir)
    try {
        source.open(page).use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
        require(ReaderPageArtifactStore.hashFile(temporary) == saved.sourceSha256) { "Original page changed" }
        val analysis = decodeOverlayAnalysis(context, temporary, saved)
        try {
            BubbleMaskRenderer().prepareSource(analysis, saved.regions.map { it.region })
        } finally { analysis.recycle() }
    } finally { temporary.delete() }
}

/** Decodes only the original. Paths and glyphs are drawn later, never baked into this bitmap. */
internal suspend fun prepareOverlayPage(context: Context, source: PageSource, page: ReaderPage,
    saved: ReaderPageTranslation, targetLongEdge: Int, cropBorders: Boolean): PreparedOverlayPage = overlayDecodeSlots.withPermit {
    val temporary = File.createTempFile("reader-overlay-source-", ".image", context.cacheDir)
    var original: Bitmap? = null
    var visible: Bitmap? = null
    try {
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.open(page).use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= 64_000_000)
                hash.update(buffer, 0, count); output.write(buffer, 0, count)
            }
        } }
        require(hash.digest().joinToString("") { "%02x".format(it) } == saved.sourceSha256) { "Original page changed" }
        val analysis = decodeOverlayAnalysis(context, temporary, saved)
        val overlay = try {
            BubbleMaskRenderer().prepareSource(analysis, saved.regions.map { it.region })
        } finally { analysis.recycle() }
        // Display sampling is independent of the smaller analysis coordinate space.
        original = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(temporary)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val plan = planPageDecode(info.size.width, info.size.height, targetLongEdge)
            if (!plan.passThrough) decoder.setTargetSize((info.size.width / plan.sampleSize).coerceAtLeast(1),
                (info.size.height / plan.sampleSize).coerceAtLeast(1))
        } else decodePageAnalysisImage(context, temporary)
        val crop = if (cropBorders) {
            // Use the very same native crop detector as the reader engine, with known offsets.
            val rgba = if (original.config == Bitmap.Config.ARGB_8888) original else original.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val buffer = ByteBuffer.allocate(rgba.byteCount)
                rgba.copyPixelsToBuffer(buffer)
                CropBorders.findCropBorders(buffer.array(), rgba.width, rgba.height).takeIf { values ->
                    values.size == 4 && values[0] >= 0 && values[1] >= 0 && values[2] > 0 && values[3] > 0 &&
                        values[0] + values[2] <= rgba.width && values[1] + values[3] <= rgba.height
                } ?: intArrayOf(0, 0, rgba.width, rgba.height)
            } finally { if (rgba !== original) rgba.recycle() }
        } else intArrayOf(0, 0, original.width, original.height)
        visible = Bitmap.createBitmap(original, crop[0], crop[1], crop[2], crop[3])
        currentCoroutineContext().ensureActive()
        val scaleX = saved.width.toFloat() / original.width
        val scaleY = saved.height.toFloat() / original.height
        PreparedOverlayPage(visible, overlay, ReaderOverlayGeometry(saved.width, saved.height,
            PixelRect(crop[0] * scaleX, crop[1] * scaleY, (crop[0] + crop[2]) * scaleX, (crop[1] + crop[3]) * scaleY)))
            .also { if (original !== visible) original.recycle(); original = null; visible = null }
    } finally {
        if (visible !== original) visible?.recycle()
        original?.recycle(); temporary.delete()
    }
}

/** Earlier decoder rounding can differ by a pixel; persisted geometry stays authoritative. */
private fun decodeOverlayAnalysis(context: Context, file: File, saved: ReaderPageTranslation): Bitmap {
    val image = decodePageAnalysisImage(context, file)
    if (image.width == saved.width && image.height == saved.height) return image
    return try {
        require(saved.width > 1 && saved.height > 1 && saved.width.toLong() * saved.height <= 4_000_000) {
            "Invalid saved analysis dimensions"
        }
        Bitmap.createScaledBitmap(image, saved.width, saved.height, true)
    }
    finally { image.recycle() }
}
