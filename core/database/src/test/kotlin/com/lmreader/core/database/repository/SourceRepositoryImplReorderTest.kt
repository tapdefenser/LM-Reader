package com.lmreader.core.database.repository

import androidx.room.withTransaction
import com.lmreader.core.database.LmReaderDatabase
import com.lmreader.core.database.dao.MangaDao
import com.lmreader.core.database.dao.SourceDao
import com.lmreader.core.database.entity.LibrarySourceEntity
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 来源顺序与漫画表里的冗余顺序必须在同一事务内保持一致。 */
class SourceRepositoryImplReorderTest {

    private val database = mockk<LmReaderDatabase>()
    private val mangaDao = mockk<MangaDao>()
    private val mangaOrderUpdates = mutableListOf<Pair<String, Int>>()
    private var inTransaction = false
    private var transactionRan = false

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        every { database.mangaDao() } returns mangaDao
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers {
            transactionRan = true
            inTransaction = true
            try {
                secondArg<suspend () -> Unit>().invoke()
            } finally {
                inTransaction = false
            }
        }
        coEvery { mangaDao.updateSourceOrder(any(), any()) } coAnswers {
            assertTrue("两张表的写入必须在同一个事务里", inTransaction)
            mangaOrderUpdates += firstArg<String>() to secondArg<Int>()
        }
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `调用方给出的顺序落到两张表，未给出的来源推到表尾`() = runBlocking {
        val dao = FakeSourceDao(source("s1", 0), source("s2", 1), source("s3", 2))
        val repository = SourceRepositoryImpl(database, dao)

        repository.reorder(listOf("s3", "s1"))

        assertEquals(mapOf("s3" to 0, "s1" to 1, "s2" to 2), dao.orderOf())
        assertEquals(listOf("s3" to 0, "s1" to 1, "s2" to 2), mangaOrderUpdates)
        assertTrue("reorder 必须走 Room 事务", transactionRan)
    }

    @Test
    fun `整表重排时两张表逐行一致`() = runBlocking {
        val dao = FakeSourceDao(source("s1", 0), source("s2", 1), source("s3", 2))
        val repository = SourceRepositoryImpl(database, dao)

        repository.reorder(listOf("s2", "s3", "s1"))

        assertEquals(mapOf("s2" to 0, "s3" to 1, "s1" to 2), dao.orderOf())
        assertEquals(listOf("s2" to 0, "s3" to 1, "s1" to 2), mangaOrderUpdates)
    }

    private fun source(sourceId: String, orderIndex: Int) = LibrarySourceEntity(
        sourceId = sourceId,
        kind = SourceKind.IMAGE_DIRECTORY,
        treeUri = "content://test/tree/$sourceId",
        displayPath = "/$sourceId",
        providerLabel = null,
        displayName = null,
        recursive = true,
        mode = LayoutMode.MULTI_CHAPTER,
        orderIndex = orderIndex,
        permission = SourcePermissionState.OK,
        revision = 0,
        lastScanAt = null,
        lastScanStatus = null,
        lastScanError = null,
    )

    /** 内存来源表，真实执行 SourceDao.applyOrder 的默认实现。 */
    private class FakeSourceDao(vararg initial: LibrarySourceEntity) : SourceDao {
        private val rows = initial.associateBy { it.sourceId }.toMutableMap()

        override fun observeAll(): Flow<List<LibrarySourceEntity>> = flow { emit(getAllOrdered()) }

        override suspend fun getAllOrdered(): List<LibrarySourceEntity> =
            rows.values.sortedWith(compareBy({ it.orderIndex }, { it.sourceId }))

        override suspend fun getById(sourceId: String): LibrarySourceEntity? = rows[sourceId]
        override suspend fun maxOrderIndex(): Int = rows.values.maxOfOrNull { it.orderIndex } ?: -1

        override suspend fun upsert(entity: LibrarySourceEntity) {
            rows[entity.sourceId] = entity
        }

        override suspend fun update(entity: LibrarySourceEntity) {
            rows[entity.sourceId] = entity
        }

        override suspend fun delete(sourceId: String) {
            rows.remove(sourceId)
        }

        override suspend fun updateOrder(sourceId: String, orderIndex: Int) {
            rows[sourceId]?.let { rows[sourceId] = it.copy(orderIndex = orderIndex) }
        }

        override suspend fun updateScanResult(
            sourceId: String,
            at: Long,
            status: ScanRunStatus,
            error: String?,
        ) {
            rows[sourceId]?.let {
                rows[sourceId] = it.copy(lastScanAt = at, lastScanStatus = status, lastScanError = error)
            }
        }

        override suspend fun updatePermission(sourceId: String, permission: SourcePermissionState) {
            rows[sourceId]?.let { rows[sourceId] = it.copy(permission = permission) }
        }

        fun orderOf(): Map<String, Int> = rows.mapValues { it.value.orderIndex }
    }
}
