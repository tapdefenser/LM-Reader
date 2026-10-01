package com.lmreader.core.model

/** Editable examples: standard page text, one manga text request, and direct bubble vision. */
object WorkflowReferenceTemplates {
    private fun declaration(id: String, name: String, type: WorkflowType) = WorkflowNode("new-$id", WorkflowKind.DECLARE, variable = WorkflowVariable(id, name, type))
    private fun prompt(text: String, extra: Map<String, WorkflowRef>) = WorkflowExpression.Template(text, linkedMapOf(
        "源语言" to WorkflowRef(WorkflowSystem.SOURCE), "目标语言" to WorkflowRef(WorkflowSystem.TARGET),
        "文风" to WorkflowRef(WorkflowSystem.STYLE), "译名字典" to WorkflowRef(WorkflowSystem.GLOSSARY)) + extra)
    private fun record() = WorkflowExpression.Record(linkedMapOf(
        "bubbleId" to WorkflowSystem.ref(WorkflowSystem.BUBBLE, "id"),
        "source" to WorkflowSystem.ref(WorkflowSystem.BUBBLE, "source"), "translation" to WorkflowExpression.Text("")))
    private fun ocrLoop(collectTo: WorkflowRef? = null) = WorkflowNode("bubbles", WorkflowKind.EACH, label = "异步识别各气泡",
        variable = WorkflowVariable(WorkflowSystem.BUBBLE, "气泡", WorkflowType.BUBBLE),
        inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles")), collectTo = collectTo,
        // The loop collects a selected expression after each item, without a return row.
        children = listOf(WorkflowNode("ocr", WorkflowKind.OCR, target = WorkflowRef(WorkflowSystem.BUBBLE, listOf("source")),
            inputs = mapOf("image" to WorkflowSystem.ref(WorkflowSystem.BUBBLE, "image"), "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE)))),
        ).let { if(collectTo == null) it else it.copy(inputs = it.inputs + ("collectValue" to record())) }
    private fun segmentation() = WorkflowNode("seg", WorkflowKind.SEG, target = WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles")),
        inputs = mapOf("image" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image")))
    private fun apply(id: String, list: String) = WorkflowNode(id, WorkflowKind.APPLY_TRANSLATIONS, inputs = mapOf("items" to WorkflowSystem.ref(list)))

    fun standard(profileId: String = ""): WorkflowProgram {
        val type = WorkflowType.list(WorkflowType.TRANSLATION)
        val base = WorkflowTemplates.blank(); val manga = base.rows.single(); val chapter = manga.children.single(); val page = chapter.children.single()
        val rows = listOf(segmentation(), declaration("page-originals", "本页气泡原文", type), ocrLoop(WorkflowRef("page-originals")),
            declaration("page-translations", "本页气泡译文", type),
            WorkflowNode("standard-api", WorkflowKind.API, label = "合并本页气泡文本翻译", target = WorkflowRef("page-translations"), resultType = type,
                inputs = mapOf("profile" to WorkflowExpression.Text(profileId), "expectedBubbles" to WorkflowSystem.ref("page-originals"),
                    "prompt" to prompt("将本页全部气泡从\${源语言}翻译为\${目标语言}。根据整页对话理解人物、语气和指代；文风：\${文风}。已有译名字典：\${译名字典}。输入：\${本页原文}。返回完整 JSON 列表，每项 bubbleId、source、translation。bubbleId 和 source 必须逐字照抄，不得遗漏、重复、添加或修正 OCR 原文。空原文保持空译文。", mapOf("本页原文" to WorkflowRef("page-originals"))))),
            apply("apply-page", "page-translations"))
        return base.copy(rows = listOf(manga.copy(children = listOf(chapter.copy(children = listOf(page.copy(children = rows)))))))
    }
    fun fullManga(profileId: String = ""): WorkflowProgram {
        val type = WorkflowType.list(WorkflowType.PAGE_RECORD)
        val base = WorkflowTemplates.blank(); val manga = base.rows.single(); val chapter = manga.children.single(); val page = chapter.children.single()
        return base.copy(rows = listOf(manga.copy(children = listOf(
            declaration("manga-originals", "整漫画气泡原文", type),
            WorkflowNode("prepare-manga", WorkflowKind.EACH, label = "识别漫画各页", variable = WorkflowVariable(WorkflowSystem.PAGE, "本页", WorkflowSystem.pageType),
                inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.ALL_PAGES), "publish" to WorkflowExpression.Boolean(false),
                    "collectValue" to WorkflowSystem.ref(WorkflowSystem.PAGE, "records"), "flatten" to WorkflowExpression.Boolean(true)),
                collectTo = WorkflowRef("manga-originals"), children = listOf(segmentation(), ocrLoop())),
            declaration("manga-translations", "整漫画气泡译文", type),
            WorkflowNode("full-manga-api", WorkflowKind.API, label = "整漫画只请求一次翻译", target = WorkflowRef("manga-translations"), resultType = type,
                inputs = mapOf("profile" to WorkflowExpression.Text(profileId), "wholeManga" to WorkflowExpression.Boolean(true), "expectedBubbles" to WorkflowSystem.ref("manga-originals"),
                    "prompt" to prompt("将整部漫画的所有气泡从\${源语言}翻译为\${目标语言}。用整部漫画上下文统一人物、专名、语气和指代；文风：\${文风}。已有译名字典：\${译名字典}。所有气泡按章节和页顺序提供：\${整漫画原文}。只返回完整 JSON 列表，每项 pageId、pageNumber、bubbleId、source、translation。前四个字段必须原样保留，包括 source 的大小写、标点和换行；只填写 translation。不得遗漏、重复、添加条目。空原文保持空译文，不单独提取译名。", mapOf("整漫画原文" to WorkflowRef("manga-originals"))))),
            chapter.copy(mode = WorkflowMode.SYNC, children = listOf(page.copy(mode = WorkflowMode.SYNC, children = listOf(apply("apply-manga-page", "manga-translations")))))))))
    }
    fun vision(profileId: String = "") = WorkflowTemplates.visionWithGlossary(profileId)

    /** Copying a reference binds every request, including optional glossary extraction. */
    fun bindApi(program: WorkflowProgram, profileId: String): WorkflowProgram {
        fun bind(node: WorkflowNode): WorkflowNode = node.copy(children = node.children.map(::bind), otherwise = node.otherwise.map(::bind),
            inputs = if (node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) node.inputs + ("profile" to WorkflowExpression.Text(profileId)) else node.inputs)
        return program.copy(rows = program.rows.map(::bind))
    }
}
