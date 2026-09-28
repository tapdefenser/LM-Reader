package com.lmreader.core.model

/**
 * 翻译相关的领域模型（阶段 2 的数据层；引擎本身是 P3）。
 *
 * ## 为什么先有数据层
 *
 * 详情页的多选底栏要放「翻译所选 / 清除翻译文本」，点下去必须有**真实效果**
 * （开发文档 17：不存在仅摆放未接线的核心控件）。这两件事的语义都落在数据上：
 * 入队 = 写入一条待翻译记录（侧栏「翻译队列」的计数因此会动）；
 * 清除 = 删掉译文并在记录上退回「待翻译」。引擎接上来时只要读这些记录就能开工。
 *
 * ## 语言为什么是自由字符串
 *
 * 用户口径："我们要能够甚至支持任意语言的翻译"，且选择列表末尾允许自己输入。
 * 存 `zh`/`ja` 这类代码会迫使我们维护一张语言表，而用户输入的语言名（例如
 * 「乌克兰语」）根本不在任何预设表里。因此这里存**用户看到/输入的那串文字**。
 *
 * 已知代价：目标语言是译名字典的键的一半（[GlossaryEntry]），把语言名改一个字就是
 * 另一套字典。这一步不做归并——静默把两套字典合并比让它们分开更危险。
 */

/** 一章的翻译记录。 */
data class ChapterTranslation(
    val chapterId: String,
    val mangaId: String,
    val targetLanguage: String,
    val state: TranslationState,
    /** 入队时的源语言快照；null + [autoDetectSource] = 当时要求自动识别。 */
    val sourceLanguage: String?,
    val autoDetectSource: Boolean,
    /** 入队时的有效配置快照（JSON）：队列不因用户之后改设置而改变行为。 */
    val configSnapshot: String?,
    val queuedAt: Long?,
    val translatedAt: Long?,
    /** 已保存的译文条数；「清除翻译文本」会把它和 [translatedAt] 一起抹掉。 */
    val translatedCount: Int,
    val failure: String?,
    val updatedAt: Long,
) {
    /** 界面上是否显示「待翻译」徽标。 */
    val pending: Boolean get() = state == TranslationState.PENDING || state == TranslationState.RUNNING
}

/** 漫画译名字典的一条（开发文档 TR09）。 */
data class GlossaryEntry(
    val mangaId: String,
    val targetLanguage: String,
    /** 原词，同一语言下是去重键。 */
    val source: String,
    val target: String,
    /**
     * true = 用户手工录入；false = 自动新增。
     *
     * 自动流程只能新增或更新 [manual] 为 false 的条目，**人工值不被自动覆盖**（TR09）。
     */
    val manual: Boolean,
    val updatedAt: Long,
)

/**
 * 一次入队的请求（「翻译所选」与「全部翻译」共用）。
 *
 * [configSnapshot] 是**入队那一刻**解析出来的有效配置：翻译方式、源/目标语言、文风。
 * 队列带着快照走，用户随后改设置不会让已排队的任务换一种翻法——这是"每部漫画一套
 * 独立翻译方式"能成立的前提，也是开发文档"队列包含有效配置快照"的要求。
 */
data class TranslationRequest(
    val targetLanguage: String,
    val sourceLanguage: String?,
    val autoDetectSource: Boolean,
    val configSnapshot: String?,
    val at: Long,
)

/**
 * 漫画级翻译设置（跟着漫画走，见用户口径）。
 *
 * 每一维都可以"留空"：留空 = 用上一层（分类 → 全局）。见 [resolveTranslationStyle]。
 */
data class MangaTranslationSettings(
    /** null = 跟随全局默认源语言。 */
    val sourceLanguage: String? = null,
    /** true = 自动识别源语言（优先于 [sourceLanguage]）。 */
    val autoDetectSource: Boolean = false,
    /** null = 跟随全局默认目标语言。 */
    val targetLanguage: String? = null,
    /** null 或非 [StyleMode.CUSTOM] = 不用漫画自己的文风，往下走分类与全局。 */
    val styleMode: StyleMode? = null,
    val customStyle: String? = null,
)

/**
 * 文风解析：**覆盖**关系，而不是继承链（用户口径）。
 *
 * "如果漫画设置了就用漫画的，如果漫画的留空了就用分类的，如果分类的还留空就用全局的"。
 * 因此判据是"这一层**显式**写了自定义文本"，而不是"这一层的 mode 是 CUSTOM"——
 * 选了自定义却把文本清空，等于没设置，应该继续往下回退，而不是得到一段空文风
 * （空文风会让模型收到一个空指令，比回退更糟）。
 */
fun resolveTranslationStyle(
    manga: MangaTranslationSettings,
    categoryStyle: String?,
    globalStyle: String,
): String = when {
    manga.styleMode == StyleMode.CUSTOM && !manga.customStyle.isNullOrBlank() ->
        manga.customStyle.trim()

    !categoryStyle.isNullOrBlank() -> categoryStyle.trim()

    else -> globalStyle
}

/**
 * 有效目标语言：漫画没设就用全局默认。
 *
 * 目标语言**没有**"自动识别"这一说（识别的是源语言），所以这里只有一层回退。
 */
fun resolveTargetLanguage(manga: MangaTranslationSettings, globalTarget: String): String =
    manga.targetLanguage?.takeIf { it.isNotBlank() }?.trim() ?: globalTarget

/** 有效源语言：自动识别优先；否则漫画设置优先，最后回退全局。 */
fun resolveSourceLanguage(
    manga: MangaTranslationSettings,
    globalSource: String,
): Pair<String?, Boolean> = when {
    manga.autoDetectSource -> null to true
    !manga.sourceLanguage.isNullOrBlank() -> manga.sourceLanguage.trim() to false
    else -> globalSource to false
}
