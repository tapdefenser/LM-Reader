package com.lmreader.core.storage.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 内容树实现（开发文档 15.2 `ContentTree.listChildren/open`）。
 *
 * [treeUri] 与 [documentId] 必须成对出现：documentId 是提供方内部标识，只有与授权
 * 树 URI 组合才构成可访问句柄（开发文档 4.1「树 URI 是授权句柄」）。因此两者都由
 * 构造器注入，不存在"先构造再补 URI"的中间态。
 *
 * 两个性能决定：
 * 1. 用 [DocumentsContract] 直接查询而不是 `DocumentFile`：后者每个属性都重新查询
 *    一次，列一个 5000 项的目录会变成上万次 binder 调用（开发文档 6.1 要求发现
 *    阶段只读元数据）。
 * 2. `hasDirectoryChildren` / `hasImageChild` 提前返回：`leafChapter` 判定只需要
 *    "是否存在"，不需要枚举完（开发文档 5.1）。注意"没有子目录"这一半必须枚举完，
 *    不能因为先看到图片就断言（验收 A04）。
 */
class SafContentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    override val rootName: String,
) : ContentTree {

    /** 一次枚举的缓存；实例由扫描过程创建并丢弃，因此不需要失效机制。 */
    private var enumerated = false
    private var cached: List<ChildNode> = emptyList()

    override suspend fun listChildren(): List<ChildNode> = withContext(Dispatchers.IO) {
        if (!enumerated) {
            cached = queryChildren()
            enumerated = true
        }
        cached
    }

    /**
     * 是否存在子目录 / 是否存在图片。
     *
     * 两者都用**完整的子项列表**回答，不再为"存在性"单独发一条窄投影查询。
     *
     * 真机（小米 14 Pro / HyperOS）验证的教训：只请求 `mime_type` 一列的查询会被
     * 系统 ExternalStorageProvider 拒绝，而请求完整列的同一目录可以正常读取。
     * 既然扫描器对同一个目录本来就要拿完整列表（leafChapter 判定 + 章节构建），
     * 复用一次查询既避开这个坑，也把每个目录的 binder 查询从 2–3 次降到 1 次。
     *
     * 缓存只在**一次扫描**内有效：ContentTree 的实例由扫描过程创建并丢弃
     * （开发文档 6.1 要求发现阶段只读元数据），因此不存在读到过期目录内容的风险。
     */
    override suspend fun hasDirectoryChildren(): Boolean =
        listChildren().any { it.isDirectory }

    override suspend fun hasImageChild(): Boolean =
        listChildren().any { it.isSupportedImage() }

    override suspend fun openChild(child: ChildNode): ContentTree? = withContext(Dispatchers.IO) {
        if (!child.isDirectory) return@withContext null
        // 名称直接沿用 listChildren 的结果，不再回查提供方。
        SafContentTree(resolver, treeUri, child.documentId, child.name)
    }

    /** 构造可读 URI；封面加载与归档打开都需要它。 */
    fun documentUri(childDocumentId: String): Uri =
        SafUris.documentUri(treeUri, childDocumentId)

    /**
     * 一次完整枚举。
     *
     * 异常交给调用方处理：SAF 在权限失效时抛 `SecurityException`，由扫描调度统一
     * 转成"授权失效"状态，而不是在这里吞掉（验收 A07）。
     */
    private fun queryChildren(): List<ChildNode> {
        val childrenUri = SafUris.childDocumentsUri(treeUri, documentId)
        val result = ArrayList<ChildNode>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIndex)
                result += ChildNode(
                    documentId = cursor.getString(idIndex),
                    name = cursor.getString(nameIndex) ?: "",
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    mimeType = mime,
                    sizeBytes = cursor.nullableLong(sizeIndex),
                    lastModified = cursor.nullableLong(modifiedIndex),
                )
            }
        }
        return result
    }

    private fun ChildNode.isSupportedImage(): Boolean {
        val extension = MimeTypes.extensionOf(name)
        if (extension != null && extension in MimeTypes.IMAGE_EXTENSIONS) return true
        // 扩展名缺失时才用 MIME 兜底；两者都不认就不当作图片（开发文档 5.2）。
        return extension == null && mimeType?.startsWith(MimeTypes.IMAGE_MIME_PREFIX) == true
    }

    private fun Cursor.nullableLong(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
