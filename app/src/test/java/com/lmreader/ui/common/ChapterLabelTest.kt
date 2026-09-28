package com.lmreader.ui.common

import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡片上的章节文案。
 *
 * 守两条用户口径：
 * 1. "共一章的就不要显示章节数了"——单章节模式一个数字都不写；
 * 2. "不要做成已发现一章，更新中，这会产生歧义"——章节数**数出来之前什么都不显示**，
 *    而不是拿发现阶段的下限（永远是 1）当章节数。
 */
class ChapterLabelTest {

    private fun card(
        layoutMode: LayoutMode = LayoutMode.MULTI_CHAPTER,
        chapterCount: Int? = 1,
        chapterCountKnown: Boolean = false,
        countedChapterCount: Int? = null,
        countedChapterCountAt: Long? = null,
    ) = MangaCard(
        mangaId = "m1",
        displayName = "作品",
        summaryPreview = null,
        sourceId = "s1",
        coverDocumentId = null,
        coverChapterId = null,
        coverProbedAt = null,
        sourceKind = SourceKind.IMAGE_DIRECTORY,
        layoutMode = layoutMode,
        chapterCount = chapterCount,
        chapterCountKnown = chapterCountKnown,
        countedChapterCount = countedChapterCount,
        countedChapterCountAt = countedChapterCountAt,
        inShelf = false,
        availability = MangaAvailability.AVAILABLE,
    )

    @Test
    fun `单章节模式不显示章节数`() {
        assertNull(chapterLabel(card(layoutMode = LayoutMode.SINGLE_CHAPTER, chapterCountKnown = true)))
        // 连"数过一次"的情况也不显示：那个 1 是结构定义，没有信息量。
        assertNull(
            chapterLabel(
                card(
                    layoutMode = LayoutMode.SINGLE_CHAPTER,
                    countedChapterCount = 1,
                    countedChapterCountAt = 42L,
                ),
            ),
        )
    }

    @Test
    fun `章节清单完整时用完整值`() {
        assertEquals("共 12 章", chapterLabel(card(chapterCount = 12, chapterCountKnown = true)))
    }

    @Test
    fun `滚动数过之后显示数出来的值`() {
        assertEquals(
            "共 12 章",
            chapterLabel(card(countedChapterCount = 12, countedChapterCountAt = 42L)),
        )
    }

    @Test
    fun `完整值优先于滚动计数值`() {
        assertEquals(
            "共 12 章",
            chapterLabel(
                card(
                    chapterCount = 12,
                    chapterCountKnown = true,
                    countedChapterCount = 99,
                    countedChapterCountAt = 42L,
                ),
            ),
        )
    }

    @Test
    fun `还没数过就什么都不显示（不再写已发现一章更新中）`() {
        // 发现阶段的下限：chapterCount = 1 但 known = false，且没数过。
        assertNull(chapterLabel(card(chapterCount = 1, chapterCountKnown = false)))
        // 数过但没数出可读章节：也不显示。
        assertNull(chapterLabel(card(countedChapterCount = null, countedChapterCountAt = 42L)))
    }
}
