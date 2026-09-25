package com.lmreader.core.index

import com.lmreader.core.model.MetadataRecord
import java.text.Normalizer

/**
 * 简介摘要：Summary 优先，缺失时压缩 XML 文本，最多 [MAX_SUMMARY_LENGTH] 字符（框架 4.5）。
 *
 * 卡片只显示两行预览（开发文档 8.1），所以这里给出**有界**的纯文本；
 * XML 原文永远不会被摘要替代，详情页仍按开发文档 1.3 展示可滚动的原文。
 */
object SummaryBuilder {

    const val MAX_SUMMARY_LENGTH = 400

    /**
     * 生成摘要；没有可用文本时返回 null，由 UI 显示「无简介」（开发文档 2）。
     *
     * 解析失败或超限的记录返回 null：格式错误的原文压缩后只是噪声，
     * 不能当简介展示（开发文档 7.1「格式错误展示原文及解析失败提示」）。
     */
    fun build(record: MetadataRecord): String? {
        if (record.parseError != null) return null
        val summary = ComicInfoParser.fieldValue(record.fields, "Summary")
            // 从数据库回读的记录可能只带已算好的 summary 而没有 fields；
            // 允许它作为中间回退，使本函数在解析路径与回读路径上都幂等。
            ?: record.summary?.takeIf { it.isNotBlank() }
        val text = summary ?: stripMarkup(record.xml)
        val compressed = compress(text)
        return compressed.takeIf { it.isNotEmpty() }?.let(::truncate)
    }

    private fun compress(text: String): String =
        WHITESPACE.replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ").trim()

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
