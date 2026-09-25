package com.lmreader.core.storage.saf

import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF URI 构造。
 *
 * 为什么不让 `DocumentsContract.buildChildDocumentsUriUsingTree` 直接用：
 * 它把 `documentId` 交给 `Uri.Builder.appendPath`，**不对 `/` 与空格做百分号编码**。
 * 目录层级用 `/` 分隔，而 SAF 的 documentId 本身也包含 `/`
 * （`primary:Tachiyomi/downloads/Comic Days (JA)`），于是拼出来的权限路径段数
 * 与授权树不一致，`ExternalStorageProvider` 会认为这次读取不在授权范围内——
 * 现象就是"根目录能列出来，任何一个子目录都打不开"。
 *
 * 真机（小米 14 Pro / HyperOS，Android 15）上正是这个现象：授权根返回 65 个子项，
 * 之后 65 个子目录全部失败、0 部漫画。修复办法是自己编码 documentId，
 * 让它成为 URI 路径里的**单个段**。
 *
 * 需求（开发文档 4.1）：树 URI 是授权句柄，任何读写都必须与授权树组合，
 * 不能手工拼接字符串路径。
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
            .appendPath(encode(documentId))
            .build()

    /** 授权树的根 documentId；无法解析时返回空串。 */
    fun treeDocumentId(treeUri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")

    /**
     * 编码单个路径段。
     *
     * `Uri.encode(s, allow)` 的第二个参数是**允许保留**的字符集合。默认允许 `/`，
     * 因此这里传入空串：把 `/` 也编成 `%2F`，documentId 才会成为单个路径段。
     */
    private fun encode(value: String): String = Uri.encode(value, "")
}
