package com.lmreader.core.storage.scan

import com.lmreader.core.index.StructureScanner
import com.lmreader.core.index.TreeFactory
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.TreeAccess
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * 「陈旧卡片」标记的调用纪律（开发文档 6.2「只有完整枚举过的容器才能作为删除判定
 * 依据」、验收 A07「失败不删索引」）。
 *
 * 这段逻辑的风险不对称：**漏标**只是旧卡片多留一会儿，**误标**会把整个来源的卡片
 * 从图库/书架里隐藏掉。因此两个方向都必须被锁住：
 * 1. 扫描完整跑完（`completed = true`）→ 用本次 generation 标记一次；
 * 2. 目录读取失败（`completed = false`）→ 一次都不能标记。
 */
class SourceScanRunnerStaleTest {

    @Test
    fun `完整跑完的扫描用本次代次标记陈旧卡片`() = runBlocking {
        val repository = mockk<MangaRepository>(relaxed = true)
        val runner = runnerFor(
            root = FakeTree("库", listOf(image("001.jpg"))),
            repository = repository,
        )

        runner.requestScan(source()).join()

        coVerify(exactly = 1) {
            repository.markUndiscoveredAsStale(sourceId = SOURCE_ID, generation = any())
        }
    }

    @Test
    fun `读取失败的扫描不得标记陈旧卡片`() = runBlocking {
        val repository = mockk<MangaRepository>(relaxed = true)
        // 根目录枚举抛 IO 异常 -> ScanSummary.completed = false -> 状态 FAILED。
        // 此时绝不能标记：这次没读到不等于卡片已经不存在。
        val runner = runnerFor(root = FakeTree("库", failListing = true), repository = repository)

        runner.requestScan(source()).join()
        val state = runner.states.value.getValue(SOURCE_ID)

        assertEquals("扫描状态应为失败", ScanRunStatus.FAILED, state.status)
        assertEquals("失败时不得标记任何卡片", 0, state.staleMarked)
        coVerify(exactly = 0) {
            repository.markUndiscoveredAsStale(sourceId = any(), generation = any())
        }
    }

    private fun runnerFor(root: ContentTree, repository: MangaRepository): SourceScanRunner {
        val treeAccess = mockk<TreeAccess>(relaxed = true)
        every { treeAccess.usesDirectFileAccess() } returns true
        every { treeAccess.checkReadable(any()) } returns null
        every { treeAccess.rootDocumentId(any()) } returns ROOT_DOCUMENT_ID
        every { treeAccess.open(any(), any()) } returns root
        every { treeAccess.treeFactory(any()) } returns TreeFactory { null }

        return SourceScanRunner(
            treeAccess = treeAccess,
            scanner = StructureScanner(TreeFactory { null }),
            mangaRepository = repository,
            sourceRepository = mockk<SourceRepository>(relaxed = true),
            clock = { FIXED_TIME },
        )
    }

    private fun source() = LibrarySource(
        sourceId = SOURCE_ID,
        kind = SourceKind.IMAGE_DIRECTORY,
        treeUri = "content://test/tree/root",
        displayPath = "/库",
        providerLabel = null,
        displayName = null,
        recursive = true,
        mode = LayoutMode.SINGLE_CHAPTER,
        orderIndex = 0,
        permission = SourcePermissionState.OK,
        revision = 0,
        lastScanAt = null,
        lastScanStatus = null,
        lastScanError = null,
    )

    private fun image(name: String) = ChildNode(
        documentId = "$ROOT_DOCUMENT_ID/$name",
        name = name,
        isDirectory = false,
        mimeType = "image/jpeg",
    )

    /** 最小内容树：只有直接文件；[failListing] 时枚举抛 [IOException]（模拟读盘失败）。 */
    private class FakeTree(
        override val rootName: String,
        private val files: List<ChildNode> = emptyList(),
        private val failListing: Boolean = false,
    ) : ContentTree {
        override suspend fun listChildren(): List<ChildNode> {
            if (failListing) throw IOException("模拟列举失败")
            return files
        }

        override suspend fun hasDirectoryChildren(): Boolean = files.any { it.isDirectory }

        override suspend fun hasImageChild(): Boolean = files.any { !it.isDirectory }

        override suspend fun openChild(child: ChildNode): ContentTree? = null
    }

    private companion object {
        const val SOURCE_ID = "source-stale-test"
        const val ROOT_DOCUMENT_ID = "/库"
        const val FIXED_TIME = 1_700_000_000_000L
    }
}
