package com.lmreader.core.translation

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Fixtures are generated public model ZIPs in this test app's private external directory. No manga access. */
@RunWith(AndroidJUnit4::class)
class LocalTranslationIntegrationTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val catalog=TranslationModelCatalog(context.assets.open("translation/catalog.json").bufferedReader().use {it.readText()})
    private val installer=ModelPackageInstaller(File(context.noBackupFilesDir,"translation-integration"))
    private val engine=BergamotTextTranslator(catalog,installer)
    @Before fun prepare()=runBlocking {
        listOf("en-zh-Hans","ja-en","ko-en").forEach { id ->
            val pack=catalog.pack(id)
            if(!installer.isInstalled(pack)) {
                val fixture=File(context.getExternalFilesDir(null),"fixtures/$id.zip")
                check(fixture.isFile) {"Push verified ZIP fixture first: ${fixture.absolutePath}"}
                fixture.inputStream().use {installer.importZip(pack,it)}
            }
        }
    }
    @After fun release()=runBlocking {engine.releaseModels()}
    private suspend fun run(language: LocalTranslationLanguage,text: String)=engine.translate(language,LocalTranslationLanguage.CHINESE_SIMPLIFIED,listOf(LocalTranslationText("bubble-1",text))).also {result ->
        Log.i("TranslationIntegration","${language.tag} -> zh-Hans: ${result.items.single().translatedText} (${result.elapsedMillis}ms) ${result.modelPackIds}")
        assertEquals("bubble-1",result.items.single().id);assertEquals(text,result.items.single().sourceText)
        assertTrue(result.items.single().translatedText.any {it in '\u4e00'..'\u9fff'})
    }
    @Test fun englishJapaneseAndKoreanProduceRealChinese()=runBlocking {
        run(LocalTranslationLanguage.ENGLISH,"The weather is nice today. Let's meet at school tomorrow.")
        assertEquals(listOf("ja-en","en-zh-Hans"),run(LocalTranslationLanguage.JAPANESE,"今日はいい天気ですね。明日、学校で会いましょう。").modelPackIds)
        assertEquals(listOf("ko-en","en-zh-Hans"),run(LocalTranslationLanguage.KOREAN,"오늘 날씨가 좋네요. 내일 학교에서 만나요.").modelPackIds)
    }
    @Test fun blankUnicodeAndIdsStayMatched()=runBlocking {
        val text="Hello 😀! Today is a good day."
        val items=listOf(LocalTranslationText("blank"," \n"),LocalTranslationText("unicode",text))
        val result=engine.translate(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,items)
        assertEquals(items.map {it.id},result.items.map {it.id});assertEquals(" \n",result.items.first().translatedText)
        assertEquals(text,result.items.last().sourceText)
        assertTrue(result.items.last().translatedText.contains("😀"));assertFalse(result.items.last().translatedText.contains('\ufffd'))
    }
    @Test fun cancellationStopsLaterItemsAndNextRequestStillWorks()=runBlocking<Unit> {
        var completed=0
        val job=launch {
            engine.translate(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                (1..8).map {LocalTranslationText("$it","Hello world.")}) { done,_ ->completed=done;cancel()}
        }
        job.join();assertTrue(job.isCancelled);assertEquals(1,completed)
        run(LocalTranslationLanguage.ENGLISH,"Good morning.")
    }
    @Test fun releaseThenReloadIsDeterministic()=runBlocking {
        val before=run(LocalTranslationLanguage.ENGLISH,"I am not going to school tomorrow.").items.single().translatedText
        engine.releaseModels()
        assertEquals(before,run(LocalTranslationLanguage.ENGLISH,"I am not going to school tomorrow.").items.single().translatedText)
    }
    @Test fun checkedNativeConfigurationErrorsDoNotAbortAndNextRequestWorks()=runBlocking<Unit> {
        TranslationRuntime.mutex.lock()
        try { withContext(TranslationRuntime.dispatcher) {
            val pack=catalog.pack("en-zh-Hans")
            val config=BergamotTextTranslator.configuration(pack,installer.installedDirectory(pack)).replace("mini-batch-words: 1024","mini-batch-words: 1")
            try {BergamotNative.load("invalid-test".toByteArray(),config.toByteArray());fail("Must report invalid batching")}
            catch(failure:IllegalStateException) {assertTrue(failure.message.orEmpty().contains("mini-batch-words"))}
        } } finally {TranslationRuntime.mutex.unlock()}
        run(LocalTranslationLanguage.ENGLISH,"This is a small test.")
    }
    @Test fun longTextIncludesFinalSentence()=runBlocking<Unit> {
        val input="The weather is nice today. ".repeat(24)+"The rabbit is blue."
        val result=run(LocalTranslationLanguage.ENGLISH,input).items.single().translatedText
        assertTrue(result,result.contains("兔"));assertTrue(result,result.contains("蓝"))
    }
}
