package com.lmreader.ui.workflow

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Only an in-memory editor fixture is shown; no library source or user workflow is modified. */
class WorkflowEditorIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext())
    private var pausedBefore = true
    private var saved: TranslationWorkflow? = null

    @Before fun pauseQueue() = runBlocking {
        pausedBefore = container.translationQueue.paused.value
        container.translationQueue.pause()
        container.translationQueue.awaitCurrentPage()
        container.translationQueue.awaitResourceRelease()
        Assume.assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING") })
    }
    @After fun restoreQueue() { if (!pausedBefore) container.translationQueue.resume() }
    private fun show(program: WorkflowProgram) {
        val fixture = TranslationWorkflow("editor-fixture", 1, "猫爪编辑测试", "", TranslationPageMode.BUBBLE, 8, 0, program = program)
        compose.setContent { MaterialTheme { WorkflowEditorScreen(container, fixture, onDismiss = {}, onSave = { saved = it }) } }
    }
    private fun scroll(tag: String) {
        compose.onNodeWithTag("workflow-rows").performScrollToNode(hasTestTag(tag))
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Window removal/rotation animations are outside Compose's animation clock.
        Thread.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap)
        val output = File(container.applicationContext.filesDir, "workflow-editor-test/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun templateParametersScopeAndModeCanBeEditedWithoutLeavingTheTree() {
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5000) { container.applicationContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        show(WorkflowTemplates.blank())
        compose.onNodeWithContentDescription("工作流菜单").performClick()
        compose.onNodeWithText("填入模板").performClick()
        compose.onNodeWithText("SEG → OCR → 本地机翻").performClick()
        screenshot("tree")
        scroll("workflow-row:pages")
        compose.onNodeWithTag("workflow-mode:pages").performClick()
        scroll("workflow-row:ocr")
        compose.onNodeWithTag("workflow-row:ocr").performClick()
        compose.onNode(hasSetTextAction() and hasText("步骤名称（可选）")).performScrollTo().performTextReplacement("我的 OCR")
        screenshot("ocr-parameters")
        compose.onNodeWithText("完成").performClick()
        scroll("workflow-row:ocr")
        compose.onNode(hasContentDescription("此处可用变量") and hasAnyAncestor(hasTestTag("workflow-row:ocr"))).performClick()
        compose.onNodeWithText("此处可用变量").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("搜索变量或字段")).performTextInput("气泡 · 原文")
        compose.onNode(hasText("文本 · 气泡 · 原文") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screenshot("variables")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val program = requireNotNull(saved).program
            assertTrue(WorkflowValidator.validate(program).valid)
            assertEquals(WorkflowMode.SYNC, program.allNodes().single { it.id == "pages" }.mode)
            assertEquals("我的 OCR", program.allNodes().single { it.id == "ocr" }.label)
        }
    }

    @Test fun menuMovesAnIndependentRowToTheOuterScopeAndUndoRestoresIt() {
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5000) { container.applicationContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val variable = WorkflowVariable("custom-list", "自定义对照列表", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
        val row = WorkflowNode("custom-row", WorkflowKind.DECLARE, variable = variable)
        show(WorkflowEditing.insert(WorkflowTemplates.blank(), WorkflowPosition("pages", 0), row))
        val sourceTag = "workflow-row:custom-row"
        scroll(sourceTag)
        compose.onNode(hasContentDescription("步骤菜单") and hasAnyAncestor(hasTestTag(sourceTag))).performClick()
        compose.onNodeWithText("移到外层").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val moved = WorkflowEditing.flatten(requireNotNull(saved).program).single { it.node.id == row.id }
            assertEquals("chapters", moved.position.parentId)
            assertEquals(1, moved.position.index)
        }
        screenshot("menu-outer-scope")
        compose.onNodeWithContentDescription("撤销").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("pages", WorkflowEditing.flatten(requireNotNull(saved).program).single { it.node.id == row.id }.position.parentId) }
    }
    @Test fun fullReferenceExposesNestedRowsAndGeneratedCatSource() {
        show(WorkflowReferenceTemplates.fullManga("fixture"))
        scroll("workflow-row:ocr")
        compose.onNodeWithTag("workflow-row:ocr").assertIsDisplayed()
        scroll("workflow-row:prepare-manga")
        compose.onNodeWithTag("workflow-mode:prepare-manga").performClick()
        compose.onNodeWithTag("workflow-row:prepare-manga").performClick()
        compose.onNodeWithText("收集到列表（可选）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        scroll("workflow-row:full-manga-api")
        screenshot("full-api-summary")
        compose.onNodeWithTag("workflow-row:full-manga-api").performClick()
        compose.onNodeWithText("整漫画单请求").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("workflow-rows").performScrollToIndex(0)
        compose.onNodeWithContentDescription("工作流菜单").performClick()
        compose.onNodeWithText("查看猫爪文本").performClick()
        compose.onNode(hasText("整漫画一次请求", substring = true)).assertIsDisplayed()
        screenshot("full-cat-source")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertTrue(WorkflowValidator.validate(requireNotNull(saved).program).valid)
            assertEquals(WorkflowMode.SYNC, saved!!.program.allNodes().single { it.id == "prepare-manga" }.mode)
        }
    }
    @Test fun closingDirtyStepOffersSaveDiscardAndContinue() {
        show(WorkflowTemplates.blank())
        scroll("workflow-row:seg")
        compose.onNodeWithTag("workflow-row:seg").performClick()
        compose.onNode(hasSetTextAction() and hasText("步骤名称（可选）")).performScrollTo().performTextReplacement("待确认 SEG")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保存步骤修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("", saved!!.program.allNodes().single { it.id == "seg" }.label) }
    }
    @Test fun fixedWorkflowCardsOpenReadOnlyParameters() {
        val fixture = TranslationWorkflow("locked-fixture", 1, "固定测试", "", TranslationPageMode.BUBBLE, 8, 0, program = WorkflowTemplates.localMachine(), editable = false)
        compose.setContent { MaterialTheme { WorkflowEditorScreen(container, fixture, readOnly = true, onDismiss = {}, onSave = { fail("Fixed workflow was edited") }) } }
        scroll("workflow-row:ocr")
        compose.onNodeWithTag("workflow-row:ocr").performClick()
        compose.onNodeWithText("步骤名称（可选）").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("完成").assertDoesNotExist()
        screenshot("read-only-parameters")
        compose.onNodeWithText("关闭").performClick()
    }
    @Test fun promptVariableChipInsertsAtCursorAndAllSourcesAreDirectChoices() {
        show(WorkflowReferenceTemplates.standard("fixture"))
        scroll("workflow-row:standard-api")
        screenshot("api-summary")
        compose.onNodeWithTag("workflow-row:standard-api").performClick()
        val field = compose.onNode(hasSetTextAction() and hasText("提示词模板"))
        field.performScrollTo().performTextReplacement("前后")
        field.performTextInputSelection(TextRange(1))
        compose.onNode(hasText("文本 · 源语言") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        field.assertTextContains("前" + "$" + "{源语言}后")
        screenshot("prompt-variable-bar")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val prompt = saved!!.program.allNodes().single { it.id == "standard-api" }.inputs["prompt"] as WorkflowExpression.Template
            assertEquals("前" + "$" + "{源语言}后", prompt.text)
            assertEquals(WorkflowRef(WorkflowSystem.SOURCE), prompt.bindings["源语言"])
        }
    }
}
