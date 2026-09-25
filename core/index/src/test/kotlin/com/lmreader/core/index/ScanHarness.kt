package com.lmreader.core.index

import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.SourceKind

/**
 * 扫描测试台：把一棵内存树 + 一次 [ScanRequest] + 事件收集器绑在一起，
 * 让 5.3 的每一行样例都能写成「给结构 → 断言卡片与章节」的两三行测试。
 *
 * **发现阶段的章节语义**（用户要求，也是性能要求）：扫描不为了一部作品遍历所有
 * 章节目录。多章节模式只探测到**自然序第一章**就发出卡片，`chapterCountKnown`
 * 为 false；单章节模式的 1 章是结构定义，`chapterCountKnown` 为 true。
 * 完整章节清单由详情页的「更新章节」按需枚举（开发文档 6.1 第 3 步）。
 * 因此断言章节清单时只应期待这一个章节。
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
        sourceRevision = 0,
        rootDocumentId = InMemoryTreeFactory.ROOT_DOCUMENT_ID,
        layoutMode = mode,
        recursive = recursive,
        generation = generation,
        displayPath = rootName,
    )

    /** 最近一次 [run] 的汇总；未运行前为 null。 */
    var summary: ScanSummary = ScanSummary(
        generation = generation,
        mangas = 0,
        chapters = 0,
        directoriesVisited = 0,
        diagnostics = emptyList(),
        failedPaths = emptyList(),
        completed = false,
    )
        private set

    val events = mutableListOf<ScanEvent>()
    val discoveries = mutableListOf<ScanResult>()

    suspend fun run(
        scanner: StructureScanner = StructureScanner(factory) { FIXED_TIME },
        onEvent: (suspend (ScanEvent) -> Unit)? = null,
    ): ScanSummary = scanner.scan(request, factory.root) { event ->
        events += event
        if (event is ScanEvent.MangaDiscovered) discoveries += event.result
        onEvent?.invoke(event)
    }.also { summary = it }

    /** 发现顺序下的漫画名。 */
    val names: List<String> get() = discoveries.map { it.manga.displayName }

    /**
     * 每本漫画在**发现阶段**带出的章节标题。
     *
     * 多章节模式下一个元素（自然序第一章），单章节模式下一个元素（自身）——
     * 见 [ScanHarness] 类注释里的发现语义说明。
     */
    val chapterTitles: List<List<String>> get() = discoveries.map { result -> result.chapters.map { it.title } }

    /** 发现阶段是否声明"章节数已知"。多章节模式应为 false（只探测到下限）。 */
    fun chapterCountKnown(name: String): Boolean = resultOf(name).manga.chapterCountKnown

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
