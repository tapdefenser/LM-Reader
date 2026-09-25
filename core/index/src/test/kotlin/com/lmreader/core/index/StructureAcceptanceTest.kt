package com.lmreader.core.index

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开发文档 5.3「结构验收样例」逐行编码（验收 A03「卡片/章节归属完全吻合」）。
 *
 * 每个测试对应表格的一行，注释里写明「授权与配置」和「文件结构」，
 * 断言的是表格「预期」列里的漫画名、章节名与章节数原话。
 * 需求依据：开发文档 5.1/5.2 的识别规则、框架实现说明 4.3 的算法。
 */
class StructureAcceptanceTest {

    /** 5.3 第 1 行：`download`，递归多章图片，`pixiv/网球王子/第一章/001.jpg`。 */
    @Test
    fun row01_递归多章图片_取最近的含叶子章节目录() = runTest {
        val harness = ScanHarness("download", listOf("pixiv/网球王子/第一章/001.jpg"))
        val summary = harness.run()

        assertEquals("不是 pixiv：多章节模式取直接包含叶子章节的目录（开发文档 5.1 末段）", listOf("网球王子"), harness.names)
        assertEquals(listOf(listOf("第一章")), harness.chapterTitles)
        assertEquals(1, summary.mangas)
        assertEquals(1, summary.chapters)
        assertTrue(summary.completed)
    }

    /** 5.3 第 2 行：`网球王子`，多章图片，`第一章/001.jpg`、`第二章/001.jpg`。 */
    @Test
    fun row02_授权根自身是多章漫画() = runTest {
        val harness = ScanHarness("网球王子", listOf("第一章/001.jpg", "第二章/001.jpg"))
        harness.run()

        assertEquals(listOf("网球王子"), harness.names)
        // 发现阶段只探测到自然序第一章；完整章节清单由「更新章节」按需枚举
        // （用户要求：多章节扫描不遍历所有章节文件夹）。
        assertEquals(listOf(listOf("第一章")), harness.chapterTitles)
        assertEquals(1, harness.resultOf("网球王子").manga.chapterCount)
        assertFalse(
            "只探测到一个章节时不得声明章节数已知（开发文档 5.1）",
            harness.chapterCountKnown("网球王子"),
        )
    }

    /** 5.3 第 3 行：`短篇`，单章图片，`001.jpg`、`ComicInfo.xml`。 */
    @Test
    fun row03_单章图片_根自身一部漫画共一章() = runTest {
        val harness = ScanHarness(
            rootName = "短篇",
            paths = listOf("001.jpg", "ComicInfo.xml"),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertEquals(listOf("短篇"), harness.names)
        assertEquals(listOf(listOf("短篇")), harness.chapterTitles)
        assertEquals(1, harness.resultOf("短篇").manga.chapterCount)
        // 根特例的章节身份就是根自身，章节标题用目录名（框架 4.3「根 documentId」）。
        assertEquals(InMemoryTreeFactory.ROOT_DOCUMENT_ID, harness.resultOf("短篇").chapters.single().documentId)
    }

    /** 5.3 第 4 行：`短篇`，多章图片，`001.jpg` —— 根特例，共 1 章，并显示结构提示。 */
    @Test
    fun row04_多章模式下根自身是叶子图片目录_根特例并提示() = runTest {
        val harness = ScanHarness("短篇", listOf("001.jpg"))
        val summary = harness.run()

        assertEquals(listOf("短篇"), harness.names)
        assertEquals(listOf(listOf("短篇")), harness.chapterTitles)
        assertEquals(1, summary.chapters)
        assertTrue("必须给出结构提示，不能静默按多章节解释", harness.diagnostics.any { it.contains("根特例") })
    }

    /** 5.3 第 5 行：`库`，不递归多章图片，`作品/第一章/001.jpg` —— 必要章节探测不被递归开关禁止。 */
    @Test
    fun row05_不递归仍要读取候选的直接章节层() = runTest {
        val harness = ScanHarness("库", listOf("作品/第一章/001.jpg"), recursive = false)
        harness.run()

        assertEquals(listOf("作品"), harness.names)
        assertEquals(listOf(listOf("第一章")), harness.chapterTitles)
    }

    /** 5.3 第 6 行：`库`，不递归多章图片，`作者/作品/第一章/001.jpg` —— 不发现深层「作品」；开启递归后发现。 */
    @Test
    fun row06_不递归不发现更深层的漫画_递归后发现() = runTest {
        val shallow = ScanHarness("库", listOf("作者/作品/第一章/001.jpg"), recursive = false)
        shallow.run()
        assertEquals("根的直接子目录只是候选，不能继续向更深处发现漫画", emptyList<String>(), shallow.names)

        val deep = ScanHarness("库", listOf("作者/作品/第一章/001.jpg"), recursive = true)
        deep.run()
        assertEquals(listOf("作品"), deep.names)
        assertEquals(listOf(listOf("第一章")), deep.chapterTitles)
    }

    /** 5.3 第 7 行：`库`，递归单章图片，`作者/短篇/001.jpg` —— 漫画「短篇」，不是「作者」。 */
    @Test
    fun row07_单章模式每个叶子目录一本漫画() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作者/短篇/001.jpg"),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertEquals("单章节模式不把中间容器当作漫画", listOf("短篇"), harness.names)
        assertEquals(listOf(listOf("短篇")), harness.chapterTitles)
    }

    /** 5.3 第 8 行：`库`，单章图片，`混合/001.jpg`、`混合/子目录/002.jpg`。 */
    @Test
    fun row08_混放目录不是叶子章节_诊断并继续递归() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("混合/001.jpg", "混合/子目录/002.jpg"),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertFalse("混放目录不得被当成章节或漫画", harness.names.contains("混合"))
        assertEquals("递归开启时仍要发现里面的叶子子目录", listOf("子目录"), harness.names)
        assertTrue(
            "混放必须进诊断列表，不能静默遗漏（开发文档 5.1）",
            harness.diagnostics.any { it.contains("混放") || it.contains("同时包含图片和子目录") },
        )
    }

    /** 5.3 第 9 行：`库`，单章归档，`A.cbz`、`B.pdf` —— 两张卡片，各共 1 章。 */
    @Test
    fun row09_单章归档每个文件一张卡片() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("A.cbz", "B.pdf"),
            kind = SourceKind.ARCHIVE_IMPORT,
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertEquals(listOf("A", "B"), harness.names)
        assertEquals(listOf(listOf("A"), listOf("B")), harness.chapterTitles)
        assertTrue(harness.discoveries.all { it.manga.chapterCount == 1 })
        assertTrue(harness.discoveries.all { it.chapters.single().kind == ChapterKind.ARCHIVE })
        // 归档文件的锚点就是文件自身，章节标题是去扩展名的文件名（开发文档 1.3）。
        assertEquals("/A.cbz", harness.resultOf("A").manga.anchorDocumentId)
    }

    /** 5.3 第 10 行：`库`，多章归档，`作品/01.cbz`、`作品/02.zip`、`作品/03.pdf`。 */
    @Test
    fun row10_多章归档目录共三章() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作品/01.cbz", "作品/02.zip", "作品/03.pdf"),
            kind = SourceKind.ARCHIVE_IMPORT,
        )
        harness.run()

        assertEquals(listOf("作品"), harness.names)
        // 开发文档 5.3 第 10 行的期望就是「共 3 章」：归档章节清单是零额外 IO 的，
        // 必须一次列全，而不是只取自然序第一个。
        assertEquals(listOf(listOf("01", "02", "03")), harness.chapterTitles)
        assertTrue(
            "列出全部直接归档后章节数是确定的，不得再报「已发现 N 章」",
            harness.chapterCountKnown("作品"),
        )
    }

    /** 5.3 第 11 行：`作品`，多章归档，`01.cbz`、`02.pdf` —— 授权根自身为漫画。 */
    @Test
    fun row11_多章归档的授权根自身是漫画() = runTest {
        val harness = ScanHarness(
            rootName = "作品",
            paths = listOf("01.cbz", "02.pdf"),
            kind = SourceKind.ARCHIVE_IMPORT,
        )
        harness.run()

        assertEquals(listOf("作品"), harness.names)
        assertEquals(listOf(listOf("01", "02")), harness.chapterTitles)
        assertEquals(InMemoryTreeFactory.ROOT_DOCUMENT_ID, harness.resultOf("作品").manga.anchorDocumentId)
    }

    /** 5.3 第 12 行：根包含直章与深层容器，递归多章 —— 不提前截断整树。 */
    /**
     * 开发文档 5.3 第 12 行的样例，在**用户要求的跳过规则**下的结果。
     *
     * 原文期望 `库` 与 `另一作品` 都被发现（"根有直章时仍要检查其它直接子目录"）。
     * 用户明确要求：多章节模式**只要某个文件夹下有一个章节，其余文件夹全部跳过**，
     * 因为那都是同一部作品的内容。因此这里的期望改为只发现 `库`。
     *
     * 这是一处**有意接受的取舍**：代价是"根下既有一部单篇、更深处又另有一整套作品"
     * 时后者不会被发现；收益是每部作品最多只打开一个子目录。要发现后者，
     * 应把授权根指向那一层，或改用单章节模式（两条路径本来就是两套逻辑）。
     */
    @Test
    fun row12_找到章节后其余子目录全部跳过() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("第一章/a.jpg", "作者/另一作品/第一章/b.jpg"),
        )
        harness.run()

        assertEquals(listOf("库"), harness.names)
        assertEquals(listOf("第一章"), harness.chaptersOf("库"))
        // 「作者」分支完全没有被打开——这正是性能优化的体现。
        assertFalse(
            "找到章节后不应再打开其它子目录，实际调用=${harness.factory.calls}",
            harness.factory.calls.any { it == "openChild:/作者" },
        )
    }

    /** 5.3 第 13 行：`库`，递归多章图片，`作者/短篇/001.jpg` —— 漫画「作者」，章节「短篇」。 */
    @Test
    fun row13_多章模式把中间容器当漫画() = runTest {
        val harness = ScanHarness("库", listOf("作者/短篇/001.jpg"))
        harness.run()

        assertEquals(listOf("作者"), harness.names)
        assertEquals(listOf(listOf("短篇")), harness.chapterTitles)
    }

    /** 5.3 第 14 行：`库`，递归多章图片，`作品/第1卷/第1话/001.jpg`、`作品/第2卷/第1话/001.jpg`。 */
    @Test
    fun row14_卷下还有话时按卷拆成卡片_不向作品折叠() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作品/第1卷/第1话/001.jpg", "作品/第2卷/第1话/001.jpg"),
        )
        harness.run()

        assertEquals(listOf("第1卷", "第2卷"), harness.names)
        assertEquals(listOf(listOf("第1话"), listOf("第1话")), harness.chapterTitles)
        assertFalse("不得向更高祖先自动折叠", harness.names.contains("作品"))
    }

    /**
     * 用户原始需求里的示例：`download/pixiv/网球王子/第一章/图片`。
     *
     * 期望两个结果同时成立：
     * 1. 漫画是「网球王子」（最近的、直接包含叶子章节的目录），**不是**「pixiv」；
     * 2. 它的章节目录「第一章」不能因为"里面还有图片"而另外生成一张卡片。
     *
     * 这条与第 13 行的 `作者/短篇/001.jpg` → 「作者」并不矛盾：第 13 行的
     * `短篇` 是叶子图片目录而不是"含章节的目录"，所以最近的含章节目录就是「作者」；
     * 这里 `第一章` 本身是叶子章节，它已经被归属给「网球王子」。
     */
    @Test
    fun 需求示例_下载目录里的pixiv结构取最近的含章节目录() = runTest {
        val harness = ScanHarness(
            rootName = "download",
            paths = listOf("pixiv/网球王子/第一章/001.jpg", "pixiv/网球王子/第二章/001.jpg"),
        )
        harness.run()

        assertEquals(listOf("网球王子"), harness.names)
        // 锚点章节必须是**自然序第一章**，不能是目录枚举碰巧返回的那个。
        assertEquals(listOf(listOf("第一章")), harness.chapterTitles)
    }

    /**
     * 同一棵授权树下既有卷册结构又有另一部作品时，不能因为第一层没有直接章节
     * 就停止，也不能把中间容器当作品。
     */
    @Test
    fun 中间容器不作为漫画卡片但其它分支仍被发现() = runTest {
        val harness = ScanHarness(
            rootName = "download",
            paths = listOf(
                "pixiv/网球王子/第一章/001.jpg",
                "pixiv/天使的短篇/vol01/001.jpg",
                "local/作品/第1话/001.jpg",
            ),
        )
        harness.run()

        assertEquals(listOf("网球王子", "天使的短篇", "作品"), harness.names)
        assertFalse("pixiv 不是漫画", harness.names.contains("pixiv"))
        assertFalse("local 不是漫画", harness.names.contains("local"))
    }

    /** 单章节模式遇到同样的包裹结构时，得到的是叶子目录而不是中间容器。 */
    @Test
    fun 单章节模式包裹结构取叶子目录() = runTest {
        val harness = ScanHarness(
            rootName = "download",
            mode = LayoutMode.SINGLE_CHAPTER,
            paths = listOf("pixiv/短篇/001.jpg"),
        )
        harness.run()

        assertEquals(listOf("短篇"), harness.names)
        assertEquals(listOf(listOf("短篇")), harness.chapterTitles)
        assertFalse("pixiv 只是包裹目录", harness.names.contains("pixiv"))
    }
}
