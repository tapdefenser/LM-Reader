package com.lmreader.core.storage.scan

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaBackfillTarget
import com.lmreader.core.model.MangaMetadataUpdate
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 补全阶段的 ComicInfo 读取（开发文档 6.1 第 2 步、7）。
 *
 * 覆盖的是真机上三种真实形状：
 * - 多章节：`<作品>/<章>/ComicInfo.xml`（Mihon 下载 / 本地源）；
 * - 单章节：图库目录本身就是章节，`ComicInfo.xml` 在它第一层（EhViewer）；
 * - 首章没有 XML → 顶层兜底；两处都没有 → 只记"探测过了"。
 *
 * 这里**不依赖 Android**：目录与文件都由两个函数注入，所以"目录打不开/流打不开"
 * 这两条最容易被忽略的分支也能测。
 */
class MetadataBackfillWorkerTest {

    @Test
    fun `多章节读首章XML并写入简介与作者`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话"), chapter("c2", "$ANCHOR/第2话")),
        )
        val worker = worker(
            repo = repo,
            files = mapOf("$ANCHOR/第1话/ComicInfo.xml" to MIHON_XML),
        )

        assertEquals(true, worker.backfillOne(MANGA_ID))

        val update = repo.capturedUpdate()
        assertEquals("比一般人稍大的女孩子和关心她女孩子的甜美故事。", update.summary)
        assertEquals("长田佳奈", update.author)
        assertTrue(update.hasMetadata)
        assertEquals(NOW, update.metadataProbedAt)
        assertEquals(1, update.records.size)
        assertEquals("c1", update.records.single().ownerId)
    }

    @Test
    fun `单章节EhViewer取Penciller做作者且简介不含重复的名称`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            anchor = ANCHOR,
            chapters = listOf(chapter("c1", ANCHOR)),
            layoutMode = LayoutMode.SINGLE_CHAPTER,
        )
        val worker = worker(repo = repo, files = mapOf("$ANCHOR/ComicInfo.xml" to EHVIEWER_XML))

        assertEquals(true, worker.backfillOne(MANGA_ID))

        val update = repo.capturedUpdate()
        val summary = assertNotNull(update.summary)
        assertEquals("bosshi, gekka kaguya", update.author)
        assertTrue(!summary.contains("作者：i-raf-you"), "EhViewer 的 Writer 是社团，不该当成作者：$summary")
        assertTrue(summary.contains("作者：bosshi, gekka kaguya"))
        assertTrue(summary.contains("社团：i-raf-you"))
        assertTrue(!summary.contains("名称："), "Series 与目录名相同，不该重复一行名称：$summary")
    }

    @Test
    fun `首章没有XML时回退漫画顶层`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话")),
        )
        // 首章目录里没有 ComicInfo.xml，只有顶层有。
        val worker = worker(repo = repo, files = mapOf("$ANCHOR/ComicInfo.xml" to MIHON_XML))

        assertEquals(true, worker.backfillOne(MANGA_ID))

        val update = repo.capturedUpdate()
        assertEquals("长田佳奈", update.author)
        assertEquals(MANGA_ID, update.records.single().ownerId)
        assertTrue(update.records.single().sourceLabel.contains("顶层"))
    }

    @Test
    fun `首章是归档时不解析归档内部但仍读顶层`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话.zip", kind = ChapterKind.ARCHIVE)),
        )
        val worker = worker(repo = repo, files = mapOf("$ANCHOR/ComicInfo.xml" to MIHON_XML))

        assertEquals(true, worker.backfillOne(MANGA_ID))

        assertEquals("长田佳奈", repo.capturedUpdate().author)
    }

    @Test
    fun `两处都没有XML时只记探测时间不清空已有简介`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话")),
        )
        val worker = worker(repo = repo, files = emptyMap())

        assertEquals(false, worker.backfillOne(MANGA_ID))

        val update = repo.capturedUpdate()
        assertEquals(NOW, update.metadataProbedAt)
        assertEquals(null, update.summary)
        assertEquals(null, update.author)
        assertTrue(!update.hasMetadata)
    }

    @Test
    fun `目录打不开时不抛异常只当没读到`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话")),
        )
        val worker = MetadataBackfillWorker(
            openTree = { _, _ -> null },
            openInputStream = { _, _ -> null },
            mangaRepository = repo,
            clock = { NOW },
        )

        assertEquals(false, worker.backfillOne(MANGA_ID))
        assertEquals(NOW, repo.capturedUpdate().metadataProbedAt)
    }

    @Test
    fun `顶层兜底的摘要与漫画级记录一致`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话.zip", kind = ChapterKind.ARCHIVE)),
        )
        val worker = worker(repo = repo, files = mapOf("$ANCHOR/ComicInfo.xml" to EHVIEWER_XML))

        worker.backfillOne(MANGA_ID)

        val update = repo.capturedUpdate()
        // 卡片预览读的是 md.summary，详情页读的是 mangas.summary：必须同一段文字。
        assertEquals(update.summary, update.records.single().summary)
    }

    @Test
    fun `授权失效的来源整体跳过`() = runTest {
        val repo = mockk<MangaRepository>(relaxed = true)
        coEvery { repo.getBackfillTarget(MANGA_ID) } returns target(
            chapters = listOf(chapter("c1", "$ANCHOR/第1话")),
            permission = SourcePermissionState.LOST,
        )
        val worker = worker(repo = repo, files = mapOf("$ANCHOR/第1话/ComicInfo.xml" to MIHON_XML))

        assertEquals(0, worker.backfill(listOf(MANGA_ID)))
        coVerify(exactly = 0) { repo.applyMetadataUpdate(any()) }
    }

    // ---- 夹具 ----------------------------------------------------------------

    /** Mihon 下载/本地源写出的章节 XML：`Writer` 是作者、`Summary` 是简介。 */
    private val MIHON_XML = """
        <?xml version='1.0' encoding='UTF-8' ?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <Title>第1话</Title>
          <Series>大大的小可爱</Series>
          <Number>1</Number>
          <Summary>比一般人稍大的女孩子和关心她女孩子的甜美故事。</Summary>
          <Writer>长田佳奈</Writer>
          <Genre>ゆり</Genre>
        </ComicInfo>
    """.trimIndent()

    /** EhViewer 写出的图库 XML：没有 Summary，`Writer` 是社团、`Penciller` 是画师。 */
    private val EHVIEWER_XML = """
        <?xml version='1.0' encoding='UTF-8' ?>
        <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Series>大大的小可爱</Series>
          <AlternateSeries>おおきなかのじょ</AlternateSeries>
          <Writer>i-raf-you</Writer>
          <Penciller>bosshi, gekka kaguya</Penciller>
          <Genre>f:yuri</Genre>
          <PageCount>28</PageCount>
          <LanguageISO>zh</LanguageISO>
        </ComicInfo>
    """.trimIndent()

    private fun worker(repo: MangaRepository, files: Map<String, String>): MetadataBackfillWorker {
        // 只有 "文件路径 → 内容" 这一份真值，目录列表由它派生：这样"目录存在但里面
        // 没有 ComicInfo.xml"与"目录压根打不开"两种情况在测试里自然区分开。
        val byDirectory = files.keys
            .groupBy { it.substringBeforeLast('/') }
            .mapValues { (_, paths) -> paths.map { it to it.substringAfterLast('/') } }
        return MetadataBackfillWorker(
            openTree = { _, documentId -> byDirectory[documentId]?.let { ListingTree(documentId, it) } },
            openInputStream = { _, documentId ->
                files[documentId]?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
            },
            mangaRepository = repo,
            clock = { NOW },
        )
    }

    /** 一个目录：子项就是传入的那些文件（本测试只关心 ComicInfo.xml 能不能被找到）。 */
    private class ListingTree(directory: String, files: List<Pair<String, String>>) : ContentTree {
        override val rootName: String = directory.substringAfterLast('/')

        private val children = files
            .filter { it.first.substringBeforeLast('/') == directory }
            .map { (path, name) -> ChildNode(path, name, isDirectory = false, mimeType = "text/xml") }

        override suspend fun listChildren(): List<ChildNode> = children
        override suspend fun hasDirectoryChildren(): Boolean = false
        override suspend fun hasImageChild(): Boolean = false
        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private fun chapter(
        id: String,
        documentId: String,
        kind: ChapterKind = ChapterKind.IMAGE_DIRECTORY,
    ) = ChapterRecord(
        chapterId = id,
        mangaId = MANGA_ID,
        documentId = documentId,
        kind = kind,
        title = documentId.substringAfterLast('/'),
        sortKey = documentId,
        pageCount = null,
        coverDocumentId = null,
        contentRevision = 0,
        discoveredAt = 0,
    )

    private fun target(
        anchor: String = ANCHOR,
        chapters: List<ChapterRecord>,
        layoutMode: LayoutMode = LayoutMode.MULTI_CHAPTER,
        permission: SourcePermissionState = SourcePermissionState.OK,
    ) = MangaBackfillTarget(
        manga = MangaRecord(
            mangaId = MANGA_ID,
            anchorDocumentId = anchor,
            sourceId = SOURCE_ID,
            sourceKind = SourceKind.IMAGE_DIRECTORY,
            layoutMode = layoutMode,
            displayName = DISPLAY_NAME,
            author = null,
            hasMetadata = false,
            summary = null,
            coverDocumentId = null,
            coverChapterId = null,
            chapterCount = chapters.size,
            chapterCountKnown = true,
            availability = MangaAvailability.AVAILABLE,
            discoveryGeneration = 1L,
            discoveredAt = 0L,
            updatedAt = 0L,
        ),
        chapters = chapters,
        sourceTreeUri = TREE_URI,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        sourcePermission = permission,
        hasCover = false,
        hasMetadata = false,
    )

    private fun MangaRepository.capturedUpdate(): MangaMetadataUpdate {
        val slot = slot<MangaMetadataUpdate>()
        coVerify(exactly = 1) { applyMetadataUpdate(capture(slot)) }
        return slot.captured
    }

    private companion object {
        const val MANGA_ID = "m_1"
        const val SOURCE_ID = "s_1"
        const val TREE_URI = "content://test/tree/library"
        const val ANCHOR = "/library/大大的小可爱"
        const val DISPLAY_NAME = "大大的小可爱"
        const val NOW = 1_700_000_000_000L
    }
}
