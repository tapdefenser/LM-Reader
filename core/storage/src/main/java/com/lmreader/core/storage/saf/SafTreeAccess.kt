package com.lmreader.core.storage.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.lmreader.core.index.TreeFactory
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree

/**
 * 树 URI 的持久授权与打开（开发文档 4.1）。
 *
 * 授权纪律：
 * - 取持久权限必须在 `onActivityResult` 的同一个 Intent 上立刻完成，否则进程重启后
 *   授权消失，书架的卡片会全部变成"来源不可用"；
 * - 释放授权与"删除来源行"是同一件事的两个阶段：开发文档 4.1 要求"授权在所有引用
 *   释放后再释放"，因此这里按 treeUri 计数，只有计数归零才真正 release。
 */
class SafTreeAccess(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** 扫描调度需要构造树工厂，因此把 resolver 暴露给同模块的存储层。 */
    fun contentResolver(): ContentResolver = context.contentResolver

    /** 保存的授权引用计数：同一棵树被多行引用时不能因为删掉一行就丢授权。 */
    private val references = mutableMapOf<String, Int>()

    /**
     * 取持久读权限。返回 false 表示提供方不支持持久授权，调用方必须提示
     * "重新授权"而不是假装保存成功（开发文档 4.1「禁用/异常」）。
     */
    fun takePersistablePermission(treeUri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (_: SecurityException) {
        false
    }

    /** 登记一个引用；返回登记后的引用数。 */
    fun retain(treeUri: String): Int = references.merge(treeUri, 1, Int::plus) ?: 1

    /** 释放一个引用；只有计数归零才真正释放系统授权。 */
    fun release(treeUri: String) {
        val remaining = (references[treeUri] ?: 1) - 1
        if (remaining > 0) {
            references[treeUri] = remaining
            return
        }
        references.remove(treeUri)
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /**
     * 是否持有这条路径的持久授权**记录**。
     *
     * 只回答"记录在不在"，不回答"现在能不能读"——后者必须用一次真实枚举确认
     * （[com.lmreader.core.storage.access.StorageAccessCoordinator.check]）。
     * 真机上出现过记录存在但查询被拒的情况，两者混为一谈会让界面显示错误的结论。
     */
    fun hasRecordedPermission(treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    /**
     * 系统层面是否真的放行这次读取。
     *
     * 与 [hasPersistedPermission] 的区别很重要：前者只说明"我们持有这条授权记录"，
     * 本方法问的是"现在读这个 URI 会不会被拒绝"。两者不一致时（真机上出现过：
     * dumpsys 显示持久授权存在，但子目录查询仍被 Permission Denial 拒绝），
     * 必须让用户看到明确结论，而不是把失败伪装成"没有漫画"。
     */
    fun checkReadable(uri: Uri): Boolean = context.checkUriPermission(
        uri,
        android.os.Process.myPid(),
        android.os.Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun openTree(treeUri: String): ContentTree? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val documentId = documentIdOf(uri)
        val name = describe(uri).displayPath.substringAfterLast('/').ifBlank { documentId }
        return SafContentTree(resolver, uri, documentId, name)
    }

    /**
     * 打开授权树内的任意 documentId。
     *
     * 补全阶段需要按已知的章节/锚点 documentId 直接定位目录，而不是从根重新遍历：
     * 开发文档 6.1 的补全只针对已发现条目，逐层遍历会把成本变成整树扫描。
     */
    fun openTreeAt(treeUri: String, documentId: String): ContentTree? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        if (documentId.isBlank()) return null
        val name = documentId.substringAfterLast('/').ifBlank { documentId }
        return SafContentTree(resolver, uri, documentId, name)
    }

    /** 扫描器用的树工厂：把一个子节点打开成可读目录（见 SafTreeFactory）。 */
    fun treeFactory(treeUri: String): TreeFactory {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return TreeFactory { null }
        return SafTreeFactory(resolver, uri)
    }

    /** 构造可读 URI（封面/阅读用）。 */
    fun documentUri(treeUri: String, documentId: String): Uri? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        return SafUris.documentUri(uri, documentId)
    }
    /**
     * 可读展示路径（开发文档 4.1「当前路径」）。
     *
     * TODO(P1，开发文档 4.1 已知限制)：Android 的 SAF 只给出 documentId，**不保证**
     * 能解析出文件系统绝对路径。这里先尝试 `DocumentsContract.getTreeDocumentId`
     * 的 `primary:Download/xxx` 形式并把它显示成 `/Download/xxx`，失败时回退为
     * 目录名 + URI 摘要。刻意不调用任何非公开 API 去"猜"真实路径。
     */
    fun describe(treeUri: Uri): SourcePathDescription {
        val documentId = documentIdOf(treeUri)
        val providerLabel = runCatching {
            context.packageManager.resolveContentProvider(treeUri.authority ?: "", 0)
                ?.loadLabel(context.packageManager)
                ?.toString()
        }.getOrNull()

        val readablePath = documentId
            .removePrefix("primary:")
            .takeIf { it != documentId }
            ?.let { "/$it" }

        return SourcePathDescription(
            displayPath = readablePath ?: documentId,
            providerLabel = providerLabel,
            documentId = documentId,
            readable = hasRecordedPermission(treeUri.toString()) ||
                documentId.isNotEmpty(),
        )
    }

    /**
     * 从树 URI 提取 documentId。
     *
     * 树 URI 形如 `content://authority/tree/<URL 编码的 documentId>`；最后一段可能
     * 还带 `/document/<childId>`（选择子文档时），因此两处都要截断。
     */
    private fun documentIdOf(uri: Uri): String {
        val path = uri.pathSegments
        val treeIndex = path.indexOf("tree")
        val raw = when {
            treeIndex >= 0 && treeIndex + 1 < path.size -> path[treeIndex + 1]
            else -> DocumentsContract.getTreeDocumentId(uri)
        }
        return raw // Uri.pathSegments has already decoded the segment; plus signs are literal IDs.
    }
}

/** 路径描述：真的解析不出来时保留目录名与 URI 摘要（开发文档 4.1）。 */
data class SourcePathDescription(
    val displayPath: String,
    val providerLabel: String?,
    val documentId: String,
    val readable: Boolean,
)

/**
 * 扫描器用的树工厂：把一个 [ChildNode] 打开成可读目录。
 *
 * 归档文件不会在这里被打开成目录：本步只把 CBZ/ZIP/PDF 识别为章节并取封面
 * （框架 9.4），归档成员清单是"深入"阶段的事（开发文档 6.1）。因此这里对
 * 非目录子项返回 null，扫描器的 `openChild` 会把 null 当作失败分支处理——
 * 为了避免把"文件本来就不是目录"误报成失败，扫描器只在需要目录时才调用它。
 */
class SafTreeFactory(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : TreeFactory {

    override suspend fun open(child: ChildNode): ContentTree? {
        if (!child.isDirectory) return null
        return SafContentTree(resolver, treeUri, child.documentId, child.name)
    }
}
