package com.lmreader.core.index

import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.MimeTypes
import java.util.Locale

/**
 * 受支持图片判定（开发文档 5.2「首版图片格式：JPEG、PNG、WebP、GIF」）。
 *
 * 先看扩展名再看 MIME：SAF 提供方经常返回 `application/octet-stream`，
 * 而归档内部成员只有相对路径没有 MIME。两套判定都保留，避免任一侧缺失时漏图。
 *
 * 必须是 public：封面懒加载（`core:storage` 的 `CoverResolver`）与扫描器要给出
 * **同一个**判定，否则"扫描说这里有图、取封面说没有"这类分叉会以"某些卡片永远
 * 没有封面"的形式出现，而且没有任何报错。
 */
fun ChildNode.isSupportedImage(): Boolean {
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

/**
 * 目录内**自然序**第一张图片（开发文档 1.3「首字母排序」、7.2「封面取首章首图」）。
 *
 * 为什么必须是自然序而不是"枚举碰巧返回的第一项"：文件系统的目录顺序不保证任何事，
 * 同一个目录在不同设备上可能给出不同结果，封面就会随机变化（验收 A12）。
 *
 * 判定与扫描器共用 [isSupportedImage]；排序与章节/页面列表共用 [NaturalOrder]，
 * 因此"第 1 页是哪张"在扫描、封面与阅读器三处是同一个答案。
 */
fun firstImageInNaturalOrder(children: List<ChildNode>): ChildNode? =
    children
        .asSequence()
        .filter { it.isSupportedImage() }
        .minWithOrNull { left, right ->
            NaturalOrder.compare(left.name, right.name)
                .takeIf { it != 0 }
                ?: left.documentId.compareTo(right.documentId)
        }
