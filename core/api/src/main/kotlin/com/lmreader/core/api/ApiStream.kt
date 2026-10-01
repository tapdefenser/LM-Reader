package com.lmreader.core.api

import com.lmreader.core.model.ApiFormat
import kotlinx.serialization.json.*

/** 所有协议统一为增量正文、思考片段、重试通知和结束状态。 */
sealed interface ApiStreamEvent {
    data class Text(val value: String, val thinking: Boolean = false) : ApiStreamEvent
    data class Retrying(val attempt: Int) : ApiStreamEvent
    data class Finished(val reason: String? = null) : ApiStreamEvent
}

/** 按 SSE 事件边界解码；支持注释、多行 data、CRLF 与 EOF 最后一帧。 */
class SseDecoder {
    private val lines = mutableListOf<String>()
    private var size = 0
    fun line(value: String): String? {
        if (value.isEmpty()) return flush()
        if (value.startsWith("data:")) {
            val data = value.removePrefix("data:").removePrefix(" ")
            size += data.length
            if (size > 4 * 1024 * 1024) throw ApiException("单个流式事件过大")
            lines += data
        }
        return null
    }
    fun flush(): String? = if (lines.isEmpty()) null else lines.joinToString("\n").also { lines.clear(); size = 0 }
}

/** 流式与非流式内容解析，不以空响应伪装成功。 */
object ApiStreamParser {
    fun event(format: ApiFormat, payload: String): List<ApiStreamEvent> {
        if (payload.trim() == "[DONE]") return listOf(ApiStreamEvent.Finished())
        val root = try { Json.parseToJsonElement(payload).jsonObject } catch (_: Exception) {
            throw ApiException("模型返回了无法解析的流式数据")
        }
        root["error"]?.takeUnless { it is JsonNull }?.let { throw ApiException(errorMessage(it)) }
        return buildList {
            when (format) {
                ApiFormat.CHAT -> root["choices"]?.jsonArray?.forEach { choice ->
                    val c = choice.jsonObject
                    if (c["index"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return@forEach
                    val d = (c["delta"] ?: c["message"])?.jsonObject
                    d?.get("reasoning_content")?.let { addText(it, true) }
                    d?.get("reasoning")?.let { addText(it, true) }
                    d?.get("content")?.let { addText(it, false) }
                    d?.get("refusal")?.let { addText(it, false) }
                    c["finish_reason"]?.takeUnless { it is JsonNull }?.let { add(ApiStreamEvent.Finished(it.jsonPrimitive.content)) }
                }
                ApiFormat.RESPONSES -> {
                    val type = root["type"]?.jsonPrimitive?.content
                    when (type) {
                        "response.output_text.delta", "response.refusal.delta" -> root["delta"]?.let { addText(it, false) }
                        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> root["delta"]?.let { addText(it, true) }
                        "response.completed" -> add(ApiStreamEvent.Finished("completed"))
                        "response.incomplete" -> add(ApiStreamEvent.Finished("incomplete"))
                        "response.failed", "error" -> throw ApiException("模型响应失败")
                        null -> {
                            root["output_text"]?.let { addText(it, false) }
                            if (root["output_text"] == null) root["output"]?.jsonArray?.forEach { item ->
                                item.jsonObject["content"]?.jsonArray?.forEach { content -> content.jsonObject["text"]?.let { addText(it, false) } }
                            }
                            add(ApiStreamEvent.Finished(root["status"]?.jsonPrimitive?.content))
                        }
                    }
                }
                ApiFormat.GEMINI -> {
                    root["promptFeedback"]?.jsonObject?.get("blockReason")?.let { throw ApiException("请求被模型拦截：${it.jsonPrimitive.content}") }
                    root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.let { candidate ->
                        candidate["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                            val p = part.jsonObject
                            p["text"]?.let { addText(it, p["thought"]?.jsonPrimitive?.booleanOrNull == true) }
                        }
                        candidate["finishReason"]?.let { add(ApiStreamEvent.Finished(it.jsonPrimitive.content)) }
                    }
                }
            }
        }
    }

    private fun MutableList<ApiStreamEvent>.addText(value: JsonElement, thinking: Boolean) {
        val text = when (value) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonArray -> value.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> ""
        }
        if (text.isNotEmpty()) add(ApiStreamEvent.Text(text, thinking))
    }

    fun errorMessage(value: JsonElement): String = when (value) {
        is JsonObject -> value["message"]?.jsonPrimitive?.contentOrNull ?: "API 返回错误"
        is JsonPrimitive -> value.contentOrNull ?: "API 返回错误"
        else -> "API 返回错误"
    }.take(1200)
}
