package com.lmreader.ui.i18n

import android.content.Context
import org.json.JSONObject
import com.lmreader.core.storage.settings.AppLanguage

/** Offline English catalog for legacy Kotlin UI copy. User content is never sent to a service. */
object UiTextTranslations {
    private data class Template(val source: Regex, val english: String)
    private data class Catalog(val exact: Map<String, String>, val patterns: List<Template>, val fragments: List<Pair<String, String>>)
    @Volatile private var catalog: Catalog? = null
    private val cache = object : LinkedHashMap<String, String>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1024
    }

    fun translate(context: Context, text: String): String {
        val locales = context.resources.configuration.locales
        if (AppLanguage.resolve(null, if (locales.isEmpty) null else locales[0]) != AppLanguage.ENGLISH || !text.any { it in '\u3400'..'\u9fff' })
            return text
        synchronized(cache) { cache[text]?.let { return it } }
        val data = catalog ?: synchronized(this) { catalog ?: load(context).also { catalog = it } }
        val result = data.exact[text] ?: data.patterns.firstNotNullOfOrNull { template ->
            val match = template.source.matchEntire(text) ?: return@firstNotNullOfOrNull null
            var translated = template.english
            match.groupValues.drop(1).forEachIndexed { index, value -> translated = translated.replace("__VAR${index}__", value) }
            translated
        } ?: run {
            var translated = text
            data.fragments.forEach { (source, english) -> if (source in translated) translated = translated.replace(source, english) }
            translated
        }
        synchronized(cache) { cache[text] = result }
        return result
    }

    private fun load(context: Context): Catalog {
        val json = JSONObject(context.assets.open("i18n/zh_en.json").bufferedReader().use { it.readText() })
        val exact = LinkedHashMap<String, String>()
        val patterns = ArrayList<Template>()
        val fragments = ArrayList<Pair<String, String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val source = keys.next()
            val english = json.getString(source)
            if ("__VAR" in source) {
                val escaped = Regex.escape(source).replace(Regex("__VAR[0-9]+__")) { "\\E(.*?)\\Q" }
                patterns += Template(Regex(escaped), english)
            } else {
                exact[source] = english
                if (source.length >= 3 && source.any { it in '\u3400'..'\u9fff' }) fragments += source to english
            }
        }
        return Catalog(exact, patterns.sortedByDescending { it.source.pattern.length }, fragments.sortedByDescending { it.first.length })
    }
}
