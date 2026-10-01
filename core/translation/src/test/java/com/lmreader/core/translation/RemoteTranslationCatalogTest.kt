package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class RemoteTranslationCatalogTest {
    private lateinit var server:MockWebServer
    private var records="{}"
    private var custom="{}"
    private val sha="a".repeat(40)
    private val requests=mutableListOf<String>()
    private var badPage=false
    @Before fun setup() {
        server=MockWebServer();server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.requestUrl!!.encodedPath;requests+=path
            val body=when {
                path=="/v1/" -> """{"capabilities":{"attachments":{"base_url":"${server.url("/files/")}"}}}"""
                path=="/records" -> records
                path=="/catalog.json" -> custom
                path=="/api/models/test/models" -> """{"sha":"$sha"}"""
                path=="/api/models/test/models/tree/$sha" -> tree(if(request.requestUrl!!.queryParameter("cursor")==null) "de" to "en" else "en" to "id")
                path.endsWith("base-memory/deen/metadata.json") -> """{"sourceLanguage":"de","targetLanguage":"en"}"""
                path.endsWith("base-memory/enid/metadata.json") -> """{"sourceLanguage":"en","targetLanguage":"id"}"""
                else -> return MockResponse().setResponseCode(404)
            }
            val response=MockResponse().setBody(body)
            if(path=="/api/models/test/models/tree/$sha" && request.requestUrl!!.queryParameter("cursor")==null)
                response.addHeader("Link","<${if(badPage) "https://other.example/api/models/test/models/tree/$sha?cursor=x" else server.url("/api/models/test/models/tree/$sha?cursor=two")}>; rel=\"next\"")
            return response
        }};server.start()
    }
    @After fun close() {server.shutdown()}
    private fun loader()=RemoteTranslationCatalog(endpoints=TranslationCatalogEndpoints(server.url("/v1/").toString(),server.url("/records").toString(),server.url("/").toString(),server.url("/").toString(),"test/models"))
    private fun group(source:String,target:String,version:String,filter:String="")=listOf("model","lex","vocab").map {role ->buildJsonObject {
        put("fromLang",source);put("toLang",target);put("version",version);put("fileType",role);put("filter_expression",filter);put("deleted",false)
        val name=if(role=="model") "model.$source$target.intgemm.alphas.bin" else "$role.$source$target.bin"
        put("name",name);putJsonObject("attachment") {put("size",32);put("hash","b".repeat(64));put("location","$source-$target/$version/$name")}
    }}
    private fun tree(pair:Pair<String,String>)=buildJsonArray {
        val folder="base-memory/${pair.first}${pair.second}"
        add(buildJsonObject {put("type","file");put("path","$folder/metadata.json");put("size",100)})
        listOf("model.${pair.first}${pair.second}.intgemm.alphas.bin","lex.bin","vocab.spm").forEach {name ->
            listOf("", ".gz").forEach {suffix ->add(buildJsonObject {
                put("type","file");put("path","$folder/$name$suffix");put("size",32)
                putJsonObject("lfs") {put("oid","b".repeat(64));put("size",32)}
            })}
        }
    }.toString()
    @Test fun publishedCatalogUsesSemanticVersionsAndDynamicLanguageCodes()=runBlocking {
        records=buildJsonObject {put("data",JsonArray(group("de","en","2.9")+group("de","en","2.10")+group("en","id","1.0")+group("lzh","en","1.0")))}.toString()
        val catalog=loader().fetch(TranslationSourceSettings())
        assertEquals("2.10",catalog.pack("de-en").version)
        assertTrue(catalog.languages.contains(LocalTranslationLanguage.fromTag("id")))
        assertTrue(catalog.languages.contains(LocalTranslationLanguage.fromTag("lzh")))
        assertEquals(listOf("de-en","en-id"),catalog.route(LocalTranslationLanguage.fromTag("de"),LocalTranslationLanguage.fromTag("id")).map {it.id})
        assertEquals(TranslationFileCompression.NONE,catalog.pack("de-en").files.first().compression)
        assertTrue(requests.all {it=="/v1/" || it=="/records"})
    }
    @Test fun incompleteNightlyAndMalformedPackagesAreExcluded()=runBlocking {
        val malformed=group("fr","en","9.0").map {record ->JsonObject(record+ ("attachment" to JsonObject(record.getValue("attachment").jsonObject+("hash" to JsonPrimitive("bad"))))) }
        records=buildJsonObject {put("data",JsonArray(group("de","en","1.0")+group("de","en","3.0a1")+group("fr","en","2.0").dropLast(1)+group("en","id","2.0","env.channel == 'nightly'")+malformed))}.toString()
        assertEquals(listOf("de-en"),loader().fetch(TranslationSourceSettings()).packs.map {it.id})
    }
    @Test fun repositoryPaginationPinsHashesAndSupportsLanguagesWithoutAppMappings()=runBlocking {
        val catalog=loader().fetch(TranslationSourceSettings(TranslationDownloadSource.HUGGING_FACE))
        assertEquals(setOf("de-en","en-id"),catalog.packs.map {it.id}.toSet())
        assertTrue(catalog.packs.all {it.version.startsWith("hf-${sha.take(12)}")})
        assertTrue(catalog.packs.flatMap {it.files}.all {it.url!!.contains("/resolve/$sha/") && it.compression==TranslationFileCompression.GZIP})
        assertTrue(requests.all {it.startsWith("/api/") || it.endsWith("/metadata.json")})
    }
    @Test fun mirrorFetchUsesMirrorEndpoints()=runBlocking {
        val catalog=loader().fetch(TranslationSourceSettings(TranslationDownloadSource.HF_MIRROR))
        assertEquals("HF_MIRROR",catalog.sourceKey);assertEquals(2,catalog.packs.size)
    }
    @Test fun paginationCannotSwitchToUnselectedHost()=runBlocking<Unit> {
        badPage=true
        try {loader().fetch(TranslationSourceSettings(TranslationDownloadSource.HUGGING_FACE));fail("Must reject page host")} catch(_:IllegalArgumentException) {}
    }
    @Test fun customCatalogRebindsFilesToSelectedDirectory()=runBlocking {
        custom=File("src/main/assets/translation/catalog.json").readText()
        val settings=TranslationSourceSettings(TranslationDownloadSource.CUSTOM,server.url("/").toString())
        val catalog=loader().fetch(settings);val pack=catalog.pack("ja-en");val file=pack.files.first()
        assertEquals(server.url("/ja-en/${file.name}.gz").toString(),catalog.downloadUrl(pack,file,settings))
        assertEquals(listOf("/catalog.json"),requests)
    }
}
