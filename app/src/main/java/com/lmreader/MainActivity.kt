package com.lmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lmreader.di.AppContainer
import com.lmreader.ui.navigation.LmReaderNavHost
import com.lmreader.ui.theme.LmReaderTheme

/**
 * 唯一 Activity 宿主。
 *
 * 页面切换全部交给 Compose 导航（`ui/navigation`）；本类只负责主题与内容根，
 * 不持有任何业务状态——这样旋转屏幕、进程恢复都由 Compose 与 ViewModel 处理
 * （开发文档 3「系统恢复当前 Activity 状态时可恢复原页面」）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = AppContainer.from(this)
        setContent {
            LmReaderTheme {
                LmReaderNavHost(container = container)
            }
        }
    }
}
