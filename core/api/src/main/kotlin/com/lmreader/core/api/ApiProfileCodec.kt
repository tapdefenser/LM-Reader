package com.lmreader.core.api

import com.lmreader.core.model.*
import kotlinx.serialization.json.*

/** 版本化配置编码；平台层传入加密后的凭据，网络层不会调用此编码器。 */
object ApiProfileCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(profiles: List<ApiProfile>): String = buildJsonObject {
        put("schemaVersion", 1)
        put("profiles", JsonArray(profiles.map(::encodeProfile)))
    }.toString()

    fun decode(value: String?): List<ApiProfile> {
        if (value == null) return emptyList()
        val root = json.parseToJsonElement(value).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.int == 1) { "API 配置版本不支持" }
        val result = root.getValue("profiles").jsonArray.map { decodeProfile(it.jsonObject) }
        require(result.map { it.id }.distinct().size == result.size) { "API 配置 ID 重复" }
        return result
    }

    private fun encodeProfile(p: ApiProfile) = buildJsonObject {
        put("id", p.id); put("kind", p.kind.name); put("name", p.name)
        put("url", p.url); put("format", p.format.name); put("apiKey", p.apiKey)
        put("model", p.model); put("timeoutSeconds", p.timeoutSeconds); put("retryCount", p.retryCount)
        put("thinkingEnabled", p.thinkingEnabled); put("thinkingLevel", p.thinkingLevel.name)
        put("customParameters", p.customParameters); put("parallelLimit", p.parallelLimit)
        put("parameters", buildJsonObject {
            p.parameters.temperature?.let { put("temperature", it) }
            p.parameters.topP?.let { put("topP", it) }
            p.parameters.topK?.let { put("topK", it) }
            p.parameters.maxTokens?.let { put("maxTokens", it) }
            p.parameters.frequencyPenalty?.let { put("frequencyPenalty", it) }
            p.parameters.presencePenalty?.let { put("presencePenalty", it) }
        })
    }

    private fun decodeProfile(o: JsonObject): ApiProfile {
        val p = o["parameters"]?.jsonObject ?: JsonObject(emptyMap())
        return ApiProfile(
            id = o.getValue("id").jsonPrimitive.content,
            kind = ApiProfileKind.valueOf(o.getValue("kind").jsonPrimitive.content),
            name = o["name"]?.jsonPrimitive?.content ?: "",
            url = o.getValue("url").jsonPrimitive.content,
            format = ApiFormat.valueOf(o.getValue("format").jsonPrimitive.content),
            apiKey = o["apiKey"]?.jsonPrimitive?.content ?: "",
            model = o.getValue("model").jsonPrimitive.content,
            timeoutSeconds = o["timeoutSeconds"]?.jsonPrimitive?.int ?: 300,
            retryCount = o["retryCount"]?.jsonPrimitive?.int ?: 3,
            thinkingEnabled = o["thinkingEnabled"]?.jsonPrimitive?.boolean ?: false,
            thinkingLevel = ThinkingLevel.valueOf(o["thinkingLevel"]?.jsonPrimitive?.content ?: "MEDIUM"),
            parameters = AiParameters(
                p["temperature"]?.jsonPrimitive?.double, p["topP"]?.jsonPrimitive?.double,
                p["topK"]?.jsonPrimitive?.int, p["maxTokens"]?.jsonPrimitive?.int,
                p["frequencyPenalty"]?.jsonPrimitive?.double, p["presencePenalty"]?.jsonPrimitive?.double,
            ),
            customParameters = o["customParameters"]?.jsonPrimitive?.content ?: "{}",
            parallelLimit = o["parallelLimit"]?.jsonPrimitive?.int ?: 4,
        )
    }
}
