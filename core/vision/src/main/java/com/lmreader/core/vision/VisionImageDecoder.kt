package com.lmreader.core.vision

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.*

/** 只读解码，在 IO 线程调用；包含 EXIF 方向和测试页内存上限。 */
object VisionImageDecoder {
    fun decode(context: Context,uri: Uri,maxPixels: Int = 8_000_000): Bitmap {
        require(maxPixels > 0)
        if (Build.VERSION.SDK_INT < 28) return decodeLegacy(context,uri,maxPixels)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) { decoder,info,_ ->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val (width,height)=visionDecodeSize(info.size.width,info.size.height,maxPixels)
            decoder.setTargetSize(width,height)
        }
    }

    internal fun decodeLegacy(context: Context,uri: Uri,maxPixels: Int = 8_000_000): Bitmap {
        val resolver=context.contentResolver
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>1 && bounds.outHeight>1) { "图片格式或尺寸无效" }
        var sample=1
        while (ceil(bounds.outWidth.toDouble()/sample)*ceil(bounds.outHeight.toDouble()/sample)>maxPixels ||
            max(bounds.outWidth,bounds.outHeight).toDouble()/sample>12000) sample*=2
        val options=BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }
        var result=resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: error("图片解码失败")
        try {
            val exif=try { resolver.openInputStream(uri)?.use { ExifInterface(it) } } catch (_: IOException) { null }
            if (exif!=null && (exif.isFlipped || exif.rotationDegrees!=0)) {
                val matrix=Matrix().apply {
                    if (exif.isFlipped) postScale(-1f,1f)
                    postRotate(exif.rotationDegrees.toFloat())
                }
                val rotated=Bitmap.createBitmap(result,0,0,result.width,result.height,matrix,true)
                if (rotated!==result) { result.recycle(); result=rotated }
            }
            val (w,h)=visionDecodeSize(result.width,result.height,maxPixels)
            if (w!=result.width || h!=result.height) {
                val resized=Bitmap.createScaledBitmap(result,w,h,true)
                if (resized!==result) { result.recycle(); result=resized }
            }
            return result
        } catch (failure: Throwable) { result.recycle(); throw failure }
    }
}

internal fun visionDecodeSize(width: Int,height: Int,maxPixels: Int = 8_000_000): Pair<Int,Int> {
    require(width>1 && height>1) { "图片尺寸无效" }
    require(maxPixels > 0)
    val scale=min(1.0,min(sqrt(maxPixels.toDouble()/(width.toDouble()*height)),12000.0/max(width,height)))
    return max(2,(width*scale).toInt()) to max(2,(height*scale).toInt())
}
