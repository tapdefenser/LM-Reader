package com.lmreader.core.model

/**
 * 目录/文件元数据；只读名称与类型，不解码内容（开发文档 5.1）。
 *
 * `sizeBytes`/`lastModified` 可以为 null：SAF 提供方允许不返回未知时间/大小，
 * 未知不等于「未变化」，因此它们只用于决定是否值得重读，不用于判定存在性
 * （开发文档 6.2）。
 */
data class ChildNode(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
)

/**
 * 只读内容树抽象。生产实现是 SAF（DocumentFile/DocumentsContract），
 * 测试实现是内存树。扫描算法只依赖本接口，因此 5.3 全部样例可离线验证。
 *
 * 所有实现必须满足：查询失败返回结构化结果而不是抛异常；
 * 不支持某类查询时安全降级为完整枚举（框架 3.3）。
 */
interface ContentTree {
    /** 根的名称；无法解析真实路径时用目录名（开发文档 4.1）。 */
    val rootName: String

    /** 列举直接子项。顺序不保证，调用方不得依赖枚举顺序。 */
    suspend fun listChildren(): List<ChildNode>

    /** 目录是否直接含至少一个子目录。允许提前停止枚举（开发文档 5.1）。 */
    suspend fun hasDirectoryChildren(): Boolean

    /** 目录是否直接含至少一张受支持图片；可先看名称后停止（开发文档 5.1）。 */
    suspend fun hasImageChild(): Boolean

    /**
     * 打开一个**直接子项**。
     *
     * 参数取 [ChildNode] 而不是裸 documentId：子项的名称已经从
     * [listChildren] 拿到，若只传 ID，实现就必须为了 `rootName` 再查一次提供方，
     * 每个目录多一次 binder 查询（开发文档 6.1 要求发现阶段只读必要元数据）。
     *
     * 返回 null 表示该子项无法作为内容树打开（例如它不是目录）。
     */
    suspend fun openChild(child: ChildNode): ContentTree?
}
