package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lmreader.core.database.entity.ReadingProgressEntity

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE mangaId = :mangaId")
    suspend fun get(mangaId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadingProgressEntity)
}
