package com.lmreader.ui.workflow

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.TranslationPageMode
import com.lmreader.core.model.TranslationWorkflow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranslationWorkflowStoreTest {
    @Test fun importedFixedWorkflowAllowsOnlyApiRebindingAndPreservesFlagOnCopyAndExport() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = File(cache, "workflow-fixture-${UUID.randomUUID()}")
        try {
            val store = TranslationWorkflowStore(root)
            val external = com.lmreader.core.workflow.WorkflowFileCodec.decode(com.lmreader.core.workflow.WorkflowFileCodec.encode(TranslationWorkflow.STANDARD_API.copy(editable = false)))
            val fixed = store.import(external)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.update(fixed.copy(name = "changed")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.update(fixed.copy(editable = true)) } }
            val rebound = store.bindApi(fixed.id, "local-api")
            org.junit.Assert.assertFalse(rebound.editable)
            org.junit.Assert.assertTrue(rebound.program.allNodes().filter { it.kind == com.lmreader.core.model.WorkflowKind.API }.all { it.inputs["profile"] == com.lmreader.core.model.WorkflowExpression.Text("local-api") })
            assertEquals(rebound, TranslationWorkflowStore(root).find(fixed.id))
            org.junit.Assert.assertFalse(store.copy(fixed.id).editable)
            org.junit.Assert.assertFalse(com.lmreader.core.workflow.WorkflowFileCodec.decode(store.export(fixed.id)).editable)
        } finally {
            if(root.canonicalFile.parentFile == cache && root.name.startsWith("workflow-fixture-")) root.deleteRecursively()
        }
    }
    @Test fun allThreeReferenceProgramsCanBeCopiedBoundAndRestored() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = File(cache, "workflow-fixture-${UUID.randomUUID()}")
        try {
            val store = TranslationWorkflowStore(root)
            assertEquals(4, store.workflows.value.size)
            TranslationWorkflow.BUILT_INS.drop(1).forEach { reference ->
                assertEquals(reference, store.find(reference.id))
                assertThrows(IllegalArgumentException::class.java) { runBlocking { store.delete(reference.id) } }
                val copy = store.copy(reference.id)
                val changed = store.update(copy.copy(program = com.lmreader.core.model.WorkflowReferenceTemplates.bindApi(copy.program, "fixture")))
                assertEquals(changed, TranslationWorkflowStore(root).find(copy.id))
                org.junit.Assert.assertTrue(com.lmreader.core.workflow.WorkflowValidator.validate(changed.program).valid)
                assertEquals(reference, store.find(reference.id))
            }
        } finally {
            if(root.canonicalFile.parentFile == cache && root.name.startsWith("workflow-fixture-")) root.deleteRecursively()
        }
    }
    @Test fun immutableBuiltInAndEditableCopyPersist() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = File(cache, "workflow-fixture-${UUID.randomUUID()}")
        try {
            val store = TranslationWorkflowStore(root)
            assertEquals(TranslationWorkflow.LOCAL_MACHINE, store.find(null))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                store.delete(TranslationWorkflow.LOCAL_MACHINE_ID)
            } }
            val copy = store.copy(TranslationWorkflow.LOCAL_MACHINE_ID)
            val updated = store.update(copy.copy(pageMode = TranslationPageMode.BUBBLE, parallelLimit = 2))
            assertEquals(2, updated.revision)
            assertEquals(TranslationPageMode.BUBBLE, TranslationWorkflowStore(root).find(copy.id)?.pageMode)
            store.delete(copy.id)
            assertNull(TranslationWorkflowStore(root).find(copy.id))
        } finally {
            if (root.canonicalFile.parentFile == cache && root.name.startsWith("workflow-fixture-")) root.deleteRecursively()
        }
    }
}
