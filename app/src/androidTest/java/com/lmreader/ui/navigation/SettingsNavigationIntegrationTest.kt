package com.lmreader.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Run with an isolated application ID; exercises the real menu and NavHost. */
class SettingsNavigationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext())
    private var completed = false

    @Before fun prepare() = runBlocking {
        container.startupReady.await()
        completed = container.preferences.onboardingCompleted.first()
        container.preferences.setOnboardingCompleted(true)
    }

    @After fun restore() = runBlocking { container.preferences.setOnboardingCompleted(completed) }

    @Test fun settingsChildrenKeepTheirParentForToolbarAndSystemBack() {
        compose.setContent { MaterialTheme { LmReaderNavHost(container, rememberNavController()) } }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("主菜单").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("主菜单").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("导出设置").performScrollTo().performClick()
        compose.onNodeWithText("选择导出目录").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("通用").assertIsDisplayed()

        compose.onNodeWithText("导出设置").performScrollTo().performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("通用").assertIsDisplayed()

        for (child in listOf("阅读器", "图库与路径")) {
            compose.onNodeWithText(child).performScrollTo().performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("通用").assertIsDisplayed()
        }

        compose.onNodeWithText("API 与翻译引擎").performScrollTo().performClick()
        compose.onNodeWithText("SEG 配置").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("LLM 配置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("通用").assertIsDisplayed()
    }
}
