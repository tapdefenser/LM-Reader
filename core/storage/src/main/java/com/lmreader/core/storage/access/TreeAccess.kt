package com.lmreader.core.storage.access

import android.content.Context
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.index.TreeFactory
import com.lmreader.core.storage.fs.FileContentTree
import com.lmreader.core.storage.fs.SafPathResolver
import com.lmreader.core.storage.saf.SafContentTree
import com.lmreader.core.storage.saf.SafTreeAccess
import android.net.Uri
import java.io.File

/**
 * 打开内容树的统一入口：能用直接文件访问就用它，否则退回 SAF。
 *
 * 为什么要有这一层选择：两条路径的**目录语义完全相同**（都实现 [ContentTree]），
 * 差别只在性能与系统兼容性：
 * - 有「全部文件访问」时走 [FileContentTree]：每个目录一次 `readdir`，
 *   在真机上还避开了系统 SAF 提供方对子目录查询的不一致行为；
 * - 只有单目录 SAF 授权时走 [SafContentTree]：权限面更小，用户只授权一个目录。
 *
 * 选择发生在**打开授权根**的时刻，之后整棵扫描都用同一个实现，
 * 因此同一批结果不会出现"一半文件路径、一半 documentId"的混合身份。
 */
class TreeAccess(
    private val context: Context,
    private val safAccess: SafTreeAccess,
) {

    /** 是否走直接文件访问。 */
    fun usesDirectFileAccess(): Boolean = StorageAccess.hasAllFilesAccess(context)

    /**
     * 授权根当前是否真的可读；不可读时给出可操作原因。
     *
     * 两条实现路径的判定方式不同，但都必须回答同一个问题：**现在读它会不会失败**。
     * 只查"是否持有授权记录"是不够的——真机上出现过记录存在但读取被拒的情况，
     * 因此这里两条路径都做一次真实探测。
     *
     * 返回 null 表示可读；非 null 是给用户看的原因。
     */
    fun checkReadable(treeUri: String): String? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return "路径格式无法解析"
        if (usesDirectFileAccess()) {
            val directory = SafPathResolver.toDirectory(uri)
            if (directory != null) {
                return when {
                    !directory.exists() -> "目录不存在，可能已被移动或删除"
                    !directory.isDirectory -> "该路径不是目录"
                    !directory.canRead() -> "系统拒绝读取该目录"
                    directory.listFiles() == null -> "系统拒绝列出该目录内容"
                    else -> null
                }
            }
            // 翻译不出真实路径（如 SD 卡卷）时退回 SAF 判定。
        }
        if (!safAccess.hasRecordedPermission(treeUri)) return "目录授权已失效，请重新选择该目录"
        return if (safAccess.checkReadable(uri)) null else "系统拒绝读取该目录，请重新选择该目录"
    }

    /**
     * 授权根的稳定标识。
     *
     * 直接文件访问时是**绝对路径**（[FileContentTree] 的身份约定），
     * SAF 时是 documentId。两者都只用于构造漫画/章节身份，不用于显示。
     */
    fun rootDocumentId(treeUri: String): String {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: return treeUri
        if (usesDirectFileAccess()) {
            SafPathResolver.toDirectory(uri)?.let { return it.absolutePath }
        }
        return safAccess.describe(uri).documentId
    }

    /**
     * 打开授权根。
     *
     * @param displayPath 已保存的展示路径，仅用于日志与回退时的命名。
     * @return 可用内容树；授权失效或路径不可达时返回 null。
     */
    fun open(treeUri: String, displayPath: String): ContentTree? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        if (usesDirectFileAccess()) {
            val directory = SafPathResolver.toDirectory(uri)
            if (directory != null && directory.isDirectory && directory.canRead()) {
                return FileContentTree(directory, displayPath.substringAfterLast('/').ifBlank { directory.name })
            }
            // 翻译不出来（例如 SD 卡卷）就不能假装成功，继续走 SAF。
        }
        return safAccess.openTree(treeUri)
    }

    /** 打开一个已知子目录（补全阶段用；避免从根重新遍历）。 */
    fun openDirectory(documentId: String): ContentTree? {
        if (usesDirectFileAccess() && documentId.startsWith("/")) {
            val directory = File(documentId)
            if (directory.isDirectory && directory.canRead()) return FileContentTree(directory)
            return null
        }
        return null
    }

    /** 补全阶段按 documentId 打开目录；直接文件访问下就是绝对路径。 */
    fun openAt(treeUri: String, documentId: String): ContentTree? =
        openDirectory(documentId) ?: safAccess.openTreeAt(treeUri, documentId)


    /** 按子节点创建树工厂；扫描器只通过它下钻。 */
    fun treeFactory(treeUri: String): TreeFactory {
        if (!usesDirectFileAccess()) {
            return safAccess.treeFactory(treeUri)
        }
        val directoryCache = HashMap<String, FileContentTree>()
        return TreeFactory { child: ChildNode ->
            val file = File(child.documentId)
            // 同一目录在一次扫描里可能被问多次（leafChapter 判定 + 递归下钻），
            // 复用实例顺带利用其内部的一次枚举缓存。
            if (!file.isDirectory || !file.canRead()) {
                null
            } else {
                directoryCache.getOrPut(child.documentId) { FileContentTree(file, child.name) }
            }
        }
    }

    /** 构造可读 URI（封面/阅读用）；直接文件访问下返回 `file://` URI。 */
    fun readableUri(treeUri: String, documentId: String): Uri? {
        if (usesDirectFileAccess() && documentId.startsWith("/")) {
            return Uri.fromFile(File(documentId))
        }
        return safAccess.documentUri(treeUri, documentId)
    }

    /** 展示路径：能拿到真实路径就给真实路径。 */
    fun describePath(treeUri: String, fallback: String): String {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return fallback
        return SafPathResolver.describe(uri) ?: fallback
    }

    fun unwrap(): SafTreeAccess = safAccess
}
