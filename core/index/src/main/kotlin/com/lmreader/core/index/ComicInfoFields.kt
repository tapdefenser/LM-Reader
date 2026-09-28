package com.lmreader.core.index

import java.util.Locale

/**
 * 两侧上游 ComicInfo 的字段语义映射（开发文档 7、上游参考与复用边界 2/4）。
 *
 * ## 为什么需要单独一层
 *
 * EhViewer 与 Mihon/Tachiyomi 写出的都是合法 ComicInfo，但**同名字段的业务含义不同**，
 * 而且各自都缺对方有的字段。只按名字取值会出现两类错误：
 *
 * | 字段 | Mihon / Tachiyomi | EhViewer |
 * |---|---|---|
 * | `Summary` | 漫画简介（`manga.description`） | **不写** |
 * | `Writer` | 作者（`manga.author`） | **社团 / 同人圈**（`groups`） |
 * | `Penciller` | 画师（`manga.artist`） | **作者 / 画师**（`artists`） |
 * | `AlternateSeries` | 不写 | 日文原名（`titleJpn`） |
 * | `Genre` | 分类字符串 | 其它标签，女性/男性向带 `f:`/`m:` 前缀 |
 * | `Characters` / `Teams` | 不写 | 角色 / 原作（parody） |
 * | `PageCount` / `LanguageISO` / `CommunityRating` | 不写 | 页数 / 语言 / 评分 |
 * | `PublishingStatusTachiyomi` / `Categories` / `SourceMihon` | 写 | 不写 |
 *
 * 因此"作者"不能一律取 `Writer`：EhViewer 的 `Writer` 是社团，取它会把
 * 「i-raf-you」当成画师，而真正的画师在 `Penciller`。
 *
 * ## 判据为什么看字段集合而不是命名空间
 *
 * 命名空间前缀（`ty:` / `mh:`）是**写法**而不是**来源**：手写或第三方工具生成的
 * XML 不带前缀却是 Mihon 语义。两侧真正互斥的是**字段集合**——Mihon 的模型里
 * 根本没有 `PageCount`/`LanguageISO`/`Characters`/`Teams`/`CommunityRating`/
 * `AlternateSeries`，EhViewer 的模型里也没有三个 Mihon 扩展字段。用它们判定，
 * 既不依赖前缀，也不依赖属性声明。
 *
 * 判不出来时（只有 `Series`+`Writer` 这类最小 XML）按 Mihon 语义处理：它是
 * ComicInfo 的标准写法，也是两侧里更"通用"的一侧。
 */
object ComicInfoFields {

    /** 写出这份 ComicInfo 的上游。 */
    enum class Flavor {
        /** Mihon / Tachiyomi：`Writer`=作者，`Summary`=简介，带 `ty:`/`mh:` 扩展字段。 */
        TACHIYOMI,

        /** EhViewer：`Penciller`=作者/画师，`Writer`=社团，无 `Summary`。 */
        EHVIEWER,

        /** 判不出来；按 Mihon 语义处理（标准写法）。 */
        UNKNOWN,
    }

    /**
     * Mihon/Tachiyomi 独有字段（`tachiyomi/core-metadata` 的 `ComicInfo` 模型）。
     *
     * `Translator`/`Year`/`Month`/`Day`/`Tags` 也在 Mihon 的模型里而没有出现在
     * EhViewer 的模型里，一并作为判据。
     */
    private val TACHIYOMI_MARKERS = setOf(
        "PublishingStatusTachiyomi", "Categories", "SourceMihon",
        "Translator", "Tags", "Year", "Month", "Day",
    )

    /**
     * EhViewer 独有字段（`com.hippo.ehviewer.spider.ComicInfo` 的模型）。
     *
     * 注意 `AlternateSeries`/`PageCount`/`Characters`/`Teams`/`CommunityRating`
     * 是 ComicInfo v2 的标准字段，Mihon **不写**，因此在这个判据里它们是
     * "非 Mihon"的证据。
     */
    private val EHVIEWER_MARKERS = setOf(
        "AlternateSeries", "PageCount", "LanguageISO", "Characters", "Teams", "CommunityRating",
    )

    /** 判定写出这份 XML 的上游。 */
    fun flavor(fields: Map<String, String>): Flavor {
        val names = fields.keys.map { it.lowercase(Locale.ROOT) }.toHashSet()
        return when {
            TACHIYOMI_MARKERS.any { it.lowercase(Locale.ROOT) in names } -> Flavor.TACHIYOMI
            EHVIEWER_MARKERS.any { it.lowercase(Locale.ROOT) in names } -> Flavor.EHVIEWER
            else -> Flavor.UNKNOWN
        }
    }

    /**
     * 「作者」显示值（详情页「作者：…」与卡片）。
     *
     * - Mihon/Tachiyomi：`Writer`（manga.author），缺失时退回 `Penciller`；
     * - EhViewer：`Penciller`（作者/画师），缺失时退回 `Writer`（社团）。
     *
     * 缺失返回 null，由界面显示「未知」（开发文档 2）。
     */
    fun author(fields: Map<String, String>): String? = when (flavor(fields)) {
        Flavor.EHVIEWER -> value(fields, "Penciller") ?: value(fields, "Writer")
        Flavor.TACHIYOMI, Flavor.UNKNOWN -> value(fields, "Writer") ?: value(fields, "Penciller")
    }

    /** EhViewer 的社团（`Writer`）；Mihon 语义下它等同于作者，因此不重复展示。 */
    fun group(fields: Map<String, String>): String? =
        value(fields, "Writer").takeIf { flavor(fields) == Flavor.EHVIEWER }

    /**
     * `Summary` 缺失时的**结构化**简介（开发文档 7「Summary 优先，缺失可显示文本摘要」）。
     *
     * 为什么不是直接把 XML 压成一行文本：EhViewer 的 XML 里 `Series`/`Web`/`PageCount`
     * 混在一起，压缩后是一句没有标签的字符串（标签、URL、页码连成一串），
     * 卡片上两行根本读不出是什么。这里按"读者想先知道什么"排序成带标签的行：
     * 别名 → 作者 → 社团 → 分类 → 角色 → 原作 → 语言/页数/评分 → 来源 → 名称。
     *
     * `excludeName` 传漫画的展示名：`Series` 与它相同时不重复一行"名称"——
     * 用户已经在标题上看到了，卡片的两行预览不该被它占掉（EhViewer 的 `Series`
     * 就是目录名，必然重复）。
     *
     * 一个可用的字段都没有时返回 null，调用方回退到压缩 XML 文本。
     */
    fun describe(fields: Map<String, String>, excludeName: String? = null): String? {
        val lines = buildList {
            value(fields, "AlternateSeries")?.let { add("别名：$it") }
            author(fields)?.let { add("作者：$it") }
            group(fields)?.let { add("社团：$it") }
            value(fields, "Genre")?.let { add("分类：${stripTagPrefixes(it)}") }
            value(fields, "Characters")?.let { add("角色：${stripTagPrefixes(it)}") }
            value(fields, "Teams")?.let { add("原作：${stripTagPrefixes(it)}") }
            metaLine(fields)?.let { add("信息：$it") }
            value(fields, "Web")?.let { add("来源：$it") }
            seriesName(fields, excludeName)?.let { add("名称：$it") }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * EhViewer 的女性/男性向标签带 `f:`/`m:` 命名空间前缀（`TagNamespace.prefix`）。
     *
     * 去掉前缀但保留标签本身：前缀在 EhViewer 里表达"这是女性向标签"，
     * 在只有一行的简介里没有信息量，而 `f:yuri` 这种写法对读者是噪声。
     */
    private fun stripTagPrefixes(text: String): String =
        text.split(',').joinToString(", ") { tag ->
            tag.trim().removePrefix("f:").removePrefix("m:").trim()
        }.trim()

    /** 「语言 · N 页 · 评分 X」；三项都没有时返回 null。 */
    private fun metaLine(fields: Map<String, String>): String? {
        val parts = buildList {
            value(fields, "LanguageISO")?.let { add(it) }
            value(fields, "PageCount")?.let { add("$it 页") }
            value(fields, "CommunityRating")?.let { add("评分 $it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * `Series`（以及与之不同的 `Title`）里的名称。
     *
     * `Series` 与漫画展示名相同时不返回；`Title` 只在和 `Series` 不同时才补一行——
     * Mihon 下载的章节 XML 里 `Title` 是**章节名**（如「Chapter 1」），
     * 与 `Series`（作品名）不同，值得保留。
     */
    private fun seriesName(fields: Map<String, String>, excludeName: String?): String? {
        val series = value(fields, "Series")
        val title = value(fields, "Title")?.takeIf { !it.equals(series, ignoreCase = true) }
        val names = listOfNotNull(series, title)
            .filterNot { it.equals(excludeName?.trim(), ignoreCase = true) }
        return names.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** 大小写不敏感地取字段值；空串按缺失处理（与 [ComicInfoParser.fieldValue] 同一规则）。 */
    private fun value(fields: Map<String, String>, name: String): String? =
        ComicInfoParser.fieldValue(fields, name)
}
