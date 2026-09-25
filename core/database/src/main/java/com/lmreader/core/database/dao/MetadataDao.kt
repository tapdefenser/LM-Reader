package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.MetadataEntity
import com.lmreader.core.model.MetadataOwnerType

/**
 * ComicInfo 记录读写（开发文档 7、6.4）。
 *
 * 章节 XML 与漫画级投影是两条记录：`ownerId` 复用同一主键列，用 [MetadataOwnerType]
 * 区分，这样"漫画用第一章 XML 做简介"时可以直接读到第一章那一条而不必复制文本。
 */
@Dao
interface MetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetadataEntity)

    @Query("SELECT * FROM metadata_records WHERE ownerId = :ownerId")
    suspend fun getByOwner(ownerId: String): MetadataEntity?

    @Query("SELECT * FROM metadata_records WHERE ownerId IN (:ownerIds)")
    suspend fun getByOwners(ownerIds: List<String>): List<MetadataEntity>

    @Query("SELECT * FROM metadata_records WHERE ownerType = :ownerType")
    suspend fun getByType(ownerType: MetadataOwnerType): List<MetadataEntity>

    @Query("DELETE FROM metadata_records WHERE ownerId = :ownerId")
    suspend fun delete(ownerId: String)

    /**
     * 是否需要重新解析。
     *
     * 开发文档 6.2 要求 XML 变化能被发现，因此比较内容指纹而不是时间/大小：
     * 同一时间同一长度的描述改动否则会被漏掉。
     */
    @Query(
        """
        SELECT COUNT(*) FROM metadata_records
        WHERE ownerId = :ownerId AND fingerprint = :fingerprint
        """,
    )
    suspend fun hasSameFingerprint(ownerId: String, fingerprint: String): Int
}
