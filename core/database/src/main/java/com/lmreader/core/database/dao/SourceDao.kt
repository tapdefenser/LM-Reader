package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.database.entity.LibrarySourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 两张路径表的读写（开发文档 4.1）。
 *
 * 排序恒定按 `orderIndex`：拖动排序是唯一即时保存的配置操作，松手即持久化，
 * 因此读取方永远看到持久化后的顺序，不需要在内存里二次排序。
 */
@Dao
interface SourceDao {

    @Query("SELECT * FROM library_sources WHERE kind = :kind ORDER BY orderIndex ASC, sourceId ASC")
    fun observeByKind(kind: SourceKind): Flow<List<LibrarySourceEntity>>

    @Query("SELECT * FROM library_sources WHERE kind = :kind ORDER BY orderIndex ASC, sourceId ASC")
    suspend fun getByKind(kind: SourceKind): List<LibrarySourceEntity>

    @Query("SELECT * FROM library_sources ORDER BY kind ASC, orderIndex ASC, sourceId ASC")
    suspend fun getAll(): List<LibrarySourceEntity>

    @Query("SELECT * FROM library_sources WHERE sourceId = :sourceId")
    suspend fun getById(sourceId: String): LibrarySourceEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM library_sources WHERE kind = :kind")
    suspend fun maxOrderIndex(kind: SourceKind): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LibrarySourceEntity)

    @Update
    suspend fun update(entity: LibrarySourceEntity)

    @Query("DELETE FROM library_sources WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)

    @Query("UPDATE library_sources SET orderIndex = :orderIndex WHERE sourceId = :sourceId")
    suspend fun updateOrder(sourceId: String, orderIndex: Int)

    @Query(
        """
        UPDATE library_sources
        SET lastScanAt = :at, lastScanStatus = :status, lastScanError = :error
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun updateScanResult(sourceId: String, at: Long, status: ScanRunStatus, error: String?)

    @Query("UPDATE library_sources SET permission = :permission WHERE sourceId = :sourceId")
    suspend fun updatePermission(sourceId: String, permission: SourcePermissionState)

    /** 拖动排序：一次事务写完整个表的顺序，避免中途被读到半旧半新的状态。 */
    @Transaction
    suspend fun applyOrder(kind: SourceKind, orderedSourceIds: List<String>) {
        // 先把超出本次列表的行推到最后，避免与本次赋值的下标冲突。
        val existing = getByKind(kind)
        val offset = orderedSourceIds.size
        existing.filter { it.sourceId !in orderedSourceIds }
            .forEachIndexed { index, entity -> updateOrder(entity.sourceId, offset + index) }
        orderedSourceIds.forEachIndexed { index, sourceId -> updateOrder(sourceId, index) }
    }
}
