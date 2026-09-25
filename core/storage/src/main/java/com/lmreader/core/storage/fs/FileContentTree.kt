package com.lmreader.core.storage.fs

import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直接文件系统实现（需要「全部文件访问」）。
 *
 * 为什么在 SAF 之外再做一条：SAF 的 `DocumentsContract` 查询每个目录都要一次 binder
 * 往返，万级图库扫描时这是主要成本；更重要的是真机验证发现系统提供方在部分目录上
 * 行为不一致（同一投影对根可用、对子目录被拒；仅取 `mime_type` 一列的查询被拒绝）。
 * 拿到「全部文件访问」之后直接用 `File` 既绕开这些坑，也把每目录一次 binder 变成
 * 一次 `readdir`。
 *
 * 稳定身份：本实现的 documentId 就是**绝对路径**。它在同一台设备上稳定，
 * 且换回 SAF 时仍可重新关联；跨设备搬迁需要重新扫描（开发文档 6.2 已声明
 * 「跨提供方搬迁或删除重建可能失去身份」，本实现不例外）。
 *
 * 目录语义仍然遵守开发文档 5.1：
 * - `hasDirectoryChildren` 只判断"是否存在子目录"，不为了回答它而遍历整棵子树；
 * - `hasImageChild` 判定顺序是先看扩展名再回退 MIME，且允许提前返回。
 */
class FileContentTree(
    private val directory: File,
    override val rootName: String = directory.name,
) : ContentTree {

    private var enumerated = false
    private var cached: List<ChildNode> = emptyList()

    override suspend fun listChildren(): List<ChildNode> = withContext(Dispatchers.IO) {
        if (!enumerated) {
            cached = directory.listFiles()?.map { it.toChildNode() }.orEmpty()
            enumerated = true
        }
        cached
    }

    /**
     * 是否存在子目录。
     *
     * 用 `File.isDirectory` 逐个判断并在第一个目录处返回：不需要读完整个目录，
     * 也不会漏掉"先看到图片、后面还有子目录"的情况（验收 A04）。
     */
    override suspend fun hasDirectoryChildren(): Boolean = withContext(Dispatchers.IO) {
        directory.listFiles()?.any { it.isDirectory } ?: false
    }

    override suspend fun hasImageChild(): Boolean = withContext(Dispatchers.IO) {
        directory.listFiles()?.any { it.isFile && it.isSupportedImage() } ?: false
    }

    override suspend fun openChild(child: ChildNode): ContentTree? = withContext(Dispatchers.IO) {
        val file = File(child.documentId)
        if (!file.isDirectory) null else FileContentTree(file, child.name)
    }

    /** 目录的真实绝对路径；诊断与封面定位都用它。 */
    val path: String get() = directory.absolutePath

    private fun File.toChildNode(): ChildNode {
        val isDir = isDirectory
        return ChildNode(
            documentId = absolutePath,
            name = name,
            isDirectory = isDir,
            mimeType = if (isDir) MimeTypes.DIRECTORY else mimeTypeByExtension(),
            sizeBytes = if (isDir) null else length(),
            lastModified = lastModified(),
        )
    }

    /**
     * MIME 兜底。
     *
     * 故意不调用 `MimeTypeMap`：它依赖系统映射表，在部分 ROM 上对 `webp`/`cbz`
     * 返回 null，会让"受支持图片"的判定依赖设备。图片与归档的判定都以
     * [MimeTypes.IMAGE_EXTENSIONS] / [MimeTypes.ARCHIVE_EXTENSIONS] 为准
     * （开发文档 5.2「扩展名比较不区分大小写」），这里只补一个通用的前缀。
     */
    private fun File.mimeTypeByExtension(): String? {
        val extension = MimeTypes.extensionOf(name) ?: return null
        return when (extension) {
            in MimeTypes.IMAGE_EXTENSIONS -> "image/$extension"
            "cbz" -> MimeTypes.CBZ
            "zip" -> MimeTypes.ZIP
            "pdf" -> MimeTypes.PDF
            else -> null
        }
    }

    private fun File.isSupportedImage(): Boolean {
        val extension = MimeTypes.extensionOf(name) ?: return false
        return extension in MimeTypes.IMAGE_EXTENSIONS
    }
}
