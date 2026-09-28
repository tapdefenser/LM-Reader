package com.lmreader.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面加载（开发文档 7.2、8.1）。
 *
 * 为什么本步不用图片加载库（框架第 9 节已知限制）：封面来自 SAF 的
 * `content://` URI，需要"按来源树 URI + documentId 组合"才能打开；把这条规则
 * 交给通用加载器的自定义 Fetcher，会在第一步引入一个我们无法验证的缓存层。
 * 这里直接按卡片可见性解码，并在内存里做一次有界缓存：
 * - 采样到卡片尺寸（`inSampleSize`），绝不解码整张原图——万级图库滚一遍会 OOM；
 * - 缓存按 LRU 且有上限，缩略图磁盘缓存与 LRU 调优是 P1 项（开发文档 6.5）。
 */
@Composable
fun CoverImage(
    request: CoverRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val painter = rememberCoverPainter(request)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = painter
        if (current != null) {
            Image(
                painter = current,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            // 封面缺失/解码失败用占位图，卡片不等封面完成（开发文档 6.1）。
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(0.3f),
            )
        }
    }
}

/** 封面定位信息；两者缺一不可，因为 SAF 文档 URI 必须与授权树组合（开发文档 4.1）。 */
data class CoverRequest(
    val treeUri: String,
    val documentId: String,
)

@Composable
private fun rememberCoverPainter(request: CoverRequest?): Painter? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var painter by remember(request?.treeUri, request?.documentId) {
        mutableStateOf<Painter?>(null)
    }

    LaunchedEffect(request?.treeUri, request?.documentId) {
        val target = request ?: return@LaunchedEffect
        painter = withContext(Dispatchers.IO) {
            CoverCache.get(target)?.let { return@withContext it }
            val decoded = loadCover(context, target) ?: return@withContext null
            CoverCache.put(target, decoded)
            decoded.painter
        }
    }
    return painter
}

/**
 * 解码一张封面。
 *
 * 两条路径都做采样：API 28+ 走 [ImageDecoder]（能正确应用 EXIF 方向），
 * 低版本走 `BitmapFactory` 的 `inSampleSize`。直接解码一张 5000×7000 的图
 * 会一次吃掉上百 MB 内存，滚动万级图库必然 OOM。
 */
private fun loadCover(context: Context, request: CoverRequest): DecodedCover? {
    val uri = if (request.documentId.startsWith("/")) {
        Uri.fromFile(File(request.documentId))
    } else {
        runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                Uri.parse(request.treeUri),
                request.documentId,
            )
        }.getOrNull() ?: return null
    }

    val resolver = context.contentResolver
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sample = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }.getOrDefault(1)
            val drawable = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(resolver, uri),
            ) { decoder, _ /* info */, _ /* source */ ->
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            (drawable as? android.graphics.drawable.BitmapDrawable)
                ?.let { DecodedCover(BitmapPainter(it.bitmap.asImageBitmap()), it.bitmap.byteCount) }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?.let { DecodedCover(BitmapPainter(it.asImageBitmap()), it.byteCount) }
        }
    }.getOrNull()
}

/** 一张已解码的封面；[bytes] 是位图在堆上的实际占用，缓存按它计量。 */
private data class DecodedCover(
    val painter: Painter,
    val bytes: Int,
)

/** 采样率取 2 的幂：`inSampleSize` 非 2 的幂时部分解码器会向上取整，反而浪费内存。 */
private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= TARGET_COVER_PX && height / (sample * 2) >= TARGET_COVER_PX) {
        sample *= 2
    }
    return sample
}

/**
 * 按**字节**计量的 LRU 封面缓存。
 *
 * ## 为什么必须按字节而不是按条数
 *
 * 上一版的上限是"120 条"，而每条持有一张位图：320 宽的封面按比例约 320×460，
 * ARGB_8888 下每条约 590KB——120 条就是约 70MB，接近真机 256MB 堆的三分之一。
 * 封面从"扫描期慢慢补"改成"图库滚动时成批取"之后，缓存会更快被填满，
 * 按条数计量的上限就不再能表达"最多占多少内存"。
 *
 * 上限取 24MB：一屏可见封面通常十几张（约 8MB），加上滚动预取的两三屏，
 * 够用且不至于挤占阅读器的页图内存。
 */
private object CoverCache {
    private const val MAX_BYTES = 24 * 1024 * 1024

    private var bytes = 0

    private val entries = object : LinkedHashMap<CoverRequest, DecodedCover>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CoverRequest, DecodedCover>?): Boolean {
            if (bytes <= MAX_BYTES) return false
            // 逐出最久未用的一条，直到回到预算内。用 while 而不是 if 是因为
            // 一条封面本身可能就比"超出量"大得多（例如一张超大跨页当封面）。
            bytes -= eldest?.value?.bytes ?: 0
            return true
        }
    }

    @Synchronized
    fun get(key: CoverRequest): Painter? = entries[key]?.painter

    @Synchronized
    fun put(key: CoverRequest, value: DecodedCover) {
        val previous = entries.put(key, value)
        bytes += value.bytes - (previous?.bytes ?: 0)
        // LinkedHashMap 的逐出只在 put **之后**由 removeEldestEntry 判定，
        // 而它一次只判一条；这里补一轮循环，保证单张超大图也不会把预算撑破。
        while (bytes > MAX_BYTES && entries.isNotEmpty()) {
            val eldest = entries.entries.first()
            bytes -= eldest.value.bytes
            entries.remove(eldest.key)
        }
    }
}

private const val TARGET_COVER_PX = 320
