package com.lmreader.core.database

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room 类型转换器。
 *
 * 为什么把 ComicInfo 字段存成 JSON 文本而不是拆列：开发文档 7.1 要求
 * 「未知字段保留原文并可搜索」，字段集合由用户文件决定，拆成固定列必然会
 * 丢掉 schema 之外的字段。JSON 文本不参与索引——搜索走
 * `metadata_records.normalized_search_text`，因此这里没有查询代价。
 */
internal object Converters {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    @TypeConverter
    @JvmStatic
    fun fieldsToJson(fields: Map<String, String>): String =
        json.encodeToString(serializer, fields)

    @TypeConverter
    @JvmStatic
    fun jsonToFields(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        // 解析失败不得让整行读取失败：坏 JSON 只能来自本应用写坏的数据，
        // 丢弃字段好过让图库整页加载不出来。
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }
}
