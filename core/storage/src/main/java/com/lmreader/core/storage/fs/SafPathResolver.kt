package com.lmreader.core.storage.fs

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * SAF 树 URI → 真实文件路径。
 *
 * 为什么需要这一步：拿到「全部文件访问」之后，用 `java.io.File` 直接读比逐个目录走
 * `DocumentsContract` 查询又快又稳（见 [FileContentTree] 的说明）。要复用同一条
 * 快速路径，就必须把已经保存的 SAF 授权句柄翻译回文件路径。
 *
 * 只翻译**有把握**的情况，其余一律返回 null 让调用方退回 SAF：
 * - 只处理 `com.android.externalstorage.documents`（系统外部存储提供方）；
 * - 只处理 `primary:` 卷（内置存储），SD 卡等其它卷的挂载点在不同 ROM 上不固定，
 *   不做猜测（开发文档 4.1 明确「不允许手工输入路径伪造授权」的同一原则：
 *   宁可用慢一点的 SAF，也不猜一个可能指错地方的路径）。
 */
object SafPathResolver {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 把树 URI 翻译成真实目录；无法确定时返回 null。 */
    fun toDirectory(treeUri: Uri): File? = toFile(treeUri.childrenDocumentId() ?: return null)

    /** 把 documentId（`primary:Download/xxx` 形式）翻译成真实文件。 */
    fun toFile(documentId: String): File? {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        val root = volumeRoot(volume) ?: return null
        return if (relative.isEmpty()) root else File(root, relative)
    }

    /**
     * 卷 → 挂载点。
     *
     * `primary` 用 [Environment.getExternalStorageDirectory] 而不是硬编码
     * `/sdcard`：多用户/工作资料场景下前者才是当前用户的正确根。
     */
    private fun volumeRoot(volume: String): File? = when (volume) {
        "primary", "home" -> Environment.getExternalStorageDirectory()
        else -> null
    }

    /** 树 URI 的根 documentId；无法解析时返回 null。 */
    private fun Uri.childrenDocumentId(): String? = runCatching {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        DocumentsContract.getTreeDocumentId(this)
    }.getOrNull()

    /**
     * 实时展示路径（真实文件路径）。
     *
     * 与 SAF 的 `describe` 不同，这里能给出用户认得的绝对路径，因此界面优先用它；
     * 拿不到时回退到 SAF 的 documentId 形式。
     */
    fun describe(treeUri: Uri): String? = toDirectory(treeUri)?.absolutePath
}
