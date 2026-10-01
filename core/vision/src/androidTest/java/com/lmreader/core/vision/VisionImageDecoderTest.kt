package com.lmreader.core.vision

import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisionImageDecoderTest {
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun jpeg(orientation: Int): File {
        val image=Bitmap.createBitmap(300,100,Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            drawRect(0f,0f,100f,100f,Paint().apply { color=Color.RED })
            drawRect(200f,0f,300f,100f,Paint().apply { color=Color.BLUE })
        }
        val file=File.createTempFile("vision-exif-",".jpg",context.cacheDir)
        try { file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,100,it) } } finally { image.recycle() }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
        return file
    }
    @Test fun legacyDecoderRotatesAndMirrorsExifWithoutChangingSource() {
        for (orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90,ExifInterface.ORIENTATION_FLIP_HORIZONTAL)) {
            val file=jpeg(orientation); val original=file.readBytes()
            try {
                val result=VisionImageDecoder.decodeLegacy(context,Uri.fromFile(file))
                try {
                    if (orientation==ExifInterface.ORIENTATION_ROTATE_90) {
                        Assert.assertEquals(100,result.width); Assert.assertEquals(300,result.height)
                        Assert.assertTrue(Color.red(result.getPixel(50,20))>240 && Color.blue(result.getPixel(50,20))<20)
                        Assert.assertTrue(Color.blue(result.getPixel(50,280))>240 && Color.red(result.getPixel(50,280))<20)
                    } else {
                        Assert.assertEquals(300,result.width); Assert.assertEquals(100,result.height)
                        Assert.assertTrue(Color.red(result.getPixel(280,50))>240 && Color.blue(result.getPixel(280,50))<20)
                        Assert.assertTrue(Color.blue(result.getPixel(20,50))>240 && Color.red(result.getPixel(20,50))<20)
                    }
                    Assert.assertNotEquals(Bitmap.Config.HARDWARE,result.config)
                } finally { result.recycle() }
                Assert.assertArrayEquals(original,file.readBytes())
            } finally { file.delete() }
        }
    }
    @Test fun platformDecoderUsesExifOrientation() {
        val file=jpeg(ExifInterface.ORIENTATION_ROTATE_90)
        try {
            val result=VisionImageDecoder.decode(context,Uri.fromFile(file))
            try { Assert.assertEquals(100,result.width); Assert.assertEquals(300,result.height) }
            finally { result.recycle() }
        } finally { file.delete() }
    }
    @Test fun analysisPixelBudgetAppliesDuringBothDecodersAndKeepsSource() {
        val image = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File.createTempFile("vision-pixel-budget-", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            image.recycle()
            val original = file.readBytes()
            for (decode in listOf<(android.content.Context, Uri, Int) -> Bitmap>(
                { context, uri, pixels -> VisionImageDecoder.decode(context, uri, pixels) },
                { context, uri, pixels -> VisionImageDecoder.decodeLegacy(context, uri, pixels) })) {
                val result = decode(context, Uri.fromFile(file), 500_000)
                try { Assert.assertTrue(result.width.toLong() * result.height <= 500_000) }
                finally { result.recycle() }
            }
            Assert.assertArrayEquals(original, file.readBytes())
        } finally { if (!image.isRecycled) image.recycle(); file.delete() }
    }
}
