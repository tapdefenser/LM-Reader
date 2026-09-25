package com.lmreader.core.model

/**
 * 图库/书架的展示方式（开发文档 8.1「展示方式」）。
 *
 * 两种方式都按 40 项增长，切换展示方式不重新扫描、不改变会话顺序。
 */
enum class LibraryDisplayMode {
    /** 封面列表：封面 + 名称 + 简介预览。 */
    LIST,

    /** 紧凑网格：只显示封面与名称。 */
    GRID,
}
