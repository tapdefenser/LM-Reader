package com.lmreader.core.api

import com.lmreader.core.model.*

/** 表单保留用户尚未完成的数字文本，验证失败不会丢掉输入。 */
data class ApiProfileDraft(
    val profile: ApiProfile,
    val timeout: String = profile.timeoutSeconds.toString(),
    val retries: String = profile.retryCount.toString(),
    val parallel: String = profile.parallelLimit.toString(),
) {
    fun build(requireModel: Boolean = true): ApiProfile {
        val result = profile.copy(
            name = profile.name.trim(), url = profile.url.trim(), apiKey = profile.apiKey.trim(), model = profile.model.trim(),
            timeoutSeconds = timeout.toIntOrNull() ?: throw IllegalArgumentException("API 超时必须是整数"),
            retryCount = retries.toIntOrNull() ?: throw IllegalArgumentException("API 重试次数必须是整数"),
            parallelLimit = parallel.toIntOrNull() ?: throw IllegalArgumentException("API 并行限制必须是整数"),
        )
        ApiProtocol.validate(result, requireModel)
        return result
    }
    override fun toString(): String = "ApiProfileDraft(credentials=REDACTED)"
}
