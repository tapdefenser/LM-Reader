package com.lmreader.ui.settings.api

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lmreader.core.api.*
import com.lmreader.core.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ApiListState(val loading: Boolean = true, val profiles: List<ApiProfile> = emptyList(), val error: String? = null)

/** 列表读取失败时保留明确错误，不将损坏的配置文档当作空列表。 */
class ApiProfilesViewModel(private val repository: ApiProfileRepository, private val kind: ApiProfileKind) : ViewModel() {
    private val mutableState = MutableStateFlow(ApiListState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            try { repository.profiles.collect { profiles -> mutableState.value = ApiListState(false, profiles.filter { it.kind == kind }) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, error = "API 配置读取失败，请检查本地存储") } }
        }
    }
    fun duplicate(id: String) = mutation { repository.duplicate(id) }
    fun delete(id: String) = mutation { repository.delete(id) }
    fun clearError() { mutableState.update { it.copy(error = null) } }
    private fun mutation(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableState.update { it.copy(error = "配置操作失败，原有配置已保留") }
            }
        }
    }
}

data class ApiEditorState(
    val draft: ApiProfileDraft,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val modelsOpen: Boolean = false,
    val modelsLoading: Boolean = false,
    val models: List<String> = emptyList(),
    val modelsError: String? = null,
    val responseOpen: Boolean = false,
    val running: Boolean = false,
    val response: String = "",
    val thinking: String = "",
    val testStatus: String = "",
    val testError: String? = null,
)

/** 编辑草稿可直接测试，不必先保存；页面离开时 ViewModel 自动取消请求。 */
class ApiProfileEditorViewModel(
    private val repository: ApiProfileRepository,
    private val gateway: ApiGateway,
    kind: ApiProfileKind,
    id: String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ApiEditorState(ApiProfileDraft(ApiProfile(
        id = id ?: UUID.randomUUID().toString(), kind = kind, parallelLimit = if (kind == ApiProfileKind.OCR) 3 else 4,
    )), loading = id != null))
    val state = mutableState.asStateFlow()
    private var baseline = state.value.draft
    private var testJob: Job? = null
    private var modelsJob: Job? = null
    private var testGeneration = 0
    init {
        if (id != null) viewModelScope.launch {
            try {
                val profile = repository.profiles.first().firstOrNull { it.id == id && it.kind == kind }
                    ?: throw IllegalStateException("配置已不存在")
                baseline = ApiProfileDraft(profile)
                mutableState.update { it.copy(draft = baseline, loading = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableState.update { it.copy(error = "无法打开此配置", loading = false, loadFailed = true) }
            }
        }
    }
    fun change(transform: (ApiProfileDraft) -> ApiProfileDraft) {
        if (state.value.loading || state.value.saving || state.value.loadFailed) return
        mutableState.update { old -> val draft = transform(old.draft); old.copy(draft = draft, dirty = draft != baseline, error = null) }
    }
    fun changeProfile(transform: (ApiProfile) -> ApiProfile) = change { it.copy(profile = transform(it.profile)) }
    fun save() {
        if (state.value.loading || state.value.saving || state.value.loadFailed) return
        val profile = build() ?: return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { repository.save(profile); mutableState.update { it.copy(saving = false, dirty = false, saved = true) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(saving = false, error = "保存失败，草稿已保留") } }
        }
    }
    fun fetchModels() {
        val profile = build(requireModel = false) ?: return
        modelsJob?.cancel()
        mutableState.update { it.copy(modelsOpen = true, modelsLoading = true, models = emptyList(), modelsError = null) }
        modelsJob = viewModelScope.launch {
            try { val result = gateway.models(profile); mutableState.update { it.copy(modelsLoading = false, models = result) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(modelsLoading = false, modelsError = e.message ?: "获取模型失败") } }
        }
    }
    fun chooseModel(model: String) { changeProfile { it.copy(model = model) }; closeModels() }
    fun closeModels() { modelsJob?.cancel(); mutableState.update { it.copy(modelsOpen = false, modelsLoading = false) } }
    fun startTest(prompt: String) {
        val profile = build() ?: return
        if (prompt.isBlank()) { mutableState.update { it.copy(error = "请输入测试请求") }; return }
        val generation = ++testGeneration
        testJob?.cancel()
        mutableState.update { it.copy(responseOpen = true, running = true, response = "", thinking = "", testStatus = "正在连接…", testError = null) }
        testJob = viewModelScope.launch {
            try {
                gateway.stream(profile, prompt).collect { event ->
                    if (state.value.response.length + state.value.thinking.length > 2 * 1024 * 1024) throw ApiException("测试输出达到显示上限，已停止")
                    mutableState.update { old -> when (event) {
                        is ApiStreamEvent.Text -> if (event.thinking) old.copy(thinking = old.thinking + event.value, testStatus = "正在接收…") else old.copy(response = old.response + event.value, testStatus = "正在接收…")
                        is ApiStreamEvent.Retrying -> old.copy(testStatus = "正在重试（${event.attempt}）…")
                        is ApiStreamEvent.Finished -> old.copy(testStatus = if (event.reason in setOf("length", "MAX_TOKENS", "incomplete")) "输出达到限制，内容可能不完整" else "完成")
                    } }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (generation == testGeneration) mutableState.update { it.copy(testStatus = "请求失败", testError = e.message ?: "模型测试失败") }
            } finally { if (generation == testGeneration) mutableState.update { it.copy(running = false) } }
        }
    }
    fun stopTest() { testGeneration++; testJob?.cancel(); mutableState.update { it.copy(running = false, testStatus = "已停止") } }
    fun closeResponse() { if (state.value.running) stopTest(); mutableState.update { it.copy(responseOpen = false) } }
    private fun build(requireModel: Boolean = true): ApiProfile? = try { state.value.draft.build(requireModel) }
    catch (e: Exception) { mutableState.update { it.copy(error = e.message ?: "配置不完整") }; null }
}
