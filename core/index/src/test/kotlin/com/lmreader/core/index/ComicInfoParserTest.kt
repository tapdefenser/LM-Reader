package com.lmreader.core.index

import com.lmreader.core.model.MetadataOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 开发文档 7「ComicInfo 与封面」与验收 A11「两种上游 XML、命名空间、未知字段、坏 XML」。
 *
 * 关注四件事：两侧上游字段都能读、未知字段不丢、坏 XML 不抛异常且保留原文、
 * 解析不解析外部实体（XXE）并受大小上限保护。
 */
class ComicInfoParserTest {

    /** EhViewer 写出的字段集合（上游参考与复用边界 2）。 */
    private val ehViewerXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo>
          <Series>网球王子</Series>
          <Title>第一章</Title>
          <Writer>许斐刚</Writer>
          <Penciller>许斐刚</Penciller>
          <Summary>越前龙马加入青学网球部。</Summary>
          <AlternateSeries>テニスの王子様</AlternateSeries>
          <Characters>越前龙马, 手冢国光</Characters>
          <Teams>青学</Teams>
          <CommunityRating>4.5</CommunityRating>
          <Genre>运动</Genre>
          <Web>https://example.invalid/x</Web>
          <PageCount>20</PageCount>
          <LanguageISO>zh</LanguageISO>
          <Number>1</Number>
          <Volume>1</Volume>
          <CustomUnknownField>未知字段也要保留</CustomUnknownField>
        </ComicInfo>
    """.trimIndent()

    @Test
    fun 读取两侧上游的标准字段并保留未知字段() {
        val record = ComicInfoParser.parse("m_1", MetadataOwnerType.MANGA, ehViewerXml, "第一章 ComicInfo.xml")

        assertEquals("网球王子", record.series)
        assertEquals("第一章", record.title)
        assertEquals("许斐刚", record.writer)
        assertEquals("テニスの王子様", record.alternateSeries)
        assertNull(record.parseError)
        assertEquals("第一章 ComicInfo.xml", record.sourceLabel)

        // 未知字段与上游扩展字段都必须留在 map 中，键用元素本地名。
        assertEquals("未知字段也要保留", record.fields["CustomUnknownField"])
        assertEquals("越前龙马, 手冢国光", record.fields["Characters"])
        assertEquals("青学", record.fields["Teams"])
        assertEquals("4.5", record.fields["CommunityRating"])
        assertEquals("20", record.fields["PageCount"])
        assertEquals(16, record.fields.size)
        // 简介走 SummaryBuilder：Summary 优先（框架 4.5）。
        assertEquals("越前龙马加入青学网球部。", record.summary)
    }

    @Test
    fun 兼容Mihon风格命名空间扩展字段() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <ComicInfo xmlns:ns0="http://mihon.app/ns/1.0">
              <Series>作品</Series>
              <Number>3</Number>
              <ns0:Volume>2</ns0:Volume>
              <ns0:CustomExtension>命名空间扩展字段</ns0:CustomExtension>
            </ComicInfo>
        """.trimIndent()

        val record = ComicInfoParser.parse("c_1", MetadataOwnerType.CHAPTER, xml, "第一章 ComicInfo.xml")

        assertNull(record.parseError)
        assertEquals("作品", record.series)
        assertEquals("2", record.fields["Volume"])
        assertEquals("命名空间扩展字段", record.fields["CustomExtension"])
        assertTrue(record.normalizedSearchText.contains("命名空间扩展字段"))
    }

    @Test
    fun 字段名匹配不区分大小写() {
        val xml = "<ComicInfo><series>小写字段名</series><ALTERNATESERIES>别名</ALTERNATESERIES></ComicInfo>"
        val record = ComicInfoParser.parse("m_2", MetadataOwnerType.MANGA, xml, "测试")

        assertEquals("小写字段名", record.series)
        assertEquals("别名", record.alternateSeries)
        // 保留首次出现的大小写写法，便于原文对照。
        assertTrue(record.fields.containsKey("series"))
    }

    @Test
    fun 坏XML不抛异常且保留原文() {
        val xml = "<ComicInfo><Series>未闭合"
        val record = ComicInfoParser.parse("m_3", MetadataOwnerType.MANGA, xml, "测试")

        assertNotNull("格式错误必须结构化返回而不是抛异常", record.parseError)
        assertTrue(record.fields.isEmpty())
        assertNull(record.series)
        assertNull(record.summary)
        assertEquals("原文必须原样保留（开发文档 7.1）", xml, record.xml)
        assertTrue("失败也要有内容指纹，便于发现同长度同时间的变化", record.fingerprint.isNotEmpty())
    }

    @Test
    fun 空输入与非法根元素不抛异常() {
        val empty = ComicInfoParser.parse("m_4", MetadataOwnerType.MANGA, "", "测试")
        assertNotNull(empty.parseError)

        val notComicInfo = ComicInfoParser.parse(
            "m_5",
            MetadataOwnerType.MANGA,
            "<html><body>不是 ComicInfo</body></html>",
            "测试",
        )
        assertNull("能读的 XML 就容错读取，不因为根元素名不同而丢弃", notComicInfo.parseError)
        assertTrue(notComicInfo.fields.isEmpty())
    }

    @Test
    fun XXE载荷不会解析外部实体() {
        val secret = "LM-READER-XXE-CANARY"
        val secretFile = Files.createTempFile("lmreader-xxe", ".txt")
        Files.writeString(secretFile, secret)
        try {
            val xml = """
                <?xml version="1.0"?>
                <!DOCTYPE ComicInfo [<!ENTITY xxe SYSTEM "${secretFile.toUri()}">]>
                <ComicInfo><Series>&xxe;</Series></ComicInfo>
            """.trimIndent()

            val record = ComicInfoParser.parse("m_6", MetadataOwnerType.MANGA, xml, "测试")

            assertNotNull("DOCTYPE 必须被拒绝（框架 4.5）", record.parseError)
            assertTrue(record.fields.isEmpty())
            assertFalse("外部实体内容绝不能进入字段", record.fields.values.any { it.contains(secret) })
            assertFalse("外部实体内容绝不能进入搜索文本", record.normalizedSearchText.contains(secret))
            assertEquals(xml, record.xml)
        } finally {
            Files.deleteIfExists(secretFile)
        }
    }

    @Test
    fun 超过大小上限的输入不解析但保留原文() {
        val limit = ComicInfoParser.MAX_BYTES.toInt()
        val oversized = paddedXml(limit + 1)
        val record = ComicInfoParser.parse("m_7", MetadataOwnerType.MANGA, oversized, "测试")

        assertNotNull("超限必须结构化拒绝，而不是把巨文档塞进内存解析", record.parseError)
        assertTrue(record.fields.isEmpty())
        assertEquals(oversized, record.xml)

        // 恰好等于上限的输入仍然可解析：边界是「大于」而不是「大于等于」。
        val atLimit = paddedXml(limit)
        val ok = ComicInfoParser.parse("m_8", MetadataOwnerType.MANGA, atLimit, "测试")
        assertNull(ok.parseError)
        assertEquals(limit - "<ComicInfo><Series></Series></ComicInfo>".length, ok.fields.getValue("Series").length)
    }

    @Test
    fun 文件名判定不区分大小写但不误认其它XML() {
        assertTrue(ComicInfoParser.isComicInfoFileName("ComicInfo.xml"))
        assertTrue(ComicInfoParser.isComicInfoFileName("comicinfo.XML"))
        assertTrue(ComicInfoParser.isComicInfoFileName("COMICINFO.xml"))
        assertTrue(ComicInfoParser.isComicInfoFileName("包裹目录/ComicInfo.xml"))

        assertFalse(ComicInfoParser.isComicInfoFileName("MyComicInfo.xml"))
        assertFalse(ComicInfoParser.isComicInfoFileName("metadata.xml"))
        assertFalse(ComicInfoParser.isComicInfoFileName("ComicInfo.xml.bak"))
    }

    @Test
    fun 搜索文本覆盖名称别名字段值与文本节点() {
        val xml = """
            <ComicInfo>
              <Series>网球王子</Series>
              <AlternateSeries>テニスの王子様</AlternateSeries>
              <Writer>许斐刚</Writer>
              <CustomUnknownField>ABC-未知</CustomUnknownField>
            </ComicInfo>
        """.trimIndent()
        val record = ComicInfoParser.parse("m_9", MetadataOwnerType.MANGA, xml, "测试")

        val search = record.normalizedSearchText
        assertTrue(search.contains("网球王子"))
        assertTrue(search.contains("テニスの王子様"))
        assertTrue("全部字段值都要进搜索文本（框架 4.5）", search.contains("许斐刚"))
        assertTrue("未知字段值也要可搜索", search.contains("abc-未知"))
        assertEquals("规范化必须小写且去多余空白", search, search.lowercase())
        assertFalse(search.contains("  "))
    }

    @Test
    fun 规范化使用NFC() {
        // "e" + 组合音标 U+0301 在 NFC 下折叠为 "é"，否则中文/日文检索会出现两种写法。
        val decomposed = "<ComicInfo><Series>cafe\u0301 作品</Series></ComicInfo>"
        val record = ComicInfoParser.parse("m_10", MetadataOwnerType.MANGA, decomposed, "测试")

        assertTrue(record.normalizedSearchText.contains("caf\u00e9"))
        assertFalse(record.normalizedSearchText.contains("e\u0301"))
    }

    @Test
    fun 内容指纹稳定且随内容变化() {
        val first = ComicInfoParser.parse("m_11", MetadataOwnerType.MANGA, "<ComicInfo><Series>A</Series></ComicInfo>", "测试")
        val second = ComicInfoParser.parse("m_12", MetadataOwnerType.MANGA, "<ComicInfo><Series>A</Series></ComicInfo>", "测试")
        val different = ComicInfoParser.parse("m_13", MetadataOwnerType.MANGA, "<ComicInfo><Series>B</Series></ComicInfo>", "测试")

        assertEquals("同内容必须同指纹，否则每次扫描都会误判为变化", first.fingerprint, second.fingerprint)
        assertNotEquals(first.fingerprint, different.fingerprint)
        assertEquals(64, first.fingerprint.length)
    }

    private fun paddedXml(totalBytes: Int): String {
        val head = "<ComicInfo><Series>"
        val tail = "</Series></ComicInfo>"
        return head + "x".repeat(totalBytes - head.length - tail.length) + tail
    }
}
