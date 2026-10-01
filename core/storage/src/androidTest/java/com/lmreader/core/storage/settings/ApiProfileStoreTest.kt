package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 独立测试文件，不读取或写入应用的 API 配置、图库与队列。 */
@RunWith(AndroidJUnit4::class)
class ApiProfileStoreTest {
    @Test fun encryptedPersistenceCrudAndConcurrentSaves() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "api-test-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val repository = ApiProfileStore(context, store)
            val profile = ApiProfile("fixture", ApiProfileKind.LLM, name = "合成测试", url = "http://localhost:1234/v1", apiKey = "secret-fixture-123", model = "fixture", thinkingEnabled = true)
            repository.save(profile)
            assertEquals(profile, repository.profiles.first().single())
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(profile.apiKey))
            val reopened = ApiProfileStore(context, store, KeystoreApiSecretCipher())
            assertEquals(profile, reopened.profiles.first().single())
            reopened.save(profile.copy(timeoutSeconds = 120, model = "updated"))
            val copied = reopened.duplicate(profile.id)
            assertNotEquals(profile.id, copied.id)
            assertEquals("secret-fixture-123", copied.apiKey)
            assertEquals("updated", copied.model)
            assertEquals(2, reopened.profiles.first().size)
            reopened.delete(copied.id)
            coroutineScope { (1..8).map { n -> async { reopened.save(profile.copy(id = "parallel-$n", kind = ApiProfileKind.OCR)) } }.awaitAll() }
            assertEquals(9, reopened.profiles.first().size)
            reopened.delete(profile.id)
            assertTrue(reopened.profiles.first().all { it.kind == ApiProfileKind.OCR })
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); file.delete() }
    }

    @Test fun corruptedConfigurationCannotBeSilentlyOverwritten() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "api-corruption-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val key = stringPreferencesKey("profiles_v1")
        try {
            store.edit { it[key] = "broken-json" }
            val repository = ApiProfileStore(context, store)
            assertTrue(runCatching { repository.profiles.first() }.isFailure)
            assertTrue(runCatching { repository.save(ApiProfile("fixture", ApiProfileKind.LLM, url = "http://localhost/v1", model = "fixture")) }.isFailure)
            assertEquals("broken-json", store.data.first()[key])
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); file.delete() }
    }
}
