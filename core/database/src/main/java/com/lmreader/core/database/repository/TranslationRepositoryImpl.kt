package com.lmreader.core.database.repository

import com.lmreader.core.database.dao.TranslationDao
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.entity.MangaGlossaryEntity
import com.lmreader.core.model.ChapterTranslation
import com.lmreader.core.model.GlossaryEntry
import com.lmreader.core.model.TranslationRepository
import com.lmreader.core.model.TranslationRequest
import com.lmreader.core.model.TranslationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 翻译数据仓储（阶段 2）。
 *
 * ## 这里的两个"真的会变"
 *
 * - [enqueue] 真的写待翻译记录：侧栏「翻译队列」的角标（[observePendingCount]）会动；
 * - [clearTranslations] 真的抹掉译文条数与时间戳，并把状态退回待翻译
 *   （用户口径："都没译文了不得待翻译"）。
 *
 * 于是详情页多选底栏的那两个按钮**不是**占位控件（开发文档 17）。
 *
 * ## 为什么按章节而不是按页记录
 *
 * 「翻译所选」与「全部翻译」都是按章勾选的（详情页的章节列表就是章），所以队列的
 * 最小单位是章。将来页级/气泡级译文挂在章记录之下即可，不需要改这张表的主键。
 */
internal class TranslationRepositoryImpl(
    private val dao: TranslationDao,
) : TranslationRepository {

    override suspend fun chapterTranslations(
        mangaId: String,
        targetLanguage: String,
    ): Map<String, ChapterTranslation> = dao.byManga(mangaId, targetLanguage)
        .associate { it.chapterId to it.toDomain() }

    /**
     * 入队。
     *
     * 已经在 `RUNNING`/`DONE`/`FAILED` 的章节**不动**：重复点「翻译所选」不该把已经
     * 翻完的作品退回队列重翻（那会让用户白等一遍，还可能覆盖掉人工修订过的译文）。
     * 从"没有记录"或"待翻译"进来的才写。
     */
    override suspend fun enqueue(
        mangaId: String,
        chapterIds: List<String>,
        request: TranslationRequest,
    ): Int {
        if (chapterIds.isEmpty()) return 0
        // 一次查询取齐这批章节在**本目标语言**下的现有状态：多选可以一次勾上百章，
        // 逐章查会变成上百次往返。
        val states = dao.byChapters(chapterIds)
            .filter { it.targetLanguage == request.targetLanguage }
            .associateBy { it.chapterId }

        val toWrite = chapterIds.mapNotNull { chapterId ->
            val current = states[chapterId]
            // 已在翻译中/已完成/已失败的都不动：重复点「翻译所选」不该把翻完的作品退回
            // 队列重翻（用户要白等一遍，还可能盖掉人工修订过的译文）。
            if (current != null && current.state != TranslationState.PENDING.name) return@mapNotNull null
            ChapterTranslationEntity(
                chapterId = chapterId,
                mangaId = mangaId,
                targetLanguage = request.targetLanguage,
                state = TranslationState.PENDING.name,
                sourceLanguage = request.sourceLanguage,
                autoDetectSource = request.autoDetectSource,
                configSnapshot = request.configSnapshot,
                queuedAt = request.at,
                // 入队不动已有译文：用户可能"翻了一半再点一次翻译所选"。
                translatedAt = current?.translatedAt,
                translatedCount = current?.translatedCount ?: 0,
                failure = null,
                updatedAt = request.at,
            )
        }
        if (toWrite.isEmpty()) return 0
        dao.upsertAll(toWrite)
        return toWrite.size
    }

    /**
     * 清除翻译文本。
     *
     * 用户口径是"取消排队 + 删除译文 + 增加待翻译标记，都没译文了不得待翻译"，
     * 所以这里**不删行**，而是把行改写成一条干净的待翻译记录：译文条数与时间戳归零、
     * 失败原因清空、重新排队。删行会丢掉"这一章还需要翻"的意图。
     */
    override suspend fun clearTranslations(
        mangaId: String,
        chapterIds: List<String>,
        targetLanguage: String,
    ): Int {
        if (chapterIds.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val current = dao.byManga(mangaId, targetLanguage).associateBy { it.chapterId }
        val toWrite = chapterIds.map { chapterId ->
            val existing = current[chapterId]
            ChapterTranslationEntity(
                chapterId = chapterId,
                mangaId = mangaId,
                targetLanguage = targetLanguage,
                state = TranslationState.PENDING.name,
                sourceLanguage = existing?.sourceLanguage,
                autoDetectSource = existing?.autoDetectSource ?: false,
                configSnapshot = existing?.configSnapshot,
                queuedAt = now,
                // 这一笔就是"删除译文"：条数与完成时间一起抹掉。
                translatedAt = null,
                translatedCount = 0,
                failure = null,
                updatedAt = now,
            )
        }
        dao.upsertAll(toWrite)
        return toWrite.size
    }

    override fun observePendingCount(): Flow<Int> = dao.observePending().map { it.size }

    override suspend fun glossary(mangaId: String, targetLanguage: String): List<GlossaryEntry> =
        dao.glossary(mangaId, targetLanguage).map { it.toDomain() }

    /**
     * 写一条译名。
     *
     * **人工值不被自动覆盖**（TR09）：如果表里已有一条 `manual = true` 而这次要写的是
     * 自动值，直接忽略——否则自动抽取的词表会把用户逐字校对过的译名冲掉。
     */
    override suspend fun upsertGlossary(entry: GlossaryEntry) {
        val source = entry.source.trim()
        val target = entry.target.trim()
        if (source.isEmpty() || target.isEmpty()) return
        if (!entry.manual) {
            val existing = dao.glossary(entry.mangaId, entry.targetLanguage)
                .firstOrNull { it.source == source }
            if (existing?.manual == true) return
        }
        dao.upsertGlossary(
            listOf(
                MangaGlossaryEntity(
                    mangaId = entry.mangaId,
                    targetLanguage = entry.targetLanguage,
                    source = source,
                    target = target,
                    manual = entry.manual,
                    updatedAt = entry.updatedAt,
                ),
            ),
        )
    }

    override suspend fun deleteGlossary(mangaId: String, targetLanguage: String, source: String) {
        dao.deleteGlossary(mangaId, targetLanguage, source)
    }
}

private fun ChapterTranslationEntity.toDomain(): ChapterTranslation = ChapterTranslation(
    chapterId = chapterId,
    mangaId = mangaId,
    targetLanguage = targetLanguage,
    // 数据库里是外部输入（升级、手工改动都可能写入未知值），失配时退回 PENDING
    // 而不是抛异常：界面显示"待翻译"远好于详情页打不开。
    state = TranslationState.entries.firstOrNull { it.name == state } ?: TranslationState.PENDING,
    sourceLanguage = sourceLanguage,
    autoDetectSource = autoDetectSource,
    configSnapshot = configSnapshot,
    queuedAt = queuedAt,
    translatedAt = translatedAt,
    translatedCount = translatedCount,
    failure = failure,
    updatedAt = updatedAt,
)

private fun MangaGlossaryEntity.toDomain(): GlossaryEntry = GlossaryEntry(
    mangaId = mangaId,
    targetLanguage = targetLanguage,
    source = source,
    target = target,
    manual = manual,
    updatedAt = updatedAt,
)
