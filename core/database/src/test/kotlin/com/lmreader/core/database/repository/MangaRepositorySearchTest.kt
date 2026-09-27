package com.lmreader.core.database.repository

import com.lmreader.core.database.LmReaderDatabase
import com.lmreader.core.database.dao.ChapterDao
import com.lmreader.core.database.dao.MangaDao
import com.lmreader.core.database.dao.MetadataDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MangaRepositorySearchTest {

    @Test
    fun `图库搜索有图源筛选时只调用筛选查询`() = runTest {
        val dao = mockk<MangaDao>()
        val repository = repository(dao)
        coEvery { dao.searchFiltered(listOf("source-a"), "%needle%", 0, 40) } returns emptyList()
        coEvery { dao.count() } returns 0

        repository.search(
            query = "needle",
            offset = 0,
            limit = 40,
            sourceFilter = setOf("source-a"),
        )

        coVerify(exactly = 1) { dao.searchFiltered(listOf("source-a"), "%needle%", 0, 40) }
        coVerify(exactly = 0) { dao.search(any(), any(), any()) }
    }

    @Test
    fun `图库搜索没有图源筛选时仍查询全库`() = runTest {
        val dao = mockk<MangaDao>()
        val repository = repository(dao)
        coEvery { dao.search("%needle%", 0, 40) } returns emptyList()
        coEvery { dao.count() } returns 0

        repository.search("needle", 0, 40)

        coVerify(exactly = 1) { dao.search("%needle%", 0, 40) }
        coVerify(exactly = 0) { dao.searchFiltered(any(), any(), any(), any()) }
    }

    private fun repository(dao: MangaDao): MangaRepositoryImpl {
        val database = mockk<LmReaderDatabase>()
        every { database.chapterDao() } returns mockk<ChapterDao>()
        every { database.metadataDao() } returns mockk<MetadataDao>()
        return MangaRepositoryImpl(database, dao)
    }
}
