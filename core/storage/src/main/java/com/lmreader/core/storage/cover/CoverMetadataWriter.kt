package com.lmreader.core.storage.cover

import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ResolvedCover
import com.lmreader.core.storage.scan.MetadataBackfillWorker

/**
 * 写一次封面探测结果，并**顺手把简介也更新掉**（用户要求：在所有更新封面的时候也要更新简介）。
 *
 * ## 为什么要绑在一起
 *
 * 封面与简介读的是**同一批目录**：封面要知道"第一章是哪一章、它里面第一张图是什么"，
 * 简介要知道"第一章目录里有没有 ComicInfo.xml"。分成两条路之后，真机上的常态是
 * **卡片有封面、简介却一直是空的**——封面走滚动懒加载（每张卡片第一次可见时一次），
 * 简介走扫描期补全（每来源每轮只 120 条），几千部作品要刷十几次才轮到，
 * 而用户看到的是"图库有图、详情页没有简介"。
 *
 * 绑在一起之后，卡片第一次可见时就同时拿到封面与简介：本来就是同一次目录枚举的产物，
 * 没有理由分成两条互相等待的队列。
 *
 * ## 三个调用点走同一个函数
 *
 * 图库滚动、书架滚动、详情页（进页面与「更新章节」）。写成一份而不是三份，
 * 是为了避免"图库有简介、详情页没有"这类分叉——那正是把封面搬去懒加载时踩过的坑。
 *
 * ## 失败语义
 *
 * 两件事各自独立：简介读不到（目录里没有 XML、授权临时失效、XML 坏了）**不能**让
 * 封面这一笔算失败，反之亦然。补全内部对"没读到"会写下 `metadataProbedAt`
 * （过程），因此不会被反复重试（见 `MangaEntity.metadataProbedAt`）。
 *
 * @param at 本次探测时间；由调用方给，保证封面与简介用的是同一个时刻。
 * @param forceMetadata 已经读过的简介也重读一次。详情页「更新章节」传 true
 *   （那一章的清单刚刚被权威地枚举过，此刻的首章 XML 才是准的）；滚动懒加载传默认 false。
 */
class CoverMetadataWriter(
    private val mangaRepository: MangaRepository,
    private val metadataBackfill: MetadataBackfillWorker,
) {

    suspend fun write(
        mangaId: String,
        cover: ResolvedCover?,
        at: Long,
        forceMetadata: Boolean = false,
    ) {
        mangaRepository.markCoverProbed(
            mangaId = mangaId,
            coverDocumentId = cover?.coverDocumentId,
            coverChapterId = cover?.coverChapterId,
            at = at,
        )
        // 已经探过简介的作品在这里是空操作（`backfillOne` 自己按 metadataProbedAt 守卫），
        // 所以滚动时"封面还没取过、简介早就读过"的卡片不会多开一次目录。
        runCatching { metadataBackfill.backfillOne(mangaId, force = forceMetadata) }
    }
}
