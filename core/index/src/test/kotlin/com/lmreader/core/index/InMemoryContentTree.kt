package com.lmreader.core.index

import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.MimeTypes
import java.io.IOException

/**
 * 内存内容树：把「相对路径清单」展开成目录树，用于离线验证开发文档 5.3 的全部样例。
 *
 * documentId 直接用带前导斜杠的相对路径（根为 `/`）：稳定、可读，断言语义与
 * SAF 的真实 documentId 一样是「提供方给的稳定标识」，不依赖列表位置。
 *
 * 额外提供两个测试钩子：
 * - [calls]：记录结构查询顺序，用于验证「先确认没有子目录，再确认有图片」（验收 A04）；
 * - [failingPaths]：让指定目录查询失败，用于验证单个目录出错不阻断整库（验收 A07）。
 */
class InMemoryTreeFactory(
    rootName: String,
    paths: List<String> = emptyList(),
) : TreeFactory {

    private class Node(
        val path: String,
        val name: String,
        var isDirectory: Boolean,
        var mimeType: String?,
    ) {
        val children = LinkedHashSet<String>()
    }

    val calls = mutableListOf<String>()

    /** 命中该集合的路径在列举/查询时抛 [IOException]。 */
    var failingPaths: Set<String> = emptySet()

    private val nodes = LinkedHashMap<String, Node>()

    init {
        nodes[ROOT_DOCUMENT_ID] = Node(ROOT_DOCUMENT_ID, rootName, isDirectory = true, mimeType = MimeTypes.DIRECTORY)
        paths.forEach(::addPath)
    }

    val root: ContentTree get() = FakeTree(nodes.getValue(ROOT_DOCUMENT_ID))

    override suspend fun open(child: ChildNode): ContentTree? =
        nodes[child.documentId]?.let { FakeTree(it) }

    /** 目录项（以 `/` 结尾表示空目录）；中间层级自动补齐为目录。 */
    private fun addPath(spec: String) {
        val declaredDirectory = spec.endsWith("/")
        val segments = spec.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var parentPath = ROOT_DOCUMENT_ID
        segments.forEachIndexed { index, segment ->
            val path = if (parentPath == ROOT_DOCUMENT_ID) "/$segment" else "$parentPath/$segment"
            val isLeaf = index == segments.lastIndex
            val directory = !isLeaf || declaredDirectory
            val existing = nodes[path]
            if (existing == null) {
                nodes[path] = Node(path, segment, directory, if (directory) MimeTypes.DIRECTORY else mimeOf(segment))
            } else if (directory) {
                // 同一路径先当文件后又作为父目录出现时按目录处理，避免样例数据自相矛盾。
                existing.isDirectory = true
                existing.mimeType = MimeTypes.DIRECTORY
            }
            nodes.getValue(parentPath).children += path
            parentPath = path
        }
    }

    private fun record(call: String) {
        calls += call
    }

    private fun checkReadable(node: Node) {
        if (node.path in failingPaths) throw IOException("模拟列举失败：${node.path}")
    }

    private fun describe(path: String): ChildNode {
        val node = nodes.getValue(path)
        return ChildNode(
            documentId = node.path,
            name = node.name,
            isDirectory = node.isDirectory,
            mimeType = node.mimeType,
        )
    }

    private fun mimeOf(name: String): String = when (MimeTypes.extensionOf(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "cbz" -> MimeTypes.CBZ
        "zip" -> MimeTypes.ZIP
        "pdf" -> MimeTypes.PDF
        "xml" -> "text/xml"
        else -> "application/octet-stream"
    }

    private inner class FakeTree(private val node: Node) : ContentTree {

        override val rootName: String get() = node.name

        override suspend fun listChildren(): List<ChildNode> {
            record("listChildren:${node.path}")
            checkReadable(node)
            return node.children.map(::describe)
        }

        override suspend fun hasDirectoryChildren(): Boolean {
            record("hasDirectoryChildren:${node.path}")
            checkReadable(node)
            return node.children.any { nodes.getValue(it).isDirectory }
        }

        override suspend fun hasImageChild(): Boolean {
            record("hasImageChild:${node.path}")
            checkReadable(node)
            return node.children.any { path ->
                val child = nodes.getValue(path)
                !child.isDirectory && describe(path).isSupportedImage()
            }
        }

        override suspend fun openChild(child: ChildNode): ContentTree? {
            record("openChild:${child.documentId}")
            return nodes[child.documentId]?.let { FakeTree(it) }
        }
    }

    companion object {
        /** 根的 documentId；与真实 SAF 一样由调用方提供，测试里固定为 `/`。 */
        const val ROOT_DOCUMENT_ID = "/"
    }
}
