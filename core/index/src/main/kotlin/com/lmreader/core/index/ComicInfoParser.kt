package com.lmreader.core.index

import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MetadataRecord
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ComicInfo.xml 读取：容错、保留原文、不因未知字段丢弃信息（开发文档 7）。
 * 解析必须关闭外部实体与网络访问，并限制大小（默认 2 MiB）。
 *
 * 与上游的差异（上游参考与复用边界 2/3）：EhViewer 与 Mihon 的字段集合不同，
 * 这里**同时**接受两侧字段，未知字段一律保留在 [MetadataRecord.fields]；
 * 读取只读源文件，不照搬 Mihon 把 XML 复制回源目录或写 `.noxml` 的逻辑。
 */
object ComicInfoParser {

    const val MAX_BYTES: Long = 2L * 1024 * 1024

    /**
     * 两侧共有的已知字段（框架 4.5）。列出它们只用于填 [MetadataRecord] 的类型化字段；
     * 未列出的字段（EhViewer 的 Characters/Teams/CommunityRating、Mihon 的命名空间
     * 扩展字段）同样进 `fields`，键用元素本地名。
     */
    private val TYPED_FIELDS = setOf(
        "Series", "Title", "Writer", "Penciller", "Summary", "AlternateSeries",
        "Characters", "Teams", "CommunityRating", "Genre", "Web", "PageCount",
        "LanguageISO", "Number", "Volume",
    )

    private const val COMIC_INFO_FILE_NAME = "ComicInfo.xml"
    private const val COMIC_INFO_ELEMENT = "ComicInfo"

    /**
     * 解析 ComicInfo 原文。xml 为原文；解析失败时 fields 为空、parseError 非空，原文仍返回。
     *
     * 解析失败不抛异常：坏 XML 只影响该漫画的简介，不能阻断漫画本身
     * （开发文档 7.1、验收 A11）。
     */
    fun parse(
        ownerId: String,
        ownerType: MetadataOwnerType,
        xml: String,
        sourceLabel: String,
    ): MetadataRecord {
        // 指纹只依赖内容，因此同一文件在不同 owner 下指纹相同，可用于发现
        // 同长度同时间的原地替换（开发文档 6.2）。
        val fingerprint = sha256Hex(xml)
        val updatedAt = System.currentTimeMillis()

        if (utf8LengthExceeds(xml, MAX_BYTES)) {
            return failure(
                ownerId, ownerType, xml, sourceLabel, fingerprint, updatedAt,
                "ComicInfo 超过 ${MAX_BYTES / 1024 / 1024} MiB 上限，未解析；列表不解析巨文档，原文仍可查看（开发文档 7.1）",
            )
        }

        val root = try {
            secureDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
                ?: return failure(
                    ownerId, ownerType, xml, sourceLabel, fingerprint, updatedAt,
                    "ComicInfo 没有根元素（开发文档 7.1：原文保留，解析失败不阻断漫画）",
                )
        } catch (error: Exception) {
            return failure(
                ownerId, ownerType, xml, sourceLabel, fingerprint, updatedAt,
                "ComicInfo 解析失败：${describe(error)}（开发文档 7.1：原文保留，解析失败不阻断漫画）",
            )
        }

        val fields = readFields(root)
        val searchParts = buildList {
            // 「名称 + 别名」放在最前，便于人工核对搜索文本来源（框架 4.5）。
            listOf("Series", "Title", "AlternateSeries").forEach { key -> fieldValue(fields, key)?.let(::add) }
            addAll(fields.values.filter { it.isNotBlank() })
            addAll(textNodes(root))
        }

        val provisional = MetadataRecord(
            ownerId = ownerId,
            ownerType = ownerType,
            xml = xml,
            fields = fields,
            // summary 由 SummaryBuilder 统一计算：Summary 优先，缺失时压缩 XML 文本（框架 4.5）。
            summary = null,
            series = fieldValue(fields, "Series"),
            title = fieldValue(fields, "Title"),
            writer = fieldValue(fields, "Writer"),
            alternateSeries = fieldValue(fields, "AlternateSeries"),
            normalizedSearchText = normalize(searchParts.joinToString(separator = " ")),
            parseError = null,
            sourceLabel = sourceLabel,
            fingerprint = fingerprint,
            updatedAt = updatedAt,
        )
        return provisional.copy(summary = SummaryBuilder.build(provisional))
    }

    /**
     * ComicInfo.xml 判定不区分大小写，但不把任意 XML 都当 ComicInfo。
     *
     * 只接受最后一段等于 `comicinfo.xml` 的名称（归档成员路径同样适用）。
     * PDF 的 sidecar（如 `01.ComicInfo.xml`）需要知道 PDF 基名才能判断，
     * 由补全阶段按开发文档 7.1 第 4 条单独处理，不在这里放宽。
     */
    fun isComicInfoFileName(name: String): Boolean {
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
        return lastSegment.equals(COMIC_INFO_FILE_NAME, ignoreCase = true)
    }

    /** 大小写不敏感地取字段值；空串按缺失处理。 */
    internal fun fieldValue(fields: Map<String, String>, name: String): String? =
        fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 构造关闭 XXE 的 DOM 解析器（框架 4.5）：
     * 禁止 DOCTYPE、关闭外部通用/参数实体与外部 DTD/Schema，并注册空实体解析器兜底。
     *
     * 允许 `setFeature`/`setAttribute` 抛出：宁可让解析失败并保留原文，
     * 也不能在无法确认防护生效时按不安全配置解析用户文件。
     */
    private fun secureDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.isIgnoringComments = true
        // 防止实体展开炸弹（billion laughs）：即使 DOCTYPE 被放行也有总量上限。
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
    }

    /**
     * 读取字段：`ComicInfo` 元素的直接子元素，键为本地名，值为文本。
     *
     * 先在文档中按本地名（不区分大小写）定位 `ComicInfo`：允许 Mihon 本地源那类
     * 包裹结构，但**不把任意 XML 都当 ComicInfo**（开发文档 7）——找不到该元素时
     * 返回空字段，而不是把无关元素的文本当成漫画字段。
     */
    private fun readFields(root: Element): Map<String, String> {
        val container = findComicInfoElement(root) ?: return emptyMap()
        val fields = LinkedHashMap<String, String>()
        for (element in directChildElements(container)) {
            val name = (element.localName ?: element.nodeName)?.trim().orEmpty()
            if (name.isEmpty()) continue
            // 匹配不区分大小写：同名不同大小写只保留首次出现，避免下游出现二义字段。
            if (fields.keys.any { it.equals(name, ignoreCase = true) }) continue
            fields[name] = element.textContent?.trim().orEmpty()
        }
        return fields
    }

    /** 迭代式中序查找第一个本地名为 `ComicInfo` 的元素。 */
    private fun findComicInfoElement(root: Element): Element? {
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val element = stack.removeFirst()
            val name = element.localName ?: element.nodeName
            if (name != null && name.equals(COMIC_INFO_ELEMENT, ignoreCase = true)) return element
            // 广度优先：先匹配外层，再进入子元素，避免命中包裹在深处的无关同名元素。
            for (child in directChildElements(element)) stack.addLast(child)
        }
        return null
    }

    private fun directChildElements(parent: Element): List<Element> {
        val children = parent.childNodes
        val result = ArrayList<Element>(children.length)
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        }
        return result
    }

    /** 迭代式遍历文本节点：递归实现会被超深 XML 打爆栈。 */
    private fun textNodes(root: Element): List<String> {
        val result = ArrayList<String>()
        val stack = ArrayDeque<Node>()
        pushChildren(root, stack)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    node.nodeValue?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add)
                else -> pushChildren(node, stack)
            }
        }
        return result
    }

    private fun pushChildren(node: Node, stack: ArrayDeque<Node>) {
        val children = node.childNodes ?: return
        for (index in 0 until children.length) stack.addLast(children.item(index))
    }

    /** NFC、小写、去多余空白；搜索结果不区分大小写也依赖这里（开发文档 6.4）。 */
    private fun normalize(text: String): String =
        WHITESPACE.replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ")
            .lowercase(Locale.ROOT)
            .trim()

    private fun failure(
        ownerId: String,
        ownerType: MetadataOwnerType,
        xml: String,
        sourceLabel: String,
        fingerprint: String,
        updatedAt: Long,
        message: String,
    ): MetadataRecord = MetadataRecord(
        ownerId = ownerId,
        ownerType = ownerType,
        xml = xml,
        fields = emptyMap(),
        summary = null,
        series = null,
        title = null,
        writer = null,
        alternateSeries = null,
        normalizedSearchText = "",
        parseError = message,
        sourceLabel = sourceLabel,
        fingerprint = fingerprint,
        updatedAt = updatedAt,
    )

    private fun describe(error: Exception): String =
        (error.message?.trim()?.takeIf { it.isNotEmpty() } ?: error::class.simpleName ?: "未知错误")
            .take(240)

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    /**
     * 按 UTF-8 统计字节数（大小上限是字节数，不是字符数），且不复制字符串：
     * 上限检查发生在解析之前，此时可能正是为了拒绝一个几十 MB 的文件。
     */
    private fun utf8LengthExceeds(text: String, limit: Long): Boolean {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val code = text[index].code
            val width = when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                Character.isHighSurrogate(text[index]) &&
                    index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            bytes += width
            if (bytes > limit) return true
            index++
        }
        return false
    }

    private val WHITESPACE = Regex("\\s+")
    private const val HEX = "0123456789abcdef"
}
