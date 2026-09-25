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
 * 路径表的读写（开发文档 4.1）。
 *
 * 排序恒定按 `orderIndex`：拖动排序是唯一即时保存的配置操作，松手即持久化，
 * 因此读取方永远看到持久化后的顺序，不需要在内存里二次排序。
 *
 * 一次遍历同时解释图片与归档之后（见 `StructureScanner`），界面只剩**一张**路径表，
 * 因此这里不再按 `kind` 分作用域；`kind` 只作为来源的身份与徽标保留。
 * 已知代价：合并前两张表各有自己的 0..n 序列，合并后可能出现重复的 `orderIndex`；
 * 重复时由 `sourceId` 兜底保证顺序稳定，用户拖动一次即会重排成唯一序列。
 */
@Dao
interface SourceDao {

    @Query("SELECT * FROM library_sources ORDER BY orderIndex ASC, sourceId ASC")
    fun observeAll(): Flow<List<LibrarySourceEntity>>

    @Query("SELECT * FROM library_sources ORDER BY orderIndex ASC, sourceId ASC")
    suspend fun getAllOrdered(): List<LibrarySourceEntity>

    @Query("SELECT * FROM library_sources WHERE sourceId = :sourceId")
    suspend fun getById(sourceId: String): LibrarySourceEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM library_sources")
    suspend fun maxOrderIndex(): Int

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
    suspend fun applyOrder(orderedSourceIds: List<String>) {
        // 先把超出本次列表的行推到最后，避免与本次赋值的下标冲突。
        val existing = getAllOrdered()
        val offset = orderedSourceIds.size
        existing.filter { it.sourceId !in orderedSourceIds }
            .forEachIndexed { index, entity -> updateOrder(entity.sourceId, offset + index) }
        orderedSourceIds.forEachIndexed { index, sourceId -> updateOrder(sourceId, index) }
    }
}
