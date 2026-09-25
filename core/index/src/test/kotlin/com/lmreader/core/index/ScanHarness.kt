package com.lmreader.core.index

import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.SourceKind

/**
 * 扫描测试台：把一棵内存树 + 一次 [ScanRequest] + 事件收集器绑在一起，
 * 让 5.3 的每一行样例都能写成「给结构 → 断言卡片与章节」的两三行测试。
 */
class ScanHarness(
    rootName: String,
    paths: List<String> = emptyList(),
    kind: SourceKind = SourceKind.IMAGE_DIRECTORY,
    mode: LayoutMode = LayoutMode.MULTI_CHAPTER,
    recursive: Boolean = true,
    generation: Long = GENERATION,
) {
    val factory = InMemoryTreeFactory(rootName, paths)

    val request = ScanRequest(
        sourceId = SOURCE_ID,
        sourceKind = kind,
        rootDocumentId = InMemoryTreeFactory.ROOT_DOCUMENT_ID,
        layoutMode = mode,
        recursive = recursive,
        generation = generation,
        displayPath = rootName,
    )

    val events = mutableListOf<ScanEvent>()
    val discoveries = mutableListOf<ScanResult>()

    suspend fun run(
        scanner: StructureScanner = StructureScanner(factory) { FIXED_TIME },
        onEvent: (suspend (ScanEvent) -> Unit)? = null,
    ): ScanSummary = scanner.scan(request, factory.root) { event ->
        events += event
        if (event is ScanEvent.MangaDiscovered) discoveries += event.result
        onEvent?.invoke(event)
    }

    /** 发现顺序下的漫画名。 */
    val names: List<String> get() = discoveries.map { it.manga.displayName }

    /** 每本漫画的章节标题，与 [names] 一一对应。 */
    val chapterTitles: List<List<String>> get() = discoveries.map { result -> result.chapters.map { it.title } }

    val diagnostics: List<String> get() = events.filterIsInstance<ScanEvent.Diagnostic>().map { it.message }

    val failures: List<ScanEvent.Failed> get() = events.filterIsInstance<ScanEvent.Failed>()

    val progress: List<ScanEvent.Progress> get() = events.filterIsInstance<ScanEvent.Progress>()

    fun resultOf(name: String): ScanResult = discoveries.firstOrNull { it.manga.displayName == name }
        ?: throw AssertionError("没有发现漫画「$name」，实际发现：$names")

    fun chaptersOf(name: String): List<String> = resultOf(name).chapters.map { it.title }

    companion object {
        const val SOURCE_ID = "source-test"
        const val GENERATION = 7L
        const val FIXED_TIME = 1_700_000_000_000L
    }
}
