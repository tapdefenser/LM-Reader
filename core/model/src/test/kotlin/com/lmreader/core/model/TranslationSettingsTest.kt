package com.lmreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文风与语言的解析（用户口径：**覆盖**关系，不是继承链）。
 *
 * "如果漫画设置了就用漫画的，如果漫画的留空了就用分类的，如果分类的还留空就用全局的"。
 * 判据是"这一层**显式**写了自定义文本"，所以"选了自定义但文本清空"必须继续往下回退——
 * 得到一段空文风比回退更糟（模型会收到一条空指令）。
 */
class TranslationSettingsTest {

    private val global = "全局默认文风"

    @Test
    fun `漫画写了自定义就用漫画的`() {
        val manga = MangaTranslationSettings(styleMode = StyleMode.CUSTOM, customStyle = "漫画文风")
        assertEquals("漫画文风", resolveTranslationStyle(manga, "分类文风", global))
    }

    @Test
    fun `漫画留空则用分类的`() {
        val manga = MangaTranslationSettings(styleMode = StyleMode.CATEGORY, customStyle = null)
        assertEquals("分类文风", resolveTranslationStyle(manga, "分类文风", global))

        // 从没设置过（mode = null）同样落到分类。
        assertEquals("分类文风", resolveTranslationStyle(MangaTranslationSettings(), "分类文风", global))
    }

    @Test
    fun `分类也留空则用全局`() {
        assertEquals(global, resolveTranslationStyle(MangaTranslationSettings(), null, global))
        assertEquals(global, resolveTranslationStyle(MangaTranslationSettings(), "   ", global))
    }

    @Test
    fun `选了自定义但文本为空要继续往下回退`() {
        val blank = MangaTranslationSettings(styleMode = StyleMode.CUSTOM, customStyle = "   ")
        assertEquals("分类文风", resolveTranslationStyle(blank, "分类文风", global))
        assertEquals(global, resolveTranslationStyle(blank, null, global))
    }

    @Test
    fun `文风两端空白会被去掉`() {
        val manga = MangaTranslationSettings(styleMode = StyleMode.CUSTOM, customStyle = "  漫画文风  ")
        assertEquals("漫画文风", resolveTranslationStyle(manga, null, global))
    }

    @Test
    fun `目标语言漫画优先否则回退全局`() {
        assertEquals("英语", resolveTargetLanguage(MangaTranslationSettings(targetLanguage = "英语"), "简体中文"))
        assertEquals("简体中文", resolveTargetLanguage(MangaTranslationSettings(), "简体中文"))
        // 只有空白等于没设置。
        assertEquals(
            "简体中文",
            resolveTargetLanguage(MangaTranslationSettings(targetLanguage = "  "), "简体中文"),
        )
    }

    @Test
    fun `源语言的自动识别优先于手工指定`() {
        val settings = MangaTranslationSettings(sourceLanguage = "英语", autoDetectSource = true)
        assertEquals(null to true, resolveSourceLanguage(settings, "日语"))
    }

    @Test
    fun `源语言漫画优先否则回退全局`() {
        assertEquals("韩语" to false, resolveSourceLanguage(MangaTranslationSettings(sourceLanguage = "韩语"), "日语"))
        assertEquals("日语" to false, resolveSourceLanguage(MangaTranslationSettings(), "日语"))
    }
}
