package com.lmreader.core.index

import com.lmreader.core.model.MetadataRecord
import java.text.Normalizer

/**
 * 简介摘要：Summary 优先，缺失时按上游字段组织，最后才压缩 XML 文本；
 * 最多 [MAX_SUMMARY_LENGTH] 字符（框架 4.5）。
 *
 * 卡片只显示两行预览（开发文档 8.1），所以这里给出**有界**的纯文本；
 * XML 原文永远不会被摘要替代，详情页仍按开发文档 1.3 展示可滚动的原文。
 *
 * ## 三级回退为什么必须存在
 *
 * 1. `Summary`：Mihon/Tachiyomi 写这个字段，有就是最准的简介；
 * 2. [ComicInfoFields.describe]：**EhViewer 根本不写 `Summary`**，而它的
 *    `Series`/`AlternateSeries`/`Penciller`/`Genre`/`Characters`/`Teams`/
 *    `LanguageISO`/`PageCount`/`CommunityRating` 恰好就是读者想知道的全部信息。
 *    把这些字段直接压成 XML 文本会得到「标题+标签+URL 连成一串」的句子，卡片上读不出
 *    所以然；按字段组织成带标签的行才是可读的简介；
 * 3. 压缩 XML 文本：判不出来源的第三方 XML，兜底保留原文信息（开发文档 7.1）。
 */
object SummaryBuilder {

    const val MAX_SUMMARY_LENGTH = 400

    /**
     * 生成摘要；没有可用文本时返回 null，由 UI 显示「无简介」（开发文档 2）。
     *
     * 解析失败或超限的记录返回 null：格式错误的原文压缩后只是噪声，
     * 不能当简介展示（开发文档 7.1「格式错误展示原文及解析失败提示」）。
     *
     * @param excludeName 这部漫画的展示名。与 `Series` 相同时不重复一行「名称」：
     *   EhViewer 的 `Series` 就是目录名，卡片的两行预览不该被它占掉。
     */
    fun build(record: MetadataRecord, excludeName: String? = null): String? {
        if (record.parseError != null) return null
        val summary = ComicInfoParser.fieldValue(record.fields, "Summary")
            // 从数据库回读的记录可能只带已算好的 summary 而没有 fields（历史行、
            // 或只投影了 summary 列的调用方）；**只有这时**才直接采用它，使本函数在
            // 解析路径与回读路径上都幂等。fields 在的时候一律重算：否则传入不同的
            // excludeName 会被旧的 summary 抢先命中，表现为"简介没变"。
            ?: record.summary?.takeIf { it.isNotBlank() && record.fields.isEmpty() }
        val text = summary?.let(::compress)
            ?: ComicInfoFields.describe(record.fields, excludeName)?.let(::compressLines)
            ?: compress(stripMarkup(record.xml))
        return text.takeIf { it.isNotEmpty() }?.let(::truncate)
    }

    private fun compress(text: String): String =
        WHITESPACE.replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ").trim()

    /**
     * 逐行压缩，保留行结构。
     *
     * 为什么不直接复用 [compress]：那一版把换行也压成空格，
     * [ComicInfoFields.describe] 输出的「别名 / 作者 / 分类」会连成一句长行，
     * 不再是可读的简介。空行与只含空白的行去掉，避免摘要开头结尾出现空行。
     */
    private fun compressLines(text: String): String =
        text.split('\n').map(::compress).filter { it.isNotEmpty() }.joinToString("\n")

    /** 截断到上限并加省略号，保证结果长度不超过 [MAX_SUMMARY_LENGTH]。 */
    private fun truncate(text: String): String =
        if (text.length <= MAX_SUMMARY_LENGTH) text else text.take(MAX_SUMMARY_LENGTH - 1) + "…"

    /**
     * 去掉标签、还原基本实体，得到「压缩的 XML 文本」。
     * 只处理五个预定义实体：命名实体需要 DTD，而解析已禁止 DOCTYPE（框架 4.5）。
     */
    private fun stripMarkup(xml: String): String = xml
        .replace(TAG, " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // &amp; 必须最后还原，否则 "&amp;lt;" 会被二次解释成 "<"。
        .replace("&amp;", "&")

    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
}
