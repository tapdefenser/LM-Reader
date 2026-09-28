package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.ChapterReadStateEntity

/**
 * 按章的已读标记（开发文档 15.3：用户状态与索引分表）。
 *
 * 只存"用户标记过"的章节：没有行的章节就是未读，**不写 `read = 0` 的行**——
 * 一部 500 章的作品里用户只标了 3 章，为其余 497 章写行只会让表无意义地变大。
 * 因此 [setRead] 在 `read = false` 时是 DELETE。
 */
@Dao
interface ChapterReadStateDao {

    @Query("SELECT chapterId, read FROM chapter_read_state WHERE mangaId = :mangaId")
    suspend fun marksOf(mangaId: String): List<ChapterReadMarkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChapterReadStateEntity>)

    @Query("DELETE FROM chapter_read_state WHERE chapterId IN (:chapterIds)")
    suspend fun deleteAll(chapterIds: List<String>)
}

/** 已读标记投影。 */
data class ChapterReadMarkRow(
    val chapterId: String,
    val read: Boolean,
)
