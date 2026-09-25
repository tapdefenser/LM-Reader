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
 */
class StructureScannerBehaviorTest {

    /** 验收 A04：先见到图片、后枚举到子文件夹，不能误判叶子章节。 */
    @Test
    fun 叶子判定必须先确认没有子目录再看图片() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("作品/第一章/001.jpg", "混合/001.jpg", "混合/子目录/002.jpg"),
        )
        harness.run()

        val calls = harness.factory.calls
        // 真正的叶子：两个查询都会发生，且「有没有子目录」在前。
        val dirCheck = calls.indexOf("hasDirectoryChildren:/作品/第一章")
        val imageCheck = calls.indexOf("hasImageChild:/作品/第一章")
        assertTrue("叶子判定要查询子目录", dirCheck >= 0)
        assertTrue("叶子判定要查询图片", imageCheck >= 0)
        assertTrue("必须先确认没有子目录，再确认有图片（框架 4.3）", dirCheck < imageCheck)

        // 含子目录的目录：一旦确认有子目录就短路，绝不能因为先看到图片而当成叶子。
        assertTrue(calls.contains("hasDirectoryChildren:/作品"))
        assertTrue(calls.contains("hasDirectoryChildren:/混合"))
        assertFalse("有子目录时不得再查询图片后就下结论", calls.contains("hasImageChild:/作品"))
        assertFalse(calls.contains("hasImageChild:/混合"))
    }

    /** 开发文档 6.1：事件必须边发现边发出，不能全部收集完再发。 */
    @Test
    fun 发现事件是流式的而不是结束后一次性发出() = runTest {
        val harness = ScanHarness(
            rootName = "库",
            paths = listOf("第一章/a.jpg", "作者/另一作品/第一章/b.jpg"),
        )
        harness.run()

        val firstDiscovery = harness.events.indexOfFirst { it is ScanEvent.MangaDiscovered }
        val lastProgress = harness.events.indexOfLast { it is ScanEvent.Progress }
        assertTrue("第一条发现事件必须出现在扫描结束之前", firstDiscovery >= 0)
        assertTrue("发现之后仍有目录访问进度，说明是边扫边发", lastProgress > firstDiscovery)
        assertEquals(1, (harness.events[firstDiscovery] as ScanEvent.MangaDiscovered).totalDiscovered)
        assertEquals(listOf(1, 2), harness.events.filterIsInstance<ScanEvent.MangaDiscovered>().map { it.totalDiscovered })
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

        assertEquals(listOf("第1话", "第2话", "第10话"), harness.chaptersOf("作品"))
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
        assertFalse("取消后不得继续发现新漫画", harness.factory.calls.contains("listChildren:/作品B"))
    }
}
