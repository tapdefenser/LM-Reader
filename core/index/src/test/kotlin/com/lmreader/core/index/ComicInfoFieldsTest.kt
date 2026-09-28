package com.lmreader.core.index

import com.lmreader.core.model.MetadataOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两侧上游 ComicInfo 的字段语义（开发文档 7、上游参考与复用边界 2/4）。
 *
 * 两份 XML 都是从真实产物里摘的：`Tachiyomi.json` 来自 Mihon `Downloader.createComicInfoFile`
 * （`manga.author` → `Writer`、`manga.description` → `Summary`），`EhViewer.xml` 来自
 * `com.hippo.ehviewer.spider.ComicInfo`（`groups` → `Writer`、`artists` → `Penciller`、
 * **不写 `Summary`**）。判断"作者"取哪个字段必须看是谁写的，不能只看字段名。
 */
class ComicInfoFieldsTest {

    /** Mihon/Tachiyomi 下载时写进章节目录的 XML（`ty:`/`mh:` 扩展字段也在）。 */
    private val tachiyomiXml = """
        <?xml version='1.0' encoding='UTF-8' ?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Title>第1话</Title>
          <Series>大大的小可爱</Series>
          <Number>1</Number>
          <Summary>比一般人稍大的女孩子和关心她女孩子的甜美故事。</Summary>
          <Writer>长田佳奈</Writer>
          <Penciller>别的画师</Penciller>
          <Genre>ゆり</Genre>
          <Web>https://m.idmzj.com/view/26223/44093.html</Web>
          <ty:Categories xmlns:ty="http://www.w3.org/2001/XMLSchema">MISC</ty:Categories>
        </ComicInfo>
    """.trimIndent()

    /** EhViewer 下载时写进图库目录的 XML：没有 Summary，作者在 Penciller。 */
    private val ehViewerXml = """
        <?xml version='1.0' encoding='UTF-8' ?>
        <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="https://raw.githubusercontent.com/anansi-project/comicinfo/main/schema/v2.0/ComicInfo.xsd">
          <Series>[I-Raf-you (Various)] Microne Magazine Vol. 18 [Chinese] [Digital]</Series>
          <AlternateSeries>[I-Raf-you (よろず)] マイクローンマガジン Vol.18 [中国翻訳] [DL版]</AlternateSeries>
          <Writer>i-raf-you</Writer>
          <Penciller>bosshi, gekka kaguya, inoue mitan</Penciller>
          <Genre>f:angel, f:yuri, m:glasses</Genre>
          <Characters>yuu, kana</Characters>
          <Teams>original</Teams>
          <Web>https://exhentai.org/g/1011643/2c3a729d1b</Web>
          <PageCount>28</PageCount>
          <LanguageISO>zh</LanguageISO>
          <CommunityRating>4.5</CommunityRating>
        </ComicInfo>
    """.trimIndent()

    private fun fields(xml: String): Map<String, String> {
        val record = ComicInfoParser.parse("m_1", MetadataOwnerType.MANGA, xml, "测试")
        assertNull(record.parseError)
        return record.fields
    }

    @Test
    fun 按字段集合判定上游而不是按命名空间前缀() {
        assertEquals(ComicInfoFields.Flavor.TACHIYOMI, ComicInfoFields.flavor(fields(tachiyomiXml)))
        assertEquals(ComicInfoFields.Flavor.EHVIEWER, ComicInfoFields.flavor(fields(ehViewerXml)))
        // 最小 XML（只有标准字段）判不出来 → 按 Mihon 语义处理。
        assertEquals(
            ComicInfoFields.Flavor.UNKNOWN,
            ComicInfoFields.flavor(mapOf("Series" to "作品", "Writer" to "作者")),
        )
    }

    @Test
    fun Mihon的作者取Writer而EhViewer的作者取Penciller() {
        assertEquals("长田佳奈", ComicInfoFields.author(fields(tachiyomiXml)))
        assertEquals(
            "bosshi, gekka kaguya, inoue mitan",
            ComicInfoFields.author(fields(ehViewerXml)),
        )
    }

    @Test
    fun 作者缺失时退回另一侧的字段() {
        val pencillerOnly = mapOf("Series" to "作品", "Penciller" to "画师")
        assertEquals("画师", ComicInfoFields.author(pencillerOnly))

        val writerOnlyEhViewer = mapOf("Series" to "作品", "PageCount" to "10", "Writer" to "社团")
        assertEquals("社团", ComicInfoFields.author(writerOnlyEhViewer))

        assertNull(ComicInfoFields.author(mapOf("Series" to "作品")))
    }

    @Test
    fun 社团只在EhViewer语义下单独展示() {
        assertEquals("i-raf-you", ComicInfoFields.group(fields(ehViewerXml)))
        assertNull("Mihon 的 Writer 就是作者，不该再显示一遍社团", ComicInfoFields.group(fields(tachiyomiXml)))
    }

    @Test
    fun EhViewer没有Summary时按字段组织简介() {
        val text = ComicInfoFields.describe(fields(ehViewerXml))

        assertTrue(text != null)
        assertTrue("日文原名是 EhViewer 独有信息", text!!.contains("别名：[I-Raf-you (よろず)]"))
        assertTrue("作者要取 Penciller 而不是社团", text.contains("作者：bosshi, gekka kaguya, inoue mitan"))
        assertTrue("社团单独一行", text.contains("社团：i-raf-you"))
        assertTrue("f:/m: 前缀要去掉但标签要保留", text.contains("分类：angel, yuri, glasses"))
        assertTrue(text.contains("角色：yuu, kana"))
        assertTrue(text.contains("原作：original"))
        assertTrue(text.contains("信息：zh · 28 页 · 评分 4.5"))
        assertTrue(text.contains("来源：https://exhentai.org/g/1011643/2c3a729d1b"))
    }

    @Test
    fun 名称与漫画展示名相同时不重复() {
        val full = ComicInfoFields.describe(fields(ehViewerXml))
        assertTrue(full!!.contains("名称："))

        val same = ComicInfoFields.describe(
            fields(ehViewerXml),
            excludeName = "[I-Raf-you (Various)] Microne Magazine Vol. 18 [Chinese] [Digital]",
        )
        assertTrue("Series 与目录名相同，卡片的两行不该被它占掉", !same!!.contains("名称："))
    }

    @Test
    fun Mihon没有Summary时章节标题与作品名都保留() {
        val xml = """
            <ComicInfo>
              <Title>第1话</Title>
              <Series>大大的小可爱</Series>
              <Writer>长田佳奈</Writer>
              <Genre>ゆり</Genre>
            </ComicInfo>
        """.trimIndent()
        val text = ComicInfoFields.describe(fields(xml))

        assertTrue(text != null)
        assertTrue("Series 是作品名、Title 是章节名，两者不同都要留", text!!.contains("名称：大大的小可爱 · 第1话"))
        assertTrue(text.contains("作者：长田佳奈"))
        assertTrue(text.contains("分类：ゆり"))
    }

    @Test
    fun 没有可用字段时返回null() {
        assertNull(ComicInfoFields.describe(emptyMap()))
        assertNull(ComicInfoFields.describe(mapOf("Summary" to "只有简介")))
    }
}
