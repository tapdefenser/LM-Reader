package com.lmreader

import android.os.Bundle
import android.os.LocaleList
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lmreader.di.AppContainer
import com.lmreader.ui.navigation.LmReaderNavHost
import com.lmreader.ui.theme.LmReaderTheme
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.core.storage.settings.AppThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 唯一 Activity 宿主。
 *
 * 页面切换全部交给 Compose 导航（`ui/navigation`）；本类只负责主题与内容根，
 * 不持有任何业务状态——这样旋转屏幕、进程恢复都由 Compose 与 ViewModel 处理
 * （开发文档 3「系统恢复当前 Activity 状态时可恢复原页面」）。
 */
class MainActivity : ComponentActivity() {
    private val requestedQueue = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedQueue.value = intent.getStringExtra("open-queue")
    }
    override fun onStart() {
        super.onStart()
        val container = AppContainer.from(this)
        container.taskService.setVisible(true)
        container.translationQueue.start()
        container.exportQueue.start()
    }
    override fun onStop() {
        AppContainer.from(this).taskService.setVisible(false)
        super.onStop()
    }

    override fun attachBaseContext(newBase: Context) {
        val tag = GeneralPreferences(newBase).effectiveLanguageTag
        val config = Configuration(newBase.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = AppContainer.from(this)
        requestedQueue.value = intent.getStringExtra("open-queue")
        setContent {
            val queue by requestedQueue.collectAsStateWithLifecycle()
            val theme by container.generalPreferences.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LmReaderTheme(darkTheme = dark) {
                LmReaderNavHost(container = container, requestedQueue = queue,
                    onQueueOpened = { requestedQueue.value = null; intent.removeExtra("open-queue") })
            }
        }
    }
}
