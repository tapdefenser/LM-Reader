package com.lmreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("分类文风", resolveTranslationStyle(MangaTranslationSettings(), "分类文风", global))
        assertEquals(global, resolveTranslationStyle(MangaTranslationSettings(), null, global))
    }

    @Test
    fun `文风只看文本是否为空不再看模式`() {
        // 用户口径："点开就是一个输入框，如果留空就是自动应用分类"。
        // 因此"模式是跟随分类但文本非空"与"模式是自定义"结果相同——模式不再参与判断。
        val withText = MangaTranslationSettings(styleMode = StyleMode.CATEGORY, customStyle = "漫画文风")
        assertEquals("漫画文风", resolveTranslationStyle(withText, "分类文风", global))

        // 反过来：模式是自定义但文本为空，也必须继续往下回退（空文风比回退更糟）。
        val blankCustom = MangaTranslationSettings(styleMode = StyleMode.CUSTOM, customStyle = "   ")
        assertEquals("分类文风", resolveTranslationStyle(blankCustom, "分类文风", global))
        assertEquals(global, resolveTranslationStyle(blankCustom, null, global))
    }

    @Test
    fun `文风两端空白会被去掉`() {
        val manga = MangaTranslationSettings(customStyle = "  漫画文风  ")
        assertEquals("漫画文风", resolveTranslationStyle(manga, null, global))
    }

    @Test
    fun `目标语言漫画优先否则用应用语言`() {
        assertEquals("英语", resolveTargetLanguage(MangaTranslationSettings(targetLanguage = "英语"), "简体中文"))
        assertEquals("简体中文", resolveTargetLanguage(MangaTranslationSettings(), "简体中文"))
        // 空串与空白都算"没设"，回退应用语言。
        assertEquals("日语", resolveTargetLanguage(MangaTranslationSettings(targetLanguage = "  "), "日语"))
        // 即使两边都给不出东西，也不会返回空：目标语言永远有值（用户口径）。
        assertEquals(DEFAULT_TRANSLATION_TARGET, resolveTargetLanguage(MangaTranslationSettings(), "  "))
    }

    @Test
    fun `源语言的自动识别优先于手工指定`() {
        val settings = MangaTranslationSettings(sourceLanguage = "英语", autoDetectSource = true)
        assertEquals(null to true, resolveSourceLanguage(settings))
    }

    @Test
    fun `源语言只认漫画这一层`() {
        assertEquals("韩语" to false, resolveSourceLanguage(MangaTranslationSettings(sourceLanguage = "韩语")))
        // 没有全局回退：不设就是没定，由调用方拦在翻译之前（用户口径：每部漫画都得手动选）。
        assertEquals(null to false, resolveSourceLanguage(MangaTranslationSettings()))
        assertEquals(null to false, resolveSourceLanguage(MangaTranslationSettings(sourceLanguage = "  ")))
    }

    @Test
    fun `翻译选项完整性的判据只看原文语言`() {
        // 源语言要么填了、要么显式选了自动识别。
        assertTrue(translationSetupComplete(sourceLanguage = "日语", autoDetectSource = false))
        assertTrue(translationSetupComplete(sourceLanguage = null, autoDetectSource = true))
        assertFalse(translationSetupComplete(sourceLanguage = null, autoDetectSource = false))
        assertFalse(translationSetupComplete(sourceLanguage = "  ", autoDetectSource = false))
    }

    @Test
    fun `应用语言标签映射到目标语言名`() {
        assertEquals("简体中文", translationTargetForLanguageTag("zh-Hans-CN"))
        assertEquals("简体中文", translationTargetForLanguageTag("zh-CN"))
        assertEquals("简体中文", translationTargetForLanguageTag("zh"))
        assertEquals("繁体中文", translationTargetForLanguageTag("zh-Hant-TW"))
        assertEquals("繁体中文", translationTargetForLanguageTag("zh-HK"))
        assertEquals("日语", translationTargetForLanguageTag("ja-JP"))
        assertEquals("英语", translationTargetForLanguageTag("en-US"))
        assertEquals("韩语", translationTargetForLanguageTag("ko"))
        assertEquals("俄语", translationTargetForLanguageTag("ru-RU"))
        assertEquals("巴西葡语", translationTargetForLanguageTag("pt-BR"))
        // 下划线形式（部分 ROM 会给 `zh_CN`）也要认。
        assertEquals("简体中文", translationTargetForLanguageTag("zh_CN"))
        // 认不出来就回退到界面语言（应用文案目前只有中文）。
        assertEquals(DEFAULT_TRANSLATION_TARGET, translationTargetForLanguageTag("ar-EG"))
        assertEquals(DEFAULT_TRANSLATION_TARGET, translationTargetForLanguageTag(""))
    }
}
