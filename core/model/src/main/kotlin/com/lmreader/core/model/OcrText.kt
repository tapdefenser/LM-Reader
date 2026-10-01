package com.lmreader.core.model

/** OCR line boundaries describe page layout, not sentence or paragraph boundaries. */
fun joinOcrText(lines: Iterable<String>, language: LocalOcrLanguage): String {
    val spaced = language == LocalOcrLanguage.ENGLISH || language == LocalOcrLanguage.KOREAN
    val chunks = lines.flatMap { it.split(Regex("[\\r\\n\\u2028\\u2029]+")) }
        .map { it.replace(Regex("[\\s\\u00a0\\u3000]+"), " ").trim() }.filter { it.isNotEmpty() }
    return chunks.fold("") { previous, next ->
        if (previous.isEmpty()) next else {
            val a = previous.last(); val b = next.first()
            val separator = if (b in ",.!?;:)]}、。，！？：；）］｝」』】" || a in "([{（［｛「『【") ""
                else if (spaced || (!isUnspacedScript(a) && !isUnspacedScript(b))) " " else ""
            previous + separator + next
        }
    }
}

private fun isUnspacedScript(c: Char) = c in '\u2E80'..'\u9FFF' || c in '\uF900'..'\uFAFF' ||
    c in '\uFF00'..'\uFFEF'
