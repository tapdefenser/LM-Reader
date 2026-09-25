package com.lmreader.core.index

import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MetadataRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简介摘要（框架 4.5）：Summary 优先，缺失时压缩 XML 文本，最多 [SummaryBuilder.MAX_SUMMARY_LENGTH] 字符。
 *
 * 卡片只显示两行预览（开发文档 8.1），所以摘要必须有界；XML 原文不受影响。
 */
class SummaryBuilderTest {

    @Test
    fun Summary优先于XML文本() {
        val xml = """
            <ComicInfo>
              <Series>作品</Series>
              <Summary>这是正经简介。</Summary>
            </ComicInfo>
        """.trimIndent()
        val record = ComicInfoParser.parse("m_1", MetadataOwnerType.MANGA, xml, "测试")

        assertEquals("这是正经简介。", record.summary)
    }

    @Test
    fun 压缩多余空白与换行() {
        val xml = """
            <ComicInfo>
              <Summary>
                 第一行
                 第二行
              </Summary>
            </ComicInfo>
        """.trimIndent()
        val record = ComicInfoParser.parse("m_2", MetadataOwnerType.MANGA, xml, "测试")

        assertEquals("第一行 第二行", record.summary)
    }

    @Test
    fun 缺少Summary时压缩XML文本() {
        val xml = "<ComicInfo><Series>网球王子</Series><Writer>许斐刚</Writer></ComicInfo>"
        val record = ComicInfoParser.parse("m_3", MetadataOwnerType.MANGA, xml, "测试")

        val summary = record.summary
        assertTrue("没有 Summary 时回退到压缩后的 XML 文本", summary != null && summary.contains("网球王子"))
        assertTrue(summary!!.contains("许斐刚"))
        assertTrue("标签本身不能留在摘要里", !summary.contains("<"))
        assertTrue(summary.length <= SummaryBuilder.MAX_SUMMARY_LENGTH)
    }

    @Test
    fun 摘要被截断到上限并加省略号() {
        val long = "长".repeat(SummaryBuilder.MAX_SUMMARY_LENGTH + 120)
        val xml = "<ComicInfo><Summary>$long</Summary></ComicInfo>"
        val record = ComicInfoParser.parse("m_4", MetadataOwnerType.MANGA, xml, "测试")

        val summary = record.summary
        assertTrue(summary != null)
        assertTrue("最多 ${SummaryBuilder.MAX_SUMMARY_LENGTH} 字符", summary!!.length <= SummaryBuilder.MAX_SUMMARY_LENGTH)
        assertTrue("截断要有可见标记", summary.endsWith("…"))
    }

    @Test
    fun 解析失败的记录不给摘要() {
        val record = ComicInfoParser.parse("m_5", MetadataOwnerType.MANGA, "<ComicInfo><Summary>坏", "测试")

        assertTrue(record.parseError != null)
        assertNull("格式错误的原文压缩后只是噪声，不能当简介展示", record.summary)
    }

    @Test
    fun 空白Summary按缺失处理() {
        val record = record(fields = mapOf("Summary" to "   "), xml = "<ComicInfo><Summary>   </Summary></ComicInfo>")
        assertNull(SummaryBuilder.build(record))
    }

    @Test
    fun 没有可读文本时返回null() {
        val record = record(xml = "<ComicInfo></ComicInfo>")
        assertNull("无简介由 UI 呈现，不返回空串（开发文档 2）", SummaryBuilder.build(record))
    }

    @Test
    fun 已算好的摘要可重复构建() {
        // 从数据库回读时可能只有 summary 没有 fields；重复构建必须幂等。
        val stored = "已保存的简介"
        val record = record(xml = "<ComicInfo/>", summary = stored)
        assertEquals(stored, SummaryBuilder.build(record))
    }

    private fun record(
        xml: String,
        fields: Map<String, String> = emptyMap(),
        parseError: String? = null,
        summary: String? = null,
    ) = MetadataRecord(
        ownerId = "m_test",
        ownerType = MetadataOwnerType.MANGA,
        xml = xml,
        fields = fields,
        summary = summary,
        series = null,
        title = null,
        writer = null,
        alternateSeries = null,
        normalizedSearchText = "",
        parseError = parseError,
        sourceLabel = "测试",
        fingerprint = "fingerprint",
        updatedAt = 0L,
    )
}
