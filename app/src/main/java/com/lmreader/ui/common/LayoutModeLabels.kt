package com.lmreader.ui.common

import com.lmreader.core.model.LayoutMode

/**
 * 解释方式的展示文案（**唯一来源**）。
 *
 * 为什么集中在这里：类型列的下拉、卡片徽标、图源筛选栏三处都要显示它，各自写一份
 * `when` 迟早会不一致。这里的三个函数都是**穷尽 when**（没有 else），因此将来往
 * [LayoutMode] 里加"混合"这类新值时，编译会直接指出需要补文案的地方，
 * 不会出现"界面上少了一个选项"这种静默遗漏。
 *
 * 选项列表本身直接遍历 [LayoutMode.entries]（见 `SourceTable` 的下拉），
 * 所以新增枚举值不需要改界面代码，只需要：枚举值 + 扫描器支持 + 这里的文案。
 */
fun LayoutMode.displayName(): String = when (this) {
    LayoutMode.MULTI_CHAPTER -> "多章节"
    LayoutMode.SINGLE_CHAPTER -> "单章节"
}

/** 下拉项里的一句话说明：用户选它之前要能看懂它会把目录解释成什么。 */
fun LayoutMode.description(): String = when (this) {
    LayoutMode.MULTI_CHAPTER -> "子文件夹分别是章节，父文件夹名是漫画名"
    LayoutMode.SINGLE_CHAPTER -> "每个有图片的文件夹各自是一本共 1 章的漫画"
}

/** 行内下拉按钮上的短标签（类型列很窄，放不下全称）。 */
fun LayoutMode.shortName(): String = when (this) {
    LayoutMode.MULTI_CHAPTER -> "多章"
    LayoutMode.SINGLE_CHAPTER -> "单章"
}
