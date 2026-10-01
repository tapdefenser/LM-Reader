package com.lmreader.ui.settings.api

import com.lmreader.core.api.*
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiConfigurationViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repository : ApiProfileRepository {
        override val profiles = MutableStateFlow<List<ApiProfile>>(emptyList())
        override suspend fun save(profile: ApiProfile) { profiles.value = profiles.value.filterNot { it.id == profile.id } + profile }
        override suspend fun duplicate(id: String): ApiProfile { val copied = profiles.value.first { it.id == id }.copy(id = "copy"); save(copied); return copied }
        override suspend fun delete(id: String) { profiles.value = profiles.value.filterNot { it.id == id } }
    }
    private class Gateway : ApiGateway {
        var tested: ApiProfile? = null
        var cancelled = false
        var waiting = false
        override suspend fun models(profile: ApiProfile) = listOf("one", "two")
        override fun stream(profile: ApiProfile, prompt: String): Flow<ApiStreamEvent> = flow {
            tested = profile
            try {
                emit(ApiStreamEvent.Text("思考", thinking = true)); emit(ApiStreamEvent.Text("答")); emit(ApiStreamEvent.Text("复"))
                if (waiting) awaitCancellation()
                emit(ApiStreamEvent.Finished("stop"))
            } finally { cancelled = true }
        }
    }
    @Test fun `unsaved draft can fetch models and stream before saving`() = runTest(dispatcher) {
        val repo = Repository(); val gateway = Gateway()
        val vm = ApiProfileEditorViewModel(repo, gateway, ApiProfileKind.LLM, null)
        vm.changeProfile { it.copy(url = "http://localhost/v1", apiKey = "test") }
        vm.fetchModels(); runCurrent()
        assertEquals(listOf("one", "two"), vm.state.value.models)
        vm.chooseModel("two"); vm.startTest("合成测试"); runCurrent()
        assertEquals("答复", vm.state.value.response); assertEquals("思考", vm.state.value.thinking)
        assertEquals("完成", vm.state.value.testStatus); assertFalse(vm.state.value.running)
        assertTrue(repo.profiles.value.isEmpty()); assertEquals("two", gateway.tested?.model)
        vm.save(); runCurrent(); assertEquals("two", repo.profiles.value.single().model); assertTrue(vm.state.value.saved)
    }
    @Test fun `invalid drafts retain values and never call transport or storage`() = runTest(dispatcher) {
        val repo = Repository(); val gateway = Gateway()
        val vm = ApiProfileEditorViewModel(repo, gateway, ApiProfileKind.OCR, null)
        vm.changeProfile { it.copy(url = "broken", model = "typed-model") }
        vm.save(); vm.startTest("hi"); runCurrent()
        assertNotNull(vm.state.value.error); assertEquals("typed-model", vm.state.value.draft.profile.model)
        assertNull(gateway.tested); assertTrue(repo.profiles.value.isEmpty())
    }
    @Test fun `closing output cancels test and preserves partial text`() = runTest(dispatcher) {
        val gateway = Gateway().apply { waiting = true }
        val vm = ApiProfileEditorViewModel(Repository(), gateway, ApiProfileKind.LLM, null)
        vm.changeProfile { it.copy(url = "http://localhost/v1", model = "fixture") }
        vm.startTest("test"); runCurrent(); assertTrue(vm.state.value.running)
        vm.closeResponse(); runCurrent()
        assertTrue(gateway.cancelled); assertFalse(vm.state.value.responseOpen); assertFalse(vm.state.value.running)
        assertEquals("答复", vm.state.value.response)
    }
    @Test fun `missing saved profile cannot be recreated by save`() = runTest(dispatcher) {
        val repo = Repository()
        val vm = ApiProfileEditorViewModel(repo, Gateway(), ApiProfileKind.LLM, "missing")
        runCurrent(); assertTrue(vm.state.value.loadFailed)
        vm.changeProfile { it.copy(url = "http://localhost/v1", model = "fixture") }; vm.save(); runCurrent()
        assertTrue(repo.profiles.value.isEmpty())
    }
    @Test fun `list is filtered by kind and supports copy delete`() = runTest(dispatcher) {
        val repo = Repository()
        repo.save(ApiProfile("llm", ApiProfileKind.LLM)); repo.save(ApiProfile("ocr", ApiProfileKind.OCR))
        val vm = ApiProfilesViewModel(repo, ApiProfileKind.LLM); runCurrent()
        assertEquals(listOf("llm"), vm.state.value.profiles.map { it.id })
        vm.duplicate("llm"); runCurrent(); assertEquals(2, vm.state.value.profiles.size)
        vm.delete("copy"); runCurrent(); assertEquals(listOf("llm"), vm.state.value.profiles.map { it.id })
    }
}
