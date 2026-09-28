package com.lmreader.core.index

import com.lmreader.core.model.ChapterRecord
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 章节显示顺序（开发文档 8.1 的章节列表）。
 *
 * ## 模型：`position` 是唯一真相，排序方式是"怎么插新章节"的规则
 *
 * 每章在库里有一个显示位置（`chapters.position`）。三种排序方式不是"读的时候现排"，
 * 而是两个动作：
 *
 * 1. **用户在抽屉里点了某个排序方式** → 整表按它重排一次并把结果写回 `position`
 *    （这是"排序"这个动作本身的意义，也是把拖乱的列表整理回来的唯一手段）；
 * 2. **扫描发现新章节** → 不重排整表，而是把新章节**插到该方式对应的位置**上，
 *    已有章节一个都不动（[planInsertion]）。
 *
 * ## 为什么新章节是"插入"而不是"追加到末尾"
 *
 * 用户口径（2026-09-28）："用户勾选的排序方式也给保存了，那么就会根据用户勾选的
 * 方式排序；如果用户手动拖动过章节顺序，依然是根据用户勾选的方式进行插入
 * （不改变用户的操作）"。用 [planInsertion] 的"最后一个不比它大的项之后"规则同时满足
 * 这两句：列表本来就有序时结果与整表重排等价；列表被拖乱时新章落在它的自然邻居旁边，
 * 而已有项的相对顺序完全不变。
 *
 * 用户从未选过排序方式时（[ChapterSortSetting.mode] == null）退回最初的口径：
 * 追加到末尾，同一批新章节之间按**首字母**（字典序）排。
 */
object ChapterOrdering {

    /** 章节排序方式（排序抽屉里的三项）。 */
    enum class Mode {
        /** 首字母：纯字典序（不认数字大小，"第100章" 排在 "第11章" 前）。 */
        ALPHA,

        /** 按修改时间：目录/归档文件的 mtime。 */
        MODIFIED,

        /** 自然数字：认数字大小，1 < 2 < 10 < 100（框架 4.3 的 [NaturalOrder]）。 */
        NATURAL,
    }

    /**
     * 用户保存的排序方式。
     *
     * [mode] 为 null = **从未选过**：新章节追加到末尾（批内首字母）。
     * [descending] 是方向：同一项在抽屉里点一次正向、再点一次逆向。
     */
    data class Setting(
        val mode: Mode?,
        val descending: Boolean,
    ) {
        companion object {
            /** 默认：从没选过排序方式，新章节追加到末尾。 */
            val DEFAULT = Setting(mode = null, descending = false)
        }
    }

    /** 当前排序方式的来源；由 app 层按用户的持久化偏好提供（数据库层不认识 DataStore）。 */
    fun interface SettingProvider {
        suspend fun current(): Setting
    }

    /**
     * 比较器：按 [setting] 比较两章；相同时用 `chapterId` 兜底，保证顺序确定
     * （否则每次扫描得到的顺序可能不同，列表会自己抖动）。
     */
    fun comparator(setting: Setting): Comparator<ChapterRecord> {
        val base = when (setting.mode) {
            Mode.ALPHA -> Comparator<ChapterRecord> { left, right ->
                left.title.lowercase(Locale.ROOT).compareTo(right.title.lowercase(Locale.ROOT))
            }

            Mode.MODIFIED -> Comparator<ChapterRecord> { left, right ->
                // 没有 mtime 的排在有 mtime 的后面（而不是当成 0 = 1970 排到最前）。
                val leftAt = left.modifiedAt
                val rightAt = right.modifiedAt
                when {
                    leftAt == null && rightAt == null -> 0
                    leftAt == null -> 1
                    rightAt == null -> -1
                    else -> leftAt.compareTo(rightAt)
                }
            }

            Mode.NATURAL, null -> Comparator<ChapterRecord> { left, right ->
                NaturalOrder.compare(left.title, right.title)
            }
        }
        val directed = if (setting.descending) base.reversed() else base
        return directed.thenBy { it.chapterId }
    }

    /**
     * 整表重排：按 [setting] 排好并给出新的位置（下标即 `position`）。
     *
     * 用户点排序方式时调用；会覆盖手动拖动过的顺序（用户已确认这是期望行为）。
     */
    fun resort(chapters: List<ChapterRecord>, setting: Setting): List<ChapterRecord> =
        chapters.sortedWith(comparator(setting)).mapIndexed { index, chapter ->
            chapter.copy(position = index.toLong())
        }

    /**
     * 计划一次扫描发现之后的章节顺序。
     *
     * @param existing 该漫画**当前显示顺序**下的已有章节（按 `position`）。
     * @param discovered 本次扫描发现的全部章节（含已有与新增）。顺序不作为依据。
     * @return 全部章节的最终显示顺序（`position` 已重写为下标）。
     *
     * 规则：
     * - 已有章节保持它们当前的相对顺序，**一个都不动**；
     * - 新章节按 [setting] 插入：插在"已经排在它后面之前、最后一个不比它大"的项之后；
     *   列表本身有序时这就等于排到正确位置；
     * - 从未选过排序方式时新章节一律追加到末尾，同一批之间按首字母（用户最初的口径）；
     * - 同一批多个新章节：先按 [setting]（或首字母）排好，再逐个插入。
     */
    fun planInsertion(
        existing: List<ChapterRecord>,
        discovered: List<ChapterRecord>,
        setting: Setting,
    ): List<ChapterRecord> {
        val existingIds = existing.mapTo(HashSet()) { it.chapterId }
        val fresh = discovered.filter { it.chapterId !in existingIds }
        if (fresh.isEmpty()) return existing

        val comparator = comparator(setting)
        val orderedFresh = when (setting.mode) {
            null -> fresh.sortedWith(alphaComparator())
            else -> fresh.sortedWith(comparator)
        }

        val result = ArrayList<ChapterRecord>(existing.size + orderedFresh.size)
        result += existing
        for (chapter in orderedFresh) {
            val at = if (setting.mode == null) {
                result.size
            } else {
                insertionIndex(result, chapter, comparator)
            }
            result.add(at, chapter)
        }
        return result.mapIndexed { index, chapter -> chapter.copy(position = index.toLong()) }
    }

    /**
     * 新章节该插在哪个下标。
     *
     * "最后一个不比它大的项之后"：列表有序时即标准插入位置；列表被手动拖乱时，
     * 新章落在它的自然邻居旁边，而已有项的相对顺序不变。
     */
    fun insertionIndex(
        current: List<ChapterRecord>,
        incoming: ChapterRecord,
        comparator: Comparator<ChapterRecord>,
    ): Int {
        var index = 0
        for ((position, chapter) in current.withIndex()) {
            if (comparator.compare(chapter, incoming) <= 0) index = position + 1
        }
        return index
    }

    /** 首字母（字典序）比较器；用于"从未选过排序方式"时的批内排序。 */
    private fun alphaComparator(): Comparator<ChapterRecord> {
        val byTitle = Comparator<ChapterRecord> { left, right ->
            left.title.lowercase(Locale.ROOT).compareTo(right.title.lowercase(Locale.ROOT))
        }
        return byTitle.thenBy { it.chapterId }
    }

    // ---- 拖动时的落点与让位（纯算术，与 Compose 无关，便于单测） ------------------

    /**
     * 拖动落点：手指移过 [offsetRows] 格（可为小数，半格进位）之后的最终下标。
     *
     * 夹在 `0..lastIndex` 内：拖出列表两端时应该停在第一/最后一格，而不是把下标算到界外
     * （那样落库时会写进一个不存在的下标）。
     */
    fun dragTarget(from: Int, offsetRows: Float, lastIndex: Int): Int {
        if (from < 0 || lastIndex < 0) return -1
        return (from + offsetRows.roundToInt()).coerceIn(0, lastIndex)
    }

    /**
     * 拖动时第 [index] 行要额外位移几格（正值向下）。
     *
     * - 被拖的那一行返回 0：它由手指位置直接决定，不参与让位；
     * - 落点在下方时，**源与落点之间**的行整体上移一格（-1）空出落点；
     * - 落点在上方时，中间的行整体下移一格（+1）；
     * - 其余行不动。
     *
     * 这三条是用户在真机上发现的缺陷的核心：没有让位时被拖的行浮在上面、其它行不动，
     * 看起来就是几行文字叠在一起。
     */
    fun dragDisplacement(index: Int, from: Int, to: Int): Int = when {
        from < 0 || to < 0 || index == from -> 0
        to > from && index in (from + 1)..to -> -1
        to < from && index in to until from -> 1
        else -> 0
    }
}
