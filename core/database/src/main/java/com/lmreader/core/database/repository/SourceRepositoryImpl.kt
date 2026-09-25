package com.lmreader.core.database.repository

import androidx.room.withTransaction
import com.lmreader.core.database.LmReaderDatabase
import com.lmreader.core.database.dao.SourceDao
import com.lmreader.core.database.entity.toDomain
import com.lmreader.core.database.entity.toEntity
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 两张路径表的仓储实现（开发文档 4.1）。
 *
 * 这里有一条不显眼但重要的规则：**保存来源时 revision 必须自增**。扫描是异步的，
 * 用户可能在扫描途中把「多章节」改成「单章节」，如果 revision 不变，正在跑的旧
 * 扫描结果会覆盖新配置（验收 A09）。自增本身在这里做，调用方不需要记得。
 */
internal class SourceRepositoryImpl(
    private val database: LmReaderDatabase,
    private val dao: SourceDao,
) : SourceRepository {

    private val mangaDao = database.mangaDao()

    override fun observeSources(): Flow<List<LibrarySource>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getSources(): List<LibrarySource> =
        dao.getAllOrdered().map { it.toDomain() }

    override suspend fun getSource(sourceId: String): LibrarySource? = dao.getById(sourceId)?.toDomain()

    override suspend fun saveSource(source: LibrarySource): LibrarySource {
        val existing = dao.getById(source.sourceId)
        val toSave = when {
            existing == null -> source.copy(
                orderIndex = if (source.orderIndex >= 0) {
                    source.orderIndex
                } else {
                    dao.maxOrderIndex() + 1
                },
                revision = 1,
            )

            else -> source.copy(
                // 顺序永远以数据库为准：拖动排序是唯一即时保存的操作，调用方
                // 不该用可能过期的内存顺序覆盖它。
                orderIndex = existing.orderIndex,
                revision = existing.revision + 1,
            )
        }
        dao.upsert(toSave.toEntity())
        return toSave
    }

    override suspend fun deleteSource(sourceId: String) {
        // 先把这个来源的卡片标成陈旧，再删来源行，同一个事务里完成：
        // 卡片行只有在来源行还在时才能按 sourceId 定位；顺序反了就只能等下一次
        // 启动的"孤儿清扫"补救。标记而不是删除，是为了让用户把目录加回来以后
        // 卡片、书架关系、阅读进度与译文原样回来（同一套逻辑见 MangaDao 的说明）。
        database.withTransaction {
            mangaDao.markSourceAsStale(sourceId)
            dao.delete(sourceId)
        }
    }

    override suspend fun reorder(orderedSourceIds: List<String>) {
        // `library_sources.orderIndex` 是配置，`mangas.sourceOrderIndex` 是图库排序用的
        // 冗余投影。两者必须在同一个事务里落盘，避免路径表与图库顺序永久分叉。
        database.withTransaction {
            // 与 SourceDao.applyOrder 用同一套下标规则：调用方给出的 ID 按下标 0..n-1，
            // 未出现在本次列表里的来源行推到表尾并从 n 开始编号。
            val existing = dao.getAllOrdered()
            val offset = orderedSourceIds.size
            val omitted = existing.filter { it.sourceId !in orderedSourceIds }
            dao.applyOrder(orderedSourceIds)
            orderedSourceIds.forEachIndexed { index, sourceId ->
                mangaDao.updateSourceOrder(sourceId, index)
            }
            omitted.forEachIndexed { index, entity ->
                mangaDao.updateSourceOrder(entity.sourceId, offset + index)
            }
        }
    }

    override suspend fun updateScanResult(
        sourceId: String,
        at: Long,
        status: ScanRunStatus,
        error: String?,
    ) {
        dao.updateScanResult(sourceId, at, status, error)
    }

    override suspend fun updatePermission(sourceId: String, permission: SourcePermissionState) {
        dao.updatePermission(sourceId, permission)
    }
}
