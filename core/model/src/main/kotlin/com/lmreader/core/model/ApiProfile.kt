package com.lmreader.core.model

import kotlinx.coroutines.flow.Flow

/** 独立的 LLM/OCR 配置组；本地 OCR 是界面内置项，不作为可变配置落库。 */
enum class ApiProfileKind { LLM, OCR }

/** 已实现请求编码、模型列表与流式解码的协议。 */
enum class ApiFormat(val label: String) {
    CHAT("OpenAI Chat 兼容"), RESPONSES("OpenAI Responses"), GEMINI("Gemini"),
}

/** 用户指定的思考长度；关闭时不主动发送思考参数。 */
enum class ThinkingLevel(val label: String, val wireValue: String) {
    LOW("低", "low"), MEDIUM("中", "medium"), HIGH("高", "high"),
}

/** null 表示不发送该参数，保留服务端默认值。 */
data class AiParameters(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
)

/** API 配置快照。凭据只在内存使用，持久化由平台层加密，toString 不暴露凭据。 */
data class ApiProfile(
    val id: String,
    val kind: ApiProfileKind,
    val name: String = "",
    val url: String = "",
    val format: ApiFormat = ApiFormat.CHAT,
    val apiKey: String = "",
    val model: String = "",
    val timeoutSeconds: Int = 300,
    val retryCount: Int = 3,
    val thinkingEnabled: Boolean = false,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    val parameters: AiParameters = AiParameters(),
    val customParameters: String = "{}",
    val parallelLimit: Int = 4,
) {
    val displayName: String get() = name.ifBlank { model.ifBlank { "未命名配置" } }
    override fun toString(): String = "ApiProfile(id=$id, kind=$kind, format=$format, credentials=REDACTED)"
}

/** 配置列表的持久化边界；编辑、复制和删除均不触发模型请求。 */
interface ApiProfileRepository {
    val profiles: Flow<List<ApiProfile>>
    suspend fun save(profile: ApiProfile)
    suspend fun duplicate(id: String): ApiProfile
    suspend fun delete(id: String)
}
