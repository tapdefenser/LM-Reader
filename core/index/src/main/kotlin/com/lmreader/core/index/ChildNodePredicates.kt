package com.lmreader.core.index

import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.MimeTypes
import java.util.Locale

/**
 * 受支持图片判定（开发文档 5.2「首版图片格式：JPEG、PNG、WebP、GIF」）。
 *
 * 先看扩展名再看 MIME：SAF 提供方经常返回 `application/octet-stream`，
 * 而归档内部成员只有相对路径没有 MIME。两套判定都保留，避免任一侧缺失时漏图。
 */
internal fun ChildNode.isSupportedImage(): Boolean {
    if (isDirectory) return false
    val extension = MimeTypes.extensionOf(name)
    if (extension != null && extension in MimeTypes.IMAGE_EXTENSIONS) return true
    val mime = mimeType?.lowercase(Locale.ROOT) ?: return false
    return mime.startsWith(MimeTypes.IMAGE_MIME_PREFIX)
}

/**
 * 归档/PDF 判定（开发文档 5.2）。扩展名不区分大小写；扩展名缺失时用 MIME 兜底，
 * 因为部分提供方对 CBZ 报 `application/x-zip-compressed` 甚至不报扩展名。
 */
internal fun ChildNode.isArchiveFile(): Boolean {
    if (isDirectory) return false
    val extension = MimeTypes.extensionOf(name)
    if (extension != null && extension in MimeTypes.ARCHIVE_EXTENSIONS) return true
    val mime = mimeType?.lowercase(Locale.ROOT) ?: return false
    return mime in MimeTypes.ARCHIVE_MIME_TYPES
}
