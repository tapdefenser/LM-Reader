package com.lmreader.core.database.repository

import com.lmreader.core.database.dao.ReadingProgressDao
import com.lmreader.core.database.entity.toDomain
import com.lmreader.core.database.entity.toEntity
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository

internal class ReadingProgressRepositoryImpl(
    private val dao: ReadingProgressDao,
) : ReadingProgressRepository {
    override suspend fun get(mangaId: String): ReadingProgress? = dao.get(mangaId)?.toDomain()

    override suspend fun save(progress: ReadingProgress) {
        dao.upsert(progress.toEntity())
    }
}
