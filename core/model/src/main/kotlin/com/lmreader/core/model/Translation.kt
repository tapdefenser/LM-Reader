package com.lmreader.core.model

import java.util.Locale

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

/**
 * 漫画译名字典的一条（用户口径：**只和漫画有关，与语言无关**）。
 *
 * ## 为什么不按目标语言分区
 *
 * 上游规格（`docs/翻译配置与上游对照.md` TR09）写的是"按 mangaId+targetLanguage 维护"，
 * 因此第一版把它做成了按语言分套的字典。用户看过之后明确否掉了：他要的是"这部作品的
 * 译名字典"，多一层语言维度只让界面多出一个他从不关心的"正在编辑：简体中文"标题。
 *
 * **已知代价**：同一部作品翻成两种语言时，字典是同一份——把某词定成"简中译名"，
 * 换成英语目标语言时它仍然生效。用户只翻一种语言时这没有影响；真要同时翻多种语言，
 * 需要把这一维加回来（那就是一次迁移 + 界面加一个切换器）。
 */
data class GlossaryEntry(
    val mangaId: String,
    /** 原词，同一部作品内是去重键。 */
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
 * 漫画级翻译设置（**「翻译选项」页面上的东西**，跟着漫画走）。
 *
 * 与**应用级**的翻译设置（设置里的那一套：主 AI、OCR、模式、全局文风；P3）区分开：
 * 这一层只回答"这部作品要怎么翻"。
 *
 * 两维语言的规则不同，别把它们写成同一个形状：
 *
 * - **原文语言：没有全局默认这一说**（用户口径："每个新的漫画都必须得手动选"）。
 *   它是这部作品的属性，替用户猜一个默认值等于让整章译文悄悄跑偏。
 * - **目标语言：全局默认就是应用现在使用的语言**（用户口径）。因此它总有值，
 *   漫画这一层只是"我这部想翻成别的"的覆盖。
 */
data class MangaTranslationSettings(
    /** null = 还没为这部作品选过原文语言。**必填**，不继承任何上层。 */
    val sourceLanguage: String? = null,
    /** true = 自动识别原文语言（优先于 [sourceLanguage]，两者互斥）。 */
    val autoDetectSource: Boolean = false,
    /** null = 用应用当前语言（见 [translationTargetForLanguageTag]）。 */
    val targetLanguage: String? = null,
    /** null 或非 [StyleMode.CUSTOM] = 不用漫画自己的文风，往下走分类与全局。 */
    val styleMode: StyleMode? = null,
    val customStyle: String? = null,
)

/**
 * 文风解析：**覆盖**关系（用户口径："留空就自动应用分类"）。
 *
 * 判据只看**文本本身是否为空**，不再看 `StyleMode`：用户要的是"这个框留空就往下退化"，
 * 多一个"用自定义 / 跟随分类"的单选只是把同一件事说了两遍（而且会出现"选了自定义却
 * 留空"这种自相矛盾的状态）。因此：
 *
 * 1. 漫画的文本非空 → 用它；
 * 2. 否则分类的文本非空 → 用它；
 * 3. 否则全局默认。
 *
 * [MangaTranslationSettings.styleMode] 保留在数据里（旧行还在），但**不再参与判断**。
 */
fun resolveTranslationStyle(
    manga: MangaTranslationSettings,
    categoryStyle: String?,
    globalStyle: String,
): String = when {
    !manga.customStyle.isNullOrBlank() -> manga.customStyle.trim()
    !categoryStyle.isNullOrBlank() -> categoryStyle.trim()
    else -> globalStyle
}

/**
 * 有效目标语言：漫画没设就用**应用现在使用的语言**（用户口径："目标语言全局默认
 * 直接是应用现在使用的语言"）。
 *
 * 因此它**不会返回 null**：目标语言永远有个可用的值，用户不需要为它做任何设置。
 * （这与原文语言相反——那个必填，见 [resolveSourceLanguage]。）
 */
fun resolveTargetLanguage(manga: MangaTranslationSettings, appLanguage: String): String =
    manga.targetLanguage?.takeIf { it.isNotBlank() }?.trim()
        ?: appLanguage.takeIf { it.isNotBlank() }?.trim()
        ?: DEFAULT_TRANSLATION_TARGET

/**
 * 有效原文语言：自动识别优先；否则只认漫画这一层。
 *
 * **没有全局回退**（用户口径："翻译的原文语言没有全局默认这一说，每个新的漫画都必须得
 * 手动选"）。都没设时返回 `null to false` = 还没定，由调用方拦在翻译之前。
 */
fun resolveSourceLanguage(manga: MangaTranslationSettings): Pair<String?, Boolean> = when {
    manga.autoDetectSource -> null to true
    !manga.sourceLanguage.isNullOrBlank() -> manga.sourceLanguage.trim() to false
    else -> null to false
}

/**
 * 翻译选项是否完整到可以排队（**只看原文语言**）。
 *
 * 目标语言总有值（[resolveTargetLanguage]），所以这里不再检查它。用户口径是
 * "如果用户要进行翻译，自动弹出翻译选项，然后给一个消息提示，然后手动重新启动翻译"，
 * 因此这里回答的是**能不能开始**：原文语言要么填了、要么显式选了自动识别。
 */
fun translationSetupComplete(
    sourceLanguage: String?,
    autoDetectSource: Boolean,
): Boolean = autoDetectSource || !sourceLanguage.isNullOrBlank()

/** 目标语言的兜底值：应用语言无法识别时用它（界面语言是中文，见下）。 */
const val DEFAULT_TRANSLATION_TARGET = "简体中文"

/**
 * 应用界面语言的 BCP-47 标签 → 翻译目标语言名。
 *
 * 用户口径："目标语言全局默认直接是应用现在使用的语言"。所以这里是一张**小映射表**，
 * 而不是让用户再填一次"我要翻成什么语言"。
 *
 * 两个刻意的选择：
 *
 * 1. 认不出来的标签回退到 [DEFAULT_TRANSLATION_TARGET]（简体中文）：应用的界面文案
 *    目前只有中文，用户看到的就是中文；将来 P5 加了"应用语言"并且真的做了多语言
 *    界面之后，这里改成跟随那个设置，才能名副其实。
 * 2. 只在语言名上与界面预设保持一致（"巴西葡语"而不是"葡萄牙语"），因为这个名字
 *    会直接进翻译请求。
 */
fun translationTargetForLanguageTag(languageTag: String): String {
    val normalized = languageTag.replace('_', '-').lowercase(Locale.ROOT)
    val language = normalized.substringBefore('-')
    return when (language) {
        "zh" -> if (
            normalized.contains("hant") ||
            normalized.contains("-tw") ||
            normalized.contains("-hk") ||
            normalized.contains("-mo")
        ) {
            "繁体中文"
        } else {
            "简体中文"
        }

        "ja" -> "日语"
        "en" -> "英语"
        "ko" -> "韩语"
        "ru" -> "俄语"
        "pt" -> "巴西葡语"
        "fr" -> "法语"
        "de" -> "德语"
        "es" -> "西班牙语"
        "it" -> "意大利语"
        else -> DEFAULT_TRANSLATION_TARGET
    }
}
