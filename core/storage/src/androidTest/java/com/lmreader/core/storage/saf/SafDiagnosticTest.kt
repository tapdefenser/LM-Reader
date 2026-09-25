package com.lmreader.core.storage.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SAF 排障用的一次性测试（真机运行，`am instrument` 读取结果）。
 *
 * 它存在的理由：真机（小米 14 Pro / HyperOS，Android 15）上扫描只成功枚举了授权根，
 * 打开任何一个子目录都失败，而 MIUI 会吞掉应用自己的 logcat，因此必须在测试输出里
 * 打印原始 URI 与异常，才能区分"授权范围问题"和"URI 构造问题"。
 *
 * 这个测试需要**先由用户授权一棵树**，因此它读取的是应用已保存的持久授权，
 * 不做任何授权动作；没有授权时直接跳过断言并打印提示。
 */
@RunWith(AndroidJUnit4::class)
class SafDiagnosticTest {

    @Test
    fun dumpTreeShape() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val resolver = context.contentResolver

        val persisted = resolver.persistedUriPermissions
        println("DIAG persisted=${persisted.size}")
        persisted.forEach { permission ->
            println(
                "DIAG permission uri=${permission.uri} read=${permission.isReadPermission} " +
                    "write=${permission.isWritePermission}",
            )
        }
        val treeUri = persisted.firstOrNull()?.uri ?: run {
            println("DIAG 没有持久授权，无法继续")
            return
        }

        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        println("DIAG treeUri=$treeUri")
        println("DIAG rootDocumentId=$rootId")

        // 1. 查询授权根本身
        runCatching { queryChildren(context, treeUri, rootId, "ROOT") }
            .onFailure { println("DIAG ROOT 查询失败：${it::class.java.name}: ${it.message}") }

        // 2. 取第一个子目录，按与 SafContentTree 完全相同的方式查询
        val firstChild = queryChildren(context, treeUri, rootId, "ROOT2").firstOrNull { it.second }
        println("DIAG firstChildDocId=${firstChild?.first} name=${firstChild?.third}")
        val childId = firstChild?.first ?: return

        runCatching { queryChildren(context, treeUri, childId, "CHILD") }
            .onFailure { println("DIAG CHILD 查询失败：${it::class.java.name}: ${it.message}") }

        // 3. 直接构造 child-document URI 并读一行元数据
        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
        println("DIAG childDocumentUri=$childUri")
        runCatching {
            resolver.query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                println("DIAG childUri rows=${cursor.count}")
            } ?: println("DIAG childUri cursor=null")
        }.onFailure { println("DIAG childUri 查询失败：${it::class.java.name}: ${it.message}") }

        // 4. checkUriPermission 的显式结果
        val readCheck = context.checkUriPermission(
            treeUri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        println("DIAG checkUriPermission(tree)=$readCheck")

        // 5. 深层目录：子目录的子目录
        val grandChild = queryChildren(context, treeUri, childId, "CHILD2").firstOrNull { it.second }
        if (grandChild != null) {
            runCatching { queryChildren(context, treeUri, grandChild.first, "GRANDCHILD") }
                .onFailure { println("DIAG GRANDCHILD 查询失败：${it::class.java.name}: ${it.message}") }
        }
    }

    private fun queryChildren(
        context: Context,
        treeUri: Uri,
        documentId: String,
        label: String,
    ): List<Triple<String, Boolean, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        println("DIAG $label childrenUri=$uri")
        val rows = mutableListOf<Triple<String, Boolean, String>>()
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: ""
                val mime = cursor.getString(2)
                rows += Triple(
                    id,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )
            }
        }
        println("DIAG $label rows=${rows.size}")
        return rows
    }
}
