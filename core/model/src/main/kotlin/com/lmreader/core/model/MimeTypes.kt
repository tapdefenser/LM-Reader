package com.lmreader.core.model

import java.util.Locale

/**
 * MIME 常量集中在此，避免各处硬编码字符串。
 *
 * SAF 提供方对归档/PDF 返回的 MIME 并不统一（例如小米/部分网盘把 CBZ 报成
 * `application/x-zip-compressed`），因此扩展名与 MIME 两套判定都要留在这里，
 * 由扫描器按「扩展名优先、MIME 兜底」使用（开发文档 5.2「扩展名比较不区分大小写」）。
 */
object MimeTypes {
    const val DIRECTORY = "vnd.android.document/directory"
    const val CBZ = "application/vnd.comicbook+zip"
    const val ZIP = "application/zip"
    const val X_ZIP = "application/x-zip-compressed"
    const val PDF = "application/pdf"
    val IMAGE_MIME_PREFIX = "image/"

    /** 首版支持解码的图片格式（开发文档 5.2）。AVIF 等按设备解码能力另开，不在此列。 */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    /** 归档/PDF 扩展名：本步只识别为章节并取封面，不做页清单与阅读（开发文档 5.2）。 */
    val ARCHIVE_EXTENSIONS = setOf("cbz", "zip", "pdf")

    /** 归档/PDF 可能出现的全部 MIME，用于扩展名缺失时的兜底判定。 */
    val ARCHIVE_MIME_TYPES = setOf(CBZ, ZIP, X_ZIP, PDF)

    /**
     * 取小写扩展名；无扩展名或以点开头（如 `.hidden`）时返回 null。
     *
     * 名称里可能带归档内部相对路径（`包裹目录/001.jpg`），这里只取最后一段，
     * 因此对目录子项与归档成员都可用（开发文档 5.2「图片按归一化相对路径自然排序」）。
     */
    fun extensionOf(name: String): String? {
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0 || dot == lastSegment.length - 1) return null
        return lastSegment.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** 去掉扩展名的名称：归档单章节的章节标题就是它（开发文档 1.3「归档单章节」）。 */
    fun nameWithoutExtension(name: String): String {
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = lastSegment.lastIndexOf('.')
        return if (dot <= 0) lastSegment else lastSegment.substring(0, dot)
    }
}
