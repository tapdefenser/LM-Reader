package com.lmreader.ui.reader.translation

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.translation.*
import com.lmreader.core.vision.LocalVisionEngine
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** All input pages are generated under this test's private folder; no manga or queue is read. */
@RunWith(AndroidJUnit4::class)
class LocalPageTranslationIntegrationTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var engine: LocalPageTranslator
    private lateinit var store: ReaderPageArtifactStore
    private val page=ReaderPage("page-fixture-1",0,"fixture.png","fixture.png")
    private lateinit var source: PageSource
    private lateinit var input: File
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        root=File(context.cacheDir,"page-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        input=File(root,"fixture.png")
        val fallback=TranslationModelCatalog(context.assets.open("translation/catalog.json").bufferedReader().use {it.readText()})
        val installer=ModelPackageInstaller(File(context.noBackupFilesDir,"translation-models"))
        val packs=installer.knownPackages(fallback.packs).filter(installer::isInstalled)
        assertTrue("Install an English to simplified Chinese pack before this test",packs.any {it.source==LocalTranslationLanguage.ENGLISH && it.target==LocalTranslationLanguage.CHINESE_SIMPLIFIED})
        val translator=BergamotTextTranslator(TranslationModelCatalog.fromPacks(packs),installer)
        store=ReaderPageArtifactStore(File(root,"results"))
        engine=LocalPageTranslator(context,LocalVisionEngine(context),translator,store) {packs}
        source=object: PageSource {
            override suspend fun pages()=listOf(page)
            override suspend fun open(page: ReaderPage)=input.inputStream()
            override suspend fun probe(page: ReaderPage)=PageGeometry(800,1000)
        }
    }
    @After fun removeOnlyOwnFixture() {
        if (::engine.isInitialized) runBlocking { engine.releaseModels() }
        check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile);root.deleteRecursively()
    }
    private fun fixture(blank: Boolean=false) {
        val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
        if(!blank) {
            val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;strokeWidth=5f;style=Paint.Style.STROKE}
            canvas.drawRect(20f,20f,780f,980f,pen);canvas.drawOval(100f,100f,700f,460f,pen)
            canvas.drawLine(470f,454f,520f,530f,pen);canvas.drawLine(520f,530f,560f,446f,pen)
            canvas.drawCircle(400f,720f,100f,pen)
            val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=43f;typeface=Typeface.DEFAULT_BOLD}
            canvas.drawText("HELLO WORLD",205f,270f,text);canvas.drawText("GOOD MORNING",210f,945f,text)
            canvas.drawText("HI",365f,735f,text)
        }
        input.outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
    }
    @Test fun realOcrTranslationMaskAndSettingsReuseSavedText() = runBlocking {
        fixture();val original=input.readBytes()
        val result=engine.translate(source,page,LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,BubbleRenderSettings())
        Log.i("PageIntegration","Grouped text: ${result.regions.map {"${it.region.kind} ${it.region.sourceText} -> ${it.translatedText}; contour ${it.region.contour.size}"}}")
        assertTrue(result.regions.toString(),result.regions.any {it.region.sourceText.replace(" ","").contains("HELLOWORLD")})
        assertTrue(result.regions.toString(),result.regions.any {it.region.kind==RegionKind.BUBBLE && it.region.contour.size>=3})
        assertTrue(result.regions.all {it.translatedText.isNotBlank()});assertTrue(result.regions.any {it.translatedText!=it.region.sourceText})
        assertArrayEquals(original,input.readBytes());assertTrue(result.dataFile.isFile)
        assertTrue(store.root.walkTopDown().none {it.extension=="png"})
        val savedBytes=result.dataFile.readBytes()
        assertEquals(result.revision,engine.cached(source,page,BubbleRenderSettings())!!.revision)
        val redraw=engine.cached(source,page,BubbleRenderSettings(BubbleFillMode.WHITE,50,12))!!
        assertEquals(result.regions,redraw.regions);assertEquals(result.revision,redraw.revision)
        assertArrayEquals(savedBytes,result.dataFile.readBytes())
        assertArrayEquals(original,input.readBytes())
        val edited=store.saveEdits(result,result.regions.map {it.copy(translatedText="Edited locally")})
        assertEquals(edited.regions,engine.cached(source,page,BubbleRenderSettings())!!.regions)
        input.writeBytes(original+byteArrayOf(0));assertNull(engine.cached(source,page,BubbleRenderSettings()))
    }
    @Test fun cancellationDoesNotPublishPartialOrErasePreviousTranslation() = runBlocking {
        fixture()
        val before=engine.translate(source,page,LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,BubbleRenderSettings())
        val work=launch {engine.translate(source,page,LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,BubbleRenderSettings()) {
            if(it.stage==PageTranslationStage.OCR) cancel()
        }}
        work.join();assertTrue(work.isCancelled)
        assertEquals(before.revision,store.load(page.pageId,ReaderPageArtifactStore.hashFile(input))!!.revision)
        assertTrue(store.root.listFiles().orEmpty().none {it.name.startsWith("source-")})
    }
    @Test fun blankAndForeignPageCannotReuseTranslation() = runBlocking {
        fixture(true)
        val result=engine.translate(source,page,LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,BubbleRenderSettings())
        assertTrue(result.regions.isEmpty());assertTrue(result.modelPacks.isEmpty())
        assertNull(store.load("another-page",result.sourceSha256))
        assertTrue(result.dataFile.isFile);assertTrue(store.root.walkTopDown().none {it.extension=="png"})
    }
    @Test fun bubblePipelinePersistsRegionsWithoutWritingImages() = runBlocking {
        fixture()
        val original = input.readBytes()
        val result = engine.translate(source, page, LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED, BubbleRenderSettings(),
            mode = TranslationPageMode.BUBBLE)
        assertTrue(result.regions.toString(), result.regions.isNotEmpty())
        assertTrue(result.regions.any { it.region.kind == RegionKind.BUBBLE })
        assertTrue(result.regions.any { it.region.sourceText.contains("HELLO") })
        assertArrayEquals(original, input.readBytes())
        assertNotNull(engine.cached(source, page, BubbleRenderSettings()))
    }
}
