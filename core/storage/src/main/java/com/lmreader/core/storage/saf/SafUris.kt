package com.lmreader.core.storage.saf

import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF document IDs are opaque, single path segments. Encode once: appendPath already
 * escapes its input, so passing Uri.encode(...) to it double-encodes slashes and percent
 * signs. appendEncodedPath accepts the one explicitly encoded segment unchanged.
 */
internal object SafUris {

    /** 子项列表 URI：`…/tree/<treeId>/document/<encoded childId>/children`。 */
    fun childDocumentsUri(treeUri: Uri, documentId: String): Uri =
        documentUri(treeUri, documentId).buildUpon()
            .appendPath("children")
            .build()

    /** 文档 URI：`…/tree/<treeId>/document/<encoded documentId>`。 */
    fun documentUri(treeUri: Uri, documentId: String): Uri =
        treeUri.buildUpon()
            .appendPath("document")
            // 关键：编码成单个路径段。空格会被编成 %20，`/` 会被编成 %2F，
            // 这样 URI 的段数恒定，权限匹配只按段比较。
            .appendEncodedPath(encode(documentId))
            .build()

    /** 授权树的根 documentId；无法解析时返回空串。 */
    fun treeDocumentId(treeUri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")

    /**
     * 编码单个路径段。
     *
     * `Uri.encode(s, allow)` 的第二个参数是**允许保留**的字符集合。默认也会编码 `/`，
     * 因此这里传入空串：把 `/` 也编成 `%2F`，documentId 才会成为单个路径段。
     */
    private fun encode(value: String): String = Uri.encode(value, "")
}
