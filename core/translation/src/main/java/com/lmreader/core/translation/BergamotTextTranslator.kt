package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

internal object BergamotNative {
    init { System.loadLibrary("lmreader_translation") }
    external fun load(key: ByteArray, config: ByteArray)
    external fun translate(key: ByteArray, text: ByteArray): ByteArray
    external fun unload(key: ByteArray)
    external fun release()
}

/** A process-wide lock also prevents removal of files while native inference uses them. */
internal object TranslationRuntime {
    val mutex = Mutex()
    val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "local-translation") }.asCoroutineDispatcher()
    val resources = MutableStateFlow<List<LoadedEngineResource>>(emptyList())
}

class BergamotTextTranslator(private val catalog: () -> TranslationModelCatalog, private val installer: ModelPackageInstaller): LocalTextTranslator {
    val loadedResources = TranslationRuntime.resources.asStateFlow()
    constructor(catalog: TranslationModelCatalog, installer: ModelPackageInstaller): this({catalog},installer)
    override suspend fun translate(source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        items: List<LocalTranslationText>, onProgress: (Int, Int) -> Unit): LocalTranslationResult {
        require(items.size <= 256) { "一次最多翻译 256 段文本" }
        require(items.all { it.id.isNotBlank() && it.sourceText.length <= 4096 && '\u0000' !in it.sourceText }) { "文本 ID 不能为空；单段最多 4096 字符且不可含 NUL" }
        require(items.map { it.id }.distinct().size == items.size) { "文本 ID 重复" }
        val route = catalog().route(source, target)
        val started = System.nanoTime()
        if (items.all { it.sourceText.isBlank() } || route.isEmpty()) return LocalTranslationResult(
            items.map { LocalTranslatedText(it.id, it.sourceText, it.sourceText) }, emptyList(), 0)
        return TranslationRuntime.mutex.withLock {
            withContext(TranslationRuntime.dispatcher) {
                route.forEach { installer.verifyInstalled(it) }
                val ids = route.map { it.id }
                val signature = route.map { "${it.id}-${it.version}:${installer.installedDirectory(it).absolutePath}" }
                route.zip(signature).forEach { (pack, key) ->
                    if (TranslationRuntime.resources.value.none { it.id == key }) {
                        ensureActive()
                        try { BergamotNative.load(key.toByteArray(Charsets.UTF_8), configuration(pack, installer.installedDirectory(pack)).toByteArray(Charsets.UTF_8)) }
                        catch (failure: LinkageError) { throw IllegalStateException("当前设备无法加载机翻运行库；需要 arm64-v8a 或 x86_64", failure) }
                        TranslationRuntime.resources.value += LoadedEngineResource(key, InferenceEngineKind.TRANSLATION,
                            "${pack.label} · ${pack.version}", "Bergamot CPU")
                    }
                }
                TranslationRuntime.resources.value = TranslationRuntime.resources.value.map { it.copy(inUse = it.id in signature) }
                try { val result = items.mapIndexed { index, item ->
                    ensureActive()
                    var text = item.sourceText
                    if (text.isNotBlank()) signature.forEach { key ->
                        ensureActive()
                        text = BergamotNative.translate(key.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
                        ensureActive()
                        require(text.isNotBlank()) { "模型返回空译文：${item.id}" }
                    }
                    onProgress(index + 1, items.size)
                    LocalTranslatedText(item.id, item.sourceText, text)
                }
                LocalTranslationResult(result, ids, (System.nanoTime() - started) / 1_000_000)
                } finally { TranslationRuntime.resources.value = TranslationRuntime.resources.value.map { it.copy(inUse = false) } }
            }
        }
    }

    override suspend fun releaseModels() = TranslationRuntime.mutex.withLock {
        withContext(TranslationRuntime.dispatcher) {
            if (TranslationRuntime.resources.value.isNotEmpty()) BergamotNative.release()
            TranslationRuntime.resources.value = emptyList()
        }
    }

    /** Called after the previous manga's workers have joined; keep shared route packs. */
    suspend fun retainModels(routes: Set<Pair<LocalTranslationLanguage, LocalTranslationLanguage>>) = TranslationRuntime.mutex.withLock {
        withContext(TranslationRuntime.dispatcher) {
            val required = routes.flatMap { (source, target) -> catalog().route(source, target) }
                .map { "${it.id}-${it.version}:${installer.installedDirectory(it).absolutePath}" }.toSet()
            TranslationRuntime.resources.value.filterNot { it.id in required }.forEach {
                BergamotNative.unload(it.id.toByteArray(Charsets.UTF_8))
            }
            TranslationRuntime.resources.value = TranslationRuntime.resources.value.filter { it.id in required }
        }
    }

    suspend fun removePack(pack: TranslationModelPack) = TranslationRuntime.mutex.withLock {
        withContext(TranslationRuntime.dispatcher) {
            check(TranslationRuntime.resources.value.none { it.id.startsWith("${pack.id}-${pack.version}:") }) {
                "此语言包仍已加载，请先在翻译队列中全部暂停并卸载引擎，再删除语言包"
            }
            installer.remove(pack)
        }
    }

    companion object {
        internal fun configuration(pack: TranslationModelPack, directory: File): String {
            fun path(role: String): String = "'" + File(directory, pack.files.single { it.role == role }.name).absolutePath.replace("'", "''") + "'"
            val vocab = if (pack.files.any { it.role == "vocab" }) listOf(path("vocab"), path("vocab")) else listOf(path("srcVocab"), path("trgVocab"))
            return """
                models: [${path("model")}]
                vocabs: [${vocab.joinToString(", ")}]
                shortlist: [${path("lexicalShortlist")}, false]
                beam-size: 1
                normalize: 1.0
                word-penalty: 0
                max-length-break: 256
                max-length-factor: 3.0
                mini-batch-words: 1024
                workspace: 128
                cpu-threads: 1
                gemm-precision: int8shiftAlphaAll
                skip-cost: true
                quiet: true
                quiet-translation: true
                ssplit-mode: paragraph
            """.trimIndent()
        }
    }
}
