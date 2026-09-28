package com.lmreader.core.index

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节显示顺序（开发文档 8.1）。
 *
 * 覆盖三件事：三种排序方式的**整表重排**（含正逆向）、**新章节插入**（选了排序方式 vs
 * 从没选过）、以及"用户手动拖过之后新章节依然按已保存的方式插入、且不动已有顺序"
 * （用户口径 2026-09-28）。
 */
class ChapterOrderingTest {

    private fun chapter(
        title: String,
        position: Long = 0L,
        modifiedAt: Long? = null,
        id: String? = null,
    ) = ChapterRecord(
        chapterId = id ?: "c_$title",
        mangaId = "m1",
        documentId = "/lib/Manga/$title",
        kind = ChapterKind.IMAGE_DIRECTORY,
        title = title,
        sortKey = NaturalOrder.sortKey(title),
        position = position,
        modifiedAt = modifiedAt,
        pageCount = null,
        coverDocumentId = null,
        contentRevision = 1L,
        discoveredAt = 0L,
    )

    private fun titles(list: List<ChapterRecord>) = list.map { it.title }

    private fun positions(list: List<ChapterRecord>) = list.map { it.position }

    @Test
    fun `自然数字排序把 1 2 10 11 100 排成数字序`() {
        val chapters = listOf("第10章", "第2章", "第100章", "第1章", "第11章").map { chapter(it) }

        val sorted = ChapterOrdering.resort(chapters, ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false))

        assertEquals(listOf("第1章", "第2章", "第10章", "第11章", "第100章"), titles(sorted))
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), positions(sorted))
    }

    @Test
    fun `首字母排序是纯字典序 第100 排在第11 之前`() {
        val chapters = listOf("第10章", "第2章", "第100章", "第1章", "第11章").map { chapter(it) }

        val sorted = ChapterOrdering.resort(chapters, ChapterOrdering.Setting(ChapterOrdering.Mode.ALPHA, false))

        // 字典序下 "第100章" < "第10章" < "第11章" < "第1章" < "第2章"
        assertEquals(listOf("第100章", "第10章", "第11章", "第1章", "第2章"), titles(sorted))
    }

    @Test
    fun `逆向就是把正向反过来`() {
        val chapters = listOf("第1章", "第2章", "第10章").map { chapter(it) }

        val forward = ChapterOrdering.resort(chapters, ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false))
        val backward = ChapterOrdering.resort(chapters, ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, true))

        assertEquals(titles(forward).reversed(), titles(backward))
    }

    @Test
    fun `按修改时间排序把没有时间的排在最后`() {
        val chapters = listOf(
            chapter("无时间", modifiedAt = null),
            chapter("旧", modifiedAt = 100L),
            chapter("新", modifiedAt = 300L),
            chapter("中", modifiedAt = 200L),
        )

        val sorted = ChapterOrdering.resort(chapters, ChapterOrdering.Setting(ChapterOrdering.Mode.MODIFIED, false))

        assertEquals(listOf("旧", "中", "新", "无时间"), titles(sorted))
    }

    @Test
    fun `从没选过排序方式时新章节追加到末尾且批内按首字母`() {
        val existing = listOf("第1章", "第2章").mapIndexed { index, title -> chapter(title, index.toLong()) }
        val discovered = existing + listOf("第10章", "第3章").map { chapter(it) }

        val planned = ChapterOrdering.planInsertion(
            existing = existing,
            discovered = discovered,
            setting = ChapterOrdering.Setting.DEFAULT,
        )

        // 批内首字母：第10章 在 第3章 之前（字典序）。
        assertEquals(listOf("第1章", "第2章", "第10章", "第3章"), titles(planned))
        assertEquals(listOf(0L, 1L, 2L, 3L), positions(planned))
    }

    @Test
    fun `选过自然数字时新章节插到正确位置而不是末尾`() {
        val existing = listOf("第1章", "第2章", "第10章")
            .mapIndexed { index, title -> chapter(title, index.toLong()) }
        val discovered = existing + chapter("第3章")

        val planned = ChapterOrdering.planInsertion(
            existing = existing,
            discovered = discovered,
            setting = ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false),
        )

        assertEquals(listOf("第1章", "第2章", "第3章", "第10章"), titles(planned))
    }

    @Test
    fun `手动拖乱之后新章节按已保存方式插入且已有顺序一个都不动`() {
        // 用户把 第10章 拖到了最前面（displayOrder 就是库里的 position 顺序）。
        val displayOrder = listOf("第10章", "第1章", "第2章")
            .mapIndexed { index, title -> chapter(title, index.toLong()) }
        val discovered = displayOrder + chapter("第3章")

        val planned = ChapterOrdering.planInsertion(
            existing = displayOrder,
            discovered = discovered,
            setting = ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false),
        )

        // 已有三章相对顺序不变；第3章 落在它的自然邻居（第2章）之后。
        assertEquals(listOf("第10章", "第1章", "第2章", "第3章"), titles(planned))
    }

    @Test
    fun `逆向时新章节按逆向插入`() {
        val existing = listOf("第100章", "第10章", "第1章")
            .mapIndexed { index, title -> chapter(title, index.toLong()) }
        val discovered = existing + chapter("第50章")

        val planned = ChapterOrdering.planInsertion(
            existing = existing,
            discovered = discovered,
            setting = ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, true),
        )

        // 逆向下 100 > 50 > 10 > 1，所以 第50章 落在 第100章 之后。
        assertEquals(listOf("第100章", "第50章", "第10章", "第1章"), titles(planned))
    }

    @Test
    fun `没有新章节时原样返回`() {
        val existing = listOf("第1章", "第2章").mapIndexed { index, title -> chapter(title, index.toLong()) }

        val planned = ChapterOrdering.planInsertion(
            existing = existing,
            discovered = existing,
            setting = ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false),
        )

        assertEquals(existing, planned)
    }

    @Test
    fun `发现阶段只报一章时不会动已有顺序`() {
        // 真机形态：普通「刷新」每部只发出一个章节，而库里可能有 60 章（用户点过更新章节）。
        val existing = (1..5).map { chapter("第${it}章", (it - 1).toLong()) }
        val discovered = listOf(existing[0])

        val planned = ChapterOrdering.planInsertion(
            existing = existing,
            discovered = discovered,
            setting = ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false),
        )

        assertEquals(titles(existing), titles(planned))
    }

    @Test
    fun `插入下标在空列表上是零`() {
        val comparator = ChapterOrdering.comparator(ChapterOrdering.Setting(ChapterOrdering.Mode.NATURAL, false))

        assertEquals(0, ChapterOrdering.insertionIndex(emptyList(), chapter("第1章"), comparator))
    }

    @Test
    fun `比较器用 chapterId 兜底保证顺序确定`() {
        // 同名两章（不同目录下同名）在字典序与自然序下都相等，必须还有稳定的次序。
        val comparator = ChapterOrdering.comparator(ChapterOrdering.Setting(ChapterOrdering.Mode.ALPHA, false))
        val left = chapter("同名", id = "c_a")
        val right = chapter("同名", id = "c_b")

        assertTrue(comparator.compare(left, right) < 0)
        assertTrue(comparator.compare(right, left) > 0)
    }

    // ---- 拖动落点与让位（真机发现的缺陷：没有让位时几行文字叠在一起） ------------

    @Test
    fun `落点按半格进位并夹在列表范围内`() {
        assertEquals(2, ChapterOrdering.dragTarget(from = 0, offsetRows = 2.4f, lastIndex = 9))
        assertEquals(3, ChapterOrdering.dragTarget(from = 0, offsetRows = 2.5f, lastIndex = 9))
        assertEquals(0, ChapterOrdering.dragTarget(from = 2, offsetRows = -5f, lastIndex = 9))
        assertEquals(9, ChapterOrdering.dragTarget(from = 8, offsetRows = 99f, lastIndex = 9))
        assertEquals(-1, ChapterOrdering.dragTarget(from = -1, offsetRows = 1f, lastIndex = 9))
    }

    @Test
    fun `往下拖时源与落点之间的行上移一格`() {
        val from = 1
        val to = 4

        // 被拖的行自己不让位（它跟着手指）。
        assertEquals(0, ChapterOrdering.dragDisplacement(index = from, from = from, to = to))
        // 中间的三行各上移一格。
        for (index in 2..4) {
            assertEquals(-1, ChapterOrdering.dragDisplacement(index, from, to))
        }
        // 落点之外的行不动。
        assertEquals(0, ChapterOrdering.dragDisplacement(0, from, to))
        assertEquals(0, ChapterOrdering.dragDisplacement(5, from, to))
    }

    @Test
    fun `往上拖时中间的行下移一格`() {
        val from = 4
        val to = 1

        assertEquals(1, ChapterOrdering.dragDisplacement(3, from, to))
        assertEquals(1, ChapterOrdering.dragDisplacement(2, from, to))
        assertEquals(1, ChapterOrdering.dragDisplacement(1, from, to))
        // 下标 0 在落点之外（落点本身是 1），不动。
        assertEquals(0, ChapterOrdering.dragDisplacement(0, from, to))
        assertEquals(0, ChapterOrdering.dragDisplacement(5, from, to))
    }

    @Test
    fun `没在拖动或落点就是原位时所有行都不让位`() {
        for (index in 0..5) {
            assertEquals(0, ChapterOrdering.dragDisplacement(index, from = -1, to = -1))
            assertEquals(0, ChapterOrdering.dragDisplacement(index, from = 3, to = 3))
        }
    }

    @Test
    fun `让位与落点组合起来正好把被拖的行移进空位`() {
        // [A,B,C,D,E]，把 A（0）拖到 2 之后：B、C 上移一格，A 落在 2 号位。
        val from = 0
        val to = 2
        val shifts = (0..4).map { ChapterOrdering.dragDisplacement(it, from, to) }
        assertEquals(listOf(0, -1, -1, 0, 0), shifts)
    }
}
