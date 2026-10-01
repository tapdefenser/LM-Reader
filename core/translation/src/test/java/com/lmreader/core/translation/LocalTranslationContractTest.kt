package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTranslationContractTest {
    @get:Rule val temp=TemporaryFolder()
    private fun engine()=BergamotTextTranslator(TranslationModelCatalog(File("src/main/assets/translation/catalog.json").readText()),ModelPackageInstaller(temp.newFolder()))
    @Test fun identityKeepsIdsUnicodeAndSourceVerbatim()=runBlocking {
        val text="😀 𠮷 日本語\n한국어"
        val result=engine().translate(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.JAPANESE,listOf(LocalTranslationText("bubble-123",text)))
        assertEquals(LocalTranslatedText("bubble-123",text,text),result.items.single());assertTrue(result.modelPackIds.isEmpty())
    }
    @Test fun blankTextDoesNotRequireModels()=runBlocking {
        val result=engine().translate(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.CHINESE_SIMPLIFIED,listOf(LocalTranslationText("empty"," \n")))
        assertEquals(" \n",result.items.single().translatedText)
    }
    @Test fun duplicateIdsAreRejectedBeforeBackendInvocation()=runBlocking {
        try {engine().translate(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.ENGLISH,listOf(LocalTranslationText("1","a"),LocalTranslationText("1","b")));fail("duplicate IDs")}
        catch(_:IllegalArgumentException) { }
    }
    @Test fun invalidSizeNulAndIdAreRejected()=runBlocking {
        val engine=engine()
        listOf(LocalTranslationText("","text"),LocalTranslationText("1","x".repeat(4097)),LocalTranslationText("1","bad\u0000text")).forEach { item ->
            try {engine.translate(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.ENGLISH,listOf(item));fail("Invalid input")}
            catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun missingDirectionModelsFailsBeforeLoadingNativeCode()=runBlocking {
        try {engine().translate(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,listOf(LocalTranslationText("1","Hello")));fail("Missing model")}
        catch(_:IllegalArgumentException) { }
    }
}
