package com.lmreader.core.index

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.StableId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描器的行为约束：判定顺序、流式事件、删除判定依据、失败与取消。
 *
 * 这些不是 5.3 的目录样例，而是框架实现说明 4.3 里「容易做错」的条目，
 * 对应验收 A04/A07 与开发文档 6.1/6.2。
 *
 * 其中「发现阶段不遍历所有章节」是本轮按用户要求新增的性能契约：
 * 多章节模式只需**一个**直接章节就能断定"这是一部漫画"，
 * 单章节模式只需**一张**图片且没有子目录就能断定"这是一个章节"。
 */
class StructureScannerBehaviorTest {

    /**
     * 多章节：一部作品有 5 个章节，发现阶段只应探测到**一个**章节，
     * 一旦确认为章节，该目录下其余子文件夹全部跳过（用户要求）。
     */
    @Test
    fun 多章节发现阶段只探测第一个章节其余全部跳过() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf(
                "作品/第1话/001.jpg",
                "作品/第2话/001.jpg",
                "作品/第3话/001.jpg",
                "作品/第4话/001.jpg",
                "作品/第5话/001.jpg",
            ),
        )
        harness.run()

        assertEquals(listOf("作品"), harness.names)
        assertEquals(listOf("第1话"), harness.chaptersOf("作品"))
        assertFalse(harness.chapterCountKnown("作品"))

        // 核心断言：命中章节后，其余章节目录一个都不应该被打开。
        // 其余章节连"被列表"都不需要：只对排序后的第一项做了枚举。
        assertEquals(
            "只应枚举第一章，实际枚举=${harness.factory.calls.filter { it.startsWith("listChildren:/作品/") }}",
            1,
            harness.factory.calls.count { it.startsWith("listChildren:/作品/") },
        )
        // 整轮扫描只打开了一个子目录。
        // 直接断言规则本身：为了确认"某个子目录是不是章节"而打开检查它的次数。
        // 5 个章节的作品应当只探测 1 次就停（命中自然序第一章）。
        // 用专用计数器而不是 fake 的调用日志，因为扫描器是通过 TreeFactory 打开
        // 子目录的，fake 的 ContentTree.openChild 并不是生产路径。
        // 计数包含两次探测：一次是父目录（根）试探「作品」本身是不是章节（不是），
        // 一次是在「作品」里找到第一章。关键是**没有**第 3 次——
        // 也就是说没有再去逐个探测第2..5话。
        assertEquals(
            "应只探测 [作品] 与 [第1话] 两次，实际=${harness.summary.leafChapterProbes} 次",
            2,
            harness.summary.leafChapterProbes,
        )
    }

    /**
     * 单章节：只需确认"有一张图片且没有子目录"。
     *
     * "有一张图片"与"没有子目录"在同一次枚举里回答，因此实现上只列一次目录、
     * 看到第一张图片即可断定这是一个章节（开发文档 5.1「允许在确认存在性后停止
     * 图片内容探测」）。断言的重点是**没有逐项遍历图片**：30 张图片的目录与
     * 1 张图片的目录，对扫描器而言是同一件事。
     */
    @Test
    fun 单章节判定只需一张图片且不遍历全部图片() = runTest {
        val manyImages = (1..30).map { "作品/短篇/%03d.jpg".format(it) }
        val harness = ScanHarness(
            rootName = "库",
            paths = manyImages,
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertEquals(listOf("短篇"), harness.names)
        assertEquals(listOf(listOf("短篇")), harness.chapterTitles)
        assertEquals("单章节模式：1 章是结构定义", true, harness.chapterCountKnown("短篇"))

        // 每个目录只被列一次：存在性判定与章节构建共用同一次枚举，
        // 不存在"为了数图片而反复列目录"。
        val listCalls = harness.factory.calls.count { it == "listChildren:/作品/短篇" }
        assertEquals("叶子章节只应被列一次，实际调用=${harness.factory.calls}", 1, listCalls)
        // 也不应该为了判定章节而去打开图片文件。
        assertFalse(
            "不得为判定章节而打开图片文件，实际调用=${harness.factory.calls}",
            harness.factory.calls.any { it.startsWith("openChild:") && it.endsWith(".jpg") },
        )
    }

    /** 单章节与多章节是两套逻辑：同一结构在两种模式下产出完全不同的卡片。 */
    @Test
    fun 单章节与多章节模式互不混用() = runTest {
        val paths = listOf("作者/短篇/001.jpg")

        val single = ScanHarness("库", paths, mode = LayoutMode.SINGLE_CHAPTER)
        single.run()
        assertEquals(listOf("短篇"), single.names)
        assertTrue(single.chapterCountKnown("短篇"))

        // 同一棵树按多章节解释时，中间目录「作者」才是漫画，章节是「短篇」。
        val multi = ScanHarness("库", paths, mode = LayoutMode.MULTI_CHAPTER)
        multi.run()
        assertEquals(listOf("作者"), multi.names)
        assertEquals(listOf("短篇"), multi.chaptersOf("作者"))
    }

    /**
     * 漫画文件夹里多一张封面图片是常见结构，它不能改变单章节判定。
     *
     * 用户复述的规则（真机核对）：单章节**必须"里面没有子文件夹且有图片"**。
     * 封面图片只是一个普通文件：它不会让"有子文件夹"这一半失效（所以带章节
     * 子目录的漫画文件夹不是单章节），也不会让"有图片"这一半失效（所以封面与页
     * 同层、没有子目录的文件夹就是一张单章节卡片）。
     */
    @Test
    fun 封面图片不影响单章节判定() = runTest {
        // 封面与页在同一层、没有子文件夹 -> 这个文件夹自己就是单章节卡片。
        val leaf = ScanHarness(
            rootName = "库",
            paths = listOf("作品/封面.jpg", "作品/001.jpg", "作品/ComicInfo.xml"),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        leaf.run()
        assertEquals(listOf("作品"), leaf.names)
        assertEquals(listOf(listOf("作品")), leaf.chapterTitles)
        assertTrue("单章节的 1 章是结构定义", leaf.chapterCountKnown("作品"))

        // 封面在漫画文件夹、页在章节目录 -> 有子文件夹，它**不是**单章节；
        // 按规则把这个文件夹当普通文件夹继续遍历，卡片落在叶子目录上。
        val mixed = ScanHarness(
            rootName = "库",
            paths = listOf("作品/封面.jpg", "作品/第1话/001.jpg"),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        mixed.run()
        assertEquals(listOf("第1话"), mixed.names)
        assertFalse("有子文件夹的文件夹不得被当成单章节", mixed.names.contains("作品"))
        assertTrue(
            "混放要留诊断，不能静默",
            mixed.diagnostics.any { it.contains("同时包含图片和子目录") },
        )
    }

    /**
     * 同一张封面图片也不能让多章节判定走偏：漫画文件夹（有封面 + 章节子目录）
     * 不得被当成"单章节"，否则它的父目录会被误判成漫画名。
     */
    @Test
    fun 封面图片不影响多章节判定() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf(
                "作品/封面.jpg",
                "作品/第1话/001.jpg",
                "作品/第2话/002.jpg",
            ),
        )
        harness.run()

        assertEquals(
            "若「作品」因为有封面图片而被当成单章节，这里的漫画名会变成父目录「库」",
            listOf("作品"),
            harness.names,
        )
        assertEquals(listOf("第1话"), harness.chaptersOf("作品"))
    }

    /**
     * 真机 `/Tachiyomi/local` 的形态：同一个目录里既有"章节是图片目录"的作品，
     * 也有"章节是压缩包"的作品（漫画文件夹里只有一张 `cover.jpg` + 若干 `.zip`）。
     *
     * 一条来源、一次遍历就要把它们都扫出来：
     * - 压缩包章节的文件夹**不是**父目录的章节，否则父目录会被判成漫画、其余子文件夹
     *   全部跳过（真机 51 个子文件夹只扫出 1 张名叫 `local` 的卡片：日志 漫画=1
     *   遍历目录=11 章节探测=10，第 10 次探测正好命中 `Jyminish  OOHS`）；
     * - 它自己是一部漫画，章节是那些压缩包。
     */
    @Test
    fun 一条来源同时扫出图片章节与压缩包章节的漫画() = runTest {
        val harness = ScanHarness(
            rootName = "local",
            paths = listOf(
                // 章节是图片目录
                "10000-nichi no 7/Swarm_Chapter 3/001.jpg",
                "Big Banko/第1话/001.jpg",
                // 章节是压缩包：cover.jpg + 两个 .zip，没有子目录
                "Jyminish  OOHS/cover.jpg",
                "Jyminish  OOHS/Jyminish  OOHS1 - A Loss Of Influence (EN).zip",
                "Jyminish  OOHS/Jyminish  OOHS 2 - A Goddess In Distress (EN).zip",
            ),
        )
        harness.run()

        assertEquals(
            "图片章节与压缩包章节的漫画都要被发现",
            setOf("10000-nichi no 7", "Big Banko", "Jyminish  OOHS"),
            harness.names.toSet(),
        )
        assertFalse("授权根「local」不得成为卡片", harness.names.contains("local"))
        assertEquals(listOf("Swarm_Chapter 3"), harness.chaptersOf("10000-nichi no 7"))
        // 压缩包章节必须**全部**登记：归档清单零额外 IO，一次列全才能给出确定章节数。
        // 注意自然序里空格排在数字之前，所以 "…OOHS 2 - …" 先于 "…OOHS1 - …"
        // （这与真机 Jyminish  OOHS/ 目录里的两个 .zip 完全一致）。
        val archiveChapters = harness.resultOf("Jyminish  OOHS").chapters
        assertEquals(
            listOf(
                "Jyminish  OOHS 2 - A Goddess In Distress (EN)",
                "Jyminish  OOHS1 - A Loss Of Influence (EN)",
            ),
            archiveChapters.map { it.title },
        )
        assertTrue(
            "压缩包章节的种类必须是 ARCHIVE",
            archiveChapters.all { it.kind == ChapterKind.ARCHIVE },
        )
        assertTrue(
            "列出全部直接归档后章节数是确定的",
            harness.chapterCountKnown("Jyminish  OOHS"),
        )
    }

    /** 单章节模式同样一条来源覆盖两种形态：叶子图片目录一张卡，每个压缩包各一张卡。 */
    @Test
    fun 单章节模式同时产出图片单章节与压缩包单章节() = runTest {
        val harness = ScanHarness(
            rootName = "local",
            paths = listOf(
                "Jyminish  OOHS/cover.jpg",
                "Jyminish  OOHS/第1话.zip",
                "短篇/001.jpg",
            ),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertEquals(
            "含压缩包的目录本身不算单章节，但它的压缩包各自是一张卡",
            setOf("短篇", "第1话"),
            harness.names.toSet(),
        )
        assertEquals(
            "压缩包单章节的章节种类是 ARCHIVE",
            ChapterKind.ARCHIVE,
            harness.resultOf("第1话").chapters.single().kind,
        )
        assertEquals(
            "图片单章节的章节种类是 IMAGE_DIRECTORY",
            ChapterKind.IMAGE_DIRECTORY,
            harness.resultOf("短篇").chapters.single().kind,
        )
    }

    /** 验收 A04：先见到图片、后枚举到子文件夹，不能误判叶子章节。 */
    @Test
    fun 叶子判定必须先确认没有子目录再看图片() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作品/第一章/001.jpg", "混合/001.jpg", "混合/子目录/002.jpg"),
        )
        harness.run()

        val calls = harness.factory.calls
        // 叶子判定现在只用**一次枚举**同时回答"有没有子目录"和"有没有图片"
        // （实现见 isLeafImageChapter）：因此这里断言的是可观测的等价性质——
        // 判定确实读取了该目录，并且顺序上先判子目录再判图片（框架 4.3 / 验收 A04）。
        // 顺序由实现里的 `if (children.any { it.isDirectory }) false else ...` 保证，
        // 单元层面通过"含子目录的目录不会被当成叶子"这一结果来验证（见下方断言）。
        assertTrue(
            "叶子判定必须读取该目录，实际调用=$calls",
            calls.any { it == "listChildren:/作品/第一章" },
        )
        // 混放目录（既有图片又有子目录）**不得被当成叶子章节**，
        // 但它本身可以是一部漫画——它的子目录「子目录」才是叶子章节。
        // 用"章节名"而不是"漫画名"来断言这一点，才能区分这两种判定。
        assertEquals(
            "混放目录不得被当成叶子章节（验收 A04）",
            listOf("子目录"),
            harness.chaptersOf("混合"),
        )
        // 混放目录会被真正读取（用于发出结构诊断），但它的父目录「库」不会因为
        // "看到一个图片文件"就被当成叶子——根目录没有直接图片，因此不会被误判。
        assertTrue(
            "混放目录必须被读取以发出诊断，实际调用=$calls",
            calls.any { it == "listChildren:/混合" },
        )
        assertTrue("根目录不得被误判为漫画", harness.names.contains("作品"))
    }

    /** 开发文档 6.1：事件必须边发现边发出，不能全部收集完再发。 */
    @Test
    fun 发现事件是流式的而不是结束后一次性发出() = runTest {
        // 用"根下有三部作品"的结构：第一部作品被发现之后，扫描还要继续处理
        // 第二、三部，因此能观察到"发现事件之后仍有目录访问进度"。
        // （不能用"根自己就是漫画"的结构——用户要求的跳过规则会让它在发现后立刻结束。）
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf(
                "甲作品/第一章/a.jpg",
                "乙作品/第一章/b.jpg",
                "丙作品/第一章/c.jpg",
            ),
        )
        harness.run()

        val firstDiscovery = harness.events.indexOfFirst { it is ScanEvent.MangaDiscovered }
        val lastProgress = harness.events.indexOfLast { it is ScanEvent.Progress }
        assertTrue("第一条发现事件必须出现在扫描结束之前", firstDiscovery >= 0)
        assertTrue("发现之后仍有目录访问进度，说明是边扫边发", lastProgress > firstDiscovery)
        assertEquals(1, (harness.events[firstDiscovery] as ScanEvent.MangaDiscovered).totalDiscovered)
        assertEquals(
            listOf(1, 2, 3),
            harness.events.filterIsInstance<ScanEvent.MangaDiscovered>().map { it.totalDiscovered },
        )
    }

    /** 开发文档 6.2：只有完整枚举过的容器才能作为删除判定依据。 */
    @Test
    fun 完整枚举的容器被记录在扫描结果里() = runTest {
        val harness = ScanHarness("库", listOf("作品/第一章/001.jpg"), recursive = false)
        harness.run()

        val result = harness.resultOf("作品")
        assertTrue(
            "锚点目录必须自己出现在完全枚举集合里，否则无法判定章节删除",
            "/作品" in result.fullyEnumeratedContainers,
        )
        assertTrue("根目录也完整枚举过", InMemoryTreeFactory.ROOT_DOCUMENT_ID in result.fullyEnumeratedContainers)
    }

    /** 开发文档 7.1：单章漫画的 ComicInfo.xml 归属到该章；多章仅作顶层兜底候选。 */
    @Test
    fun 元数据候选区分章节与漫画顶层兜底() = runTest {
        val single = ScanHarness(
            rootName = "短篇",
            paths = listOf("001.jpg", "ComicInfo.xml"),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        single.run()
        val chapterCandidate = single.resultOf("短篇").metadataCandidates.single()
        assertEquals(MetadataOwnerType.CHAPTER, chapterCandidate.ownerType)
        assertEquals(InMemoryTreeFactory.ROOT_DOCUMENT_ID, chapterCandidate.ownerDocumentId)
        assertTrue(chapterCandidate.label.contains("ComicInfo.xml"))

        val multi = ScanHarness("库", listOf("作品/第一章/001.jpg", "作品/ComicInfo.xml"))
        multi.run()
        val mangaCandidate = multi.resultOf("作品").metadataCandidates.single()
        assertEquals(MetadataOwnerType.MANGA, mangaCandidate.ownerType)
        assertEquals("/作品", mangaCandidate.ownerDocumentId)
        assertEquals("漫画顶层兜底", mangaCandidate.label)
    }

    /** 归档内的 ComicInfo 只能由补全阶段打开归档后定位（框架 6.3），发现阶段先登记指针。 */
    @Test
    fun 归档章节登记待补全的元数据位置() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作品/01.cbz", "作品/02.pdf"),
            kind = SourceKind.ARCHIVE_IMPORT,
        )
        harness.run()

        // 归档章节清单是零额外 IO 的，发现阶段就把每个直接归档登记为元数据候选；
        // 真正要留到「深入」阶段的是**打开归档**定位其中的 ComicInfo.xml。
        val candidates = harness.resultOf("作品").metadataCandidates
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.ownerType == MetadataOwnerType.CHAPTER })
        assertTrue(candidates.all { it.archiveMemberPath == null })
        assertEquals(setOf("/作品/01.cbz", "/作品/02.pdf"), candidates.map { it.ownerDocumentId }.toSet())
        assertTrue(candidates.all { it.label.contains("归档") })
    }

    /** 稳定身份：漫画/章节 ID 必须能由 documentId + 种类重算（开发文档 15.3）。 */
    @Test
    fun 稳定ID由documentId与种类派生() = runTest {
        val harness = ScanHarness("库", listOf("作品/第一章/001.jpg"))
        harness.run()

        val result = harness.resultOf("作品")
        assertEquals(StableId.mangaId("/作品", SourceKind.IMAGE_DIRECTORY), result.manga.mangaId)
        assertEquals(StableId.chapterId("/作品/第一章", ChapterKind.IMAGE_DIRECTORY), result.chapters.single().chapterId)
        assertEquals(result.manga.mangaId, result.chapters.single().mangaId)
    }

    /** 章节顺序按自然序，而不是目录枚举顺序（框架 4.3「排序」）。 */
    @Test
    fun 章节按自然序排序() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作品/第10话/001.jpg", "作品/第2话/001.jpg", "作品/第1话/001.jpg"),
        )
        harness.run()

        // 发现阶段只带自然序第一章，且必须是「第1话」而不是枚举顺序里的第一个
        // （真实文件系统不保证枚举顺序，真机上曾先返回「第10话」）。
        assertEquals(listOf("第1话"), harness.chaptersOf("作品"))
    }

    /** recursive=false 时归档单章节仍要检查根的直接子目录，但不进入更深的层级。 */
    @Test
    fun 不递归的归档单章节只检查根与直接子目录() = runTest {
        val shallow = ScanHarness(
            rootName = "库",
            paths = listOf("作者/A.cbz", "作者/深层/B.cbz"),
            kind = SourceKind.ARCHIVE_IMPORT,
            mode = LayoutMode.SINGLE_CHAPTER,
            recursive = false,
        )
        shallow.run()
        assertEquals(listOf("A"), shallow.names)

        val deep = ScanHarness(
            rootName = "库",
            paths = listOf("作者/A.cbz", "作者/深层/B.cbz"),
            kind = SourceKind.ARCHIVE_IMPORT,
            mode = LayoutMode.SINGLE_CHAPTER,
            recursive = true,
        )
        deep.run()
        assertEquals(listOf("A", "B"), deep.names)
    }

    /** 空目录不生成卡片，但根为空要留下可操作的原因（框架 4.3）。 */
    @Test
    fun 空目录不生成卡片并给出诊断() = runTest {
        val harness = ScanHarness(
            rootName = "空目录",
            paths = emptyList(),
            mode = LayoutMode.SINGLE_CHAPTER,
        )
        harness.run()

        assertEquals(emptyList<String>(), harness.names)
        assertTrue(harness.diagnostics.any { it.contains("没有受支持的图片") })
    }

    /** 验收 A07：单个目录读取失败不影响其它来源分支，但整次扫描不得标记为完整。 */
    @Test
    fun 单个目录失败不阻断其它作品但会使扫描不完整() = runTest {
        val harness = ScanHarness("库", listOf("作品/第一章/001.jpg", "正常/第一章/001.jpg"))
        harness.factory.failingPaths = setOf("/作品")
        val summary = harness.run()

        assertEquals("失败目录之外的漫画仍要发现", listOf("正常"), harness.names)
        assertTrue("读取失败必须产生 Failed 事件", harness.failures.isNotEmpty())
        assertTrue(
            "诊断路径用展示路径而不是 documentId（框架 4.1），且只应涉及那一个目录",
            harness.failures.all { it.path == "库/作品" },
        )
        assertEquals("摘要里的失败路径要收敛成集合，供 UI 列出部分失败", listOf("库/作品"), summary.failedPaths)
        assertFalse("存在 IO 失败时 completed 必须为 false，否则会被当成删除依据", summary.completed)
    }

    /** 开发文档 6.2：取消后不得把未完成结果标记为完整。 */
    @Test
    fun 取消的扫描不得报告completed为true() = runTest {
        val harness = ScanHarness("库", listOf("作品A/第一章/001.jpg", "作品B/第一章/001.jpg"))
        var summary: ScanSummary? = null

        val job = launch {
            summary = harness.run { event ->
                // 发现第一部漫画后立刻取消：后续目录不应再被访问。
                if (event is ScanEvent.MangaDiscovered) cancel()
            }
        }
        job.join()

        val result = summary
        assertNotNull("取消后仍要返回摘要，而不是把异常抛给调度方", result)
        assertFalse("取消不能报告 completed = true", result!!.completed)
        assertEquals("取消发生在第一部漫画之后", 1, result.mangas)
        // 取消发生在事件回调里，此时"下一个父目录"可能已经被打开——这无法完全避免
        // （openChild 本身不挂起）。真正必须成立的是：取消后不再产出新漫画，
        // 且整次运行不得报告完整（验收 A09、开发文档 6.2）。
        assertFalse("取消后不得继续发现新漫画", harness.names.contains("作品B"))
    }
}
