package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lmreader.core.database.entity.DirectorySnapshotEntity
import com.lmreader.core.database.entity.ScanRunEntity
import com.lmreader.core.model.ScanRunStatus

/**
 * 扫描运行与目录快照（开发文档 6.2）。
 *
 * 快照只记录"这个容器是否被完整枚举过"，成员集合从 `mangas/chapters` 反查。
 * 判定删除时必须以快照为准：权限丢失或中断的容器没有完整快照，其中的成员
 * 消失不算删除（验收 A07）。
 */
@Dao
interface ScanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(entity: ScanRunEntity)

    @Query("SELECT * FROM scan_runs WHERE sourceId = :sourceId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestRun(sourceId: String): ScanRunEntity?

    @Query("UPDATE scan_runs SET status = :status, finishedAt = :finishedAt, mangasFound = :mangasFound, error = :error WHERE generation = :generation")
    suspend fun finishRun(
        generation: Long,
        status: ScanRunStatus,
        finishedAt: Long,
        mangasFound: Int,
        error: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(entities: List<DirectorySnapshotEntity>)

    @Query("SELECT * FROM directory_snapshots WHERE sourceId = :sourceId AND documentId = :documentId")
    suspend fun snapshot(sourceId: String, documentId: String): DirectorySnapshotEntity?

    @Query("SELECT documentId FROM directory_snapshots WHERE sourceId = :sourceId")
    suspend fun snapshotDocumentIds(sourceId: String): List<String>

    /** 配置版本变化后旧快照不再可信，整源清理（开发文档 6.2「配置版本未变」）。 */
    @Query("DELETE FROM directory_snapshots WHERE sourceId = :sourceId")
    suspend fun clearSnapshots(sourceId: String)

    /**
     * 一次扫描结束后写入运行状态并登记完整枚举过的容器。
     *
     * 放在同一事务里：如果运行状态写成 COMPLETED 但快照没写成功，下一轮就会把
     * 整源当成"从未完整枚举"，永远不敢清理已删除章节。
     */
    @Transaction
    suspend fun commitRun(
        run: ScanRunEntity,
        completedContainers: List<DirectorySnapshotEntity>,
    ) {
        upsertRun(run)
        if (completedContainers.isNotEmpty()) upsertSnapshots(completedContainers)
    }
}
