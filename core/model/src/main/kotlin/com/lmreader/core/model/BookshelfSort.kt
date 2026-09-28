package com.lmreader.core.model

/**
 * 书架列表的排序（用户口径：**一个全局显示的排序**，作用于"分类之后"的漫画）。
 *
 * ## 与详情页章节排序的差别
 *
 * 章节那边顺序是**数据**（用户可拖动，`chapters.position` 存着）；书架这边**不建排序表、
 * 不支持拖动**，只有一个全局偏好，读的时候现排。因此它是纯展示决策，不写任何一列
 * （用户明确要求："不需要拖动，不建每分类的排序方法，就是一个全局显示的排序"）。
 *
 * ## 为什么每种方式有自己的默认方向
 *
 * "加入时间"与"最近阅读"的自然期望都是**新的在前**（刚加进来的、刚读过的排最上面），
 * 而"名称"自然是 A→Z。若统一从升序起步，用户点"加入时间"第一眼看到的是最老的几部，
 * 得再点一次才是想要的——所以每一项第一次点击就按它自己的默认方向排，再点同一项才反向
 * （与详情页排序抽屉的交互保持一致）。
 */
enum class BookshelfSortMode {
    /** 名称：自然序（数字按数值，"第2话" 在 "第10话" 之前）。 */
    NAME,

    /** 加入书架的时间（`shelf_entries.addedAt`）。 */
    ADDED,

    /** 最近阅读时间（`reading_progress.updatedAt`）；**从没读过的一律排在后面**。 */
    READ,

    /**
     * 最新章节更新时间：该漫画**所有章节里最晚的那个**修改时间（用户口径）。
     *
     * 目录章节的 `chapters.modifiedAt` 就是**目录自身的 mtime**，归档章节是文件自身的
     * mtime，因此这个键等于"这部作品的文件最近一次是什么时候变的"——追更的人据此把
     * 刚更新过的作品排到前面。
     *
     * 它是**章节**的时间，不是作品被加入书架的时间（那是 [ADDED]），也不是我读到哪
     * （那是 [READ]）。
     */
    RECENT_CHAPTER;

    /** 第一次点击这一项时用的方向。 */
    val startsDescending: Boolean
        get() = when (this) {
            NAME -> false
            ADDED, READ, RECENT_CHAPTER -> true
        }
}

/** 书架排序设置：方式 + 方向。 */
data class BookshelfSort(
    val mode: BookshelfSortMode,
    val descending: Boolean,
) {
    companion object {
        /**
         * 默认：按名称升序。
         *
         * 选它是因为它最接近用户升级前看到的效果（那时是"源顺序 → 自然名"，
         * 同一分类内的作品在名称上也大致有序），换排序方式不会让书架突然面目全非。
         */
        val DEFAULT = BookshelfSort(
            mode = BookshelfSortMode.NAME,
            descending = BookshelfSortMode.NAME.startsDescending,
        )
    }

    /**
     * 用户在抽屉里点了某一项之后的新设置。
     *
     * 点的是**同一项** → 只反向；换了一项 → 用那一项的默认方向。
     */
    fun pick(mode: BookshelfSortMode): BookshelfSort = if (mode == this.mode) {
        copy(descending = !descending)
    } else {
        BookshelfSort(mode = mode, descending = mode.startsDescending)
    }
}
