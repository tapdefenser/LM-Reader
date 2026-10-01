package com.lmreader.ui.workflow

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.di.AppContainer
import com.lmreader.ui.settings.api.ApiLogScreen
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkflowOverviewIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext())
    private fun screenshot(name: String) {
        compose.waitForIdle(); Thread.sleep(350)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(container.applicationContext.filesDir, "workflow-editor-test/$name.png")
        file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun overviewHasImportExportAndMangaJournalOpensActualRequest() {
        val logs = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if(logs.value) ApiLogScreen(container, onBack = {}) else TranslationWorkflowScreen(container, onBack = {})
        } }
        compose.onNodeWithContentDescription("导入工作流").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("导出工作流").onFirst().assertIsDisplayed()
        screenshot("workflow-overview")
        val record = container.apiLogs.records.value.firstOrNull()
        assumeTrue(record != null)
        compose.runOnIdle { logs.value = true }
        compose.onNodeWithText("API 日志").assertIsDisplayed()
        val tag = "api-log:" + requireNotNull(record).id
        compose.onNodeWithTag("api-logs").performScrollToNode(hasTestTag(tag))
        screenshot("api-journal")
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithText("输入").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("输出").performScrollTo().assertIsDisplayed()
        screenshot("api-request-detail")
    }
}
