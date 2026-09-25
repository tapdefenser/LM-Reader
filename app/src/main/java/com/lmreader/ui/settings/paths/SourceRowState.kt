package com.lmreader.ui.settings.paths

import android.net.Uri
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import java.net.URLDecoder

/**
 * 一张路径表的一行。
 *
 * 为什么没有"草稿/保存"这一层（开发文档 4.1 的修订）：用户要求**选择目录后立即
 * 加入列表**、**改动立即生效**，不再需要每行二次确认。因此每一行都直接对应一条
 * 已保存的 [LibrarySource]；界面上的复选框与类型开关写入即持久化。
 *
 * [source] 为 null 只出现在一种瞬间状态：新增行正在写库（乐观占位），
 * 写完立刻被数据库发射的真实行替换。
 */
data class SourceRow(
    val source: LibrarySource?,
    /** 乐观占位用的稳定 key（写库前也能让 Compose 保持列表稳定）。 */
    val key: String,
) {
    val displayPath: String get() = source?.displayPath.orEmpty()

    /** 界面优先显示用户起的名字，没有才回退到系统路径。 */
    val title: String get() = source?.displayName?.takeIf { it.isNotBlank() } ?: displayPath

    val recursive: Boolean get() = source?.recursive ?: true
    val mode: LayoutMode get() = source?.mode ?: LayoutMode.MULTI_CHAPTER
    val permission: SourcePermissionState get() = source?.permission ?: SourcePermissionState.CHECKING
}

/** 一张表的完整状态。 */
data class SourceTableState(
    val kind: SourceKind,
    /** 表头四列；语义是路径/子目录/类型/删除，展示顺序按可读性排列。 */
    val headers: List<String> = HEADERS,
    val title: String,
    val rows: List<SourceRow>,
    val emptyHint: String,
) {
    val savedCount: Int get() = rows.count { it.source != null }

    companion object {
        val HEADERS = listOf("路径", "子目录", "类型", "删除")

        fun forKind(kind: SourceKind, rows: List<SourceRow>): SourceTableState = when (kind) {
            SourceKind.IMAGE_DIRECTORY -> SourceTableState(
                kind = kind,
                title = "图库路径列表",
                rows = rows,
                emptyHint = "还没有图库路径。点击 + 选择存放漫画图片的文件夹，选完立即加入列表。",
            )

            SourceKind.ARCHIVE_IMPORT -> SourceTableState(
                kind = kind,
                title = "CBZ / ZIP / PDF 导入列表",
                rows = rows,
                emptyHint = "还没有导入路径。点击 + 选择存放 CBZ、ZIP 或 PDF 的文件夹，选完立即加入列表。",
            )
        }
    }
}

/**
 * 从树 URI 提取 documentId。
 *
 * Android 的树 URI 形如 `content://authority/tree/<encoded documentId>`；这里只做
 * URI 结构解析，不访问提供方。真正的可读性检查在用户通过系统选择器选定目录时完成
 * （开发文档 4.1「不允许手工输入路径伪造授权」）。
 */
internal fun documentIdOf(treeUri: String): String {
    val encoded = treeUri.substringAfterLast("/tree/", missingDelimiterValue = treeUri)
        .substringBefore("/document/")
        .substringBefore('?')
    return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
}

/** 路径行编辑弹窗的初始值。 */
data class SourceEditDialogState(
    val kind: SourceKind,
    val sourceId: String,
    /** 系统提供的路径，只读展示（授权句柄，不允许伪造）。 */
    val systemPath: String,
    val providerLabel: String?,
    /** 用户可编辑的名称；确定后写入 `displayName`。 */
    val nameInput: String,
    /** 用户正在重新选择目录时暂存的新树 URI。 */
    val pendingTreeUri: String? = null,
    val pendingDisplayPath: String? = null,
    val error: String? = null,
) {
    val effectiveSystemPath: String get() = pendingDisplayPath ?: systemPath

    /** 名称允许为空（回退到系统路径），但不允许超长或含换行。 */
    val nameValid: Boolean
        get() = nameInput.length <= MAX_NAME_LENGTH && !nameInput.contains('\n')

    companion object {
        const val MAX_NAME_LENGTH = 60

        fun from(source: LibrarySource, kind: SourceKind): SourceEditDialogState =
            SourceEditDialogState(
                kind = kind,
                sourceId = source.sourceId,
                systemPath = source.displayPath,
                providerLabel = source.providerLabel,
                nameInput = source.displayName.orEmpty(),
            )
    }
}

/** 待确认删除的行（危险操作保留确认，避免误触删除整条索引来源）。 */
data class PendingDelete(
    val kind: SourceKind,
    val source: LibrarySource,
)

/** 系统目录选择器的结果，交给 ViewModel 写入。 */
data class PickedDirectory(
    val treeUri: Uri,
    val documentId: String,
    val displayPath: String,
    val providerLabel: String?,
)
