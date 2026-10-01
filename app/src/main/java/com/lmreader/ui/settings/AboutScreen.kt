package com.lmreader.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lmreader.core.api.GitHubUpdates
import com.lmreader.core.api.ProjectLinks
import com.lmreader.core.api.UpdateStatus
import com.lmreader.ui.i18n.Icon
import com.lmreader.ui.i18n.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    val updates = remember { GitHubUpdates() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateStatus?>(null) }
    var checkFailed by remember { mutableStateOf(false) }
    var browserFailed by remember { mutableStateOf(false) }
    fun openPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            browserFailed = false
        } catch (_: ActivityNotFoundException) { browserFailed = true }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("关于") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("LM-Reader", style = MaterialTheme.typography.headlineLarge, localize = false)
            Text("本地漫画阅读与翻译", style = MaterialTheme.typography.bodyLarge)
            Text("版本", style = MaterialTheme.typography.labelLarge)
            Text(version, localize = false)
            OutlinedButton(onClick = { openPage(ProjectLinks.GITHUB) }, modifier = Modifier.fillMaxWidth()) {
                Text("GitHub 项目主页")
            }
            SelectionContainer { Text(ProjectLinks.GITHUB, localize = false, style = MaterialTheme.typography.bodySmall) }
            Button(enabled = !checking, modifier = Modifier.fillMaxWidth(), onClick = {
                checking = true; result = null; checkFailed = false
                scope.launch {
                    try { result = updates.check(version) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { checkFailed = true }
                    finally { checking = false }
                }
            }) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checking) "正在检查更新" else "检查更新")
            }
            when (val status = result) {
                UpdateStatus.NoPublicRelease -> Text("尚无可公开访问的正式版本")
                UpdateStatus.AccessRestricted -> Text("GitHub 暂时限制访问，请稍后重试")
                is UpdateStatus.Release -> {
                    Text(when (status.isNewer) {
                        true -> "发现新版本"
                        false -> "当前已是最新版本"
                        null -> "已获取发行版本，请到 GitHub 确认"
                    })
                    Text(status.version, localize = false)
                    OutlinedButton(onClick = { openPage(status.page) }) { Text("查看发行页面") }
                }
                null -> Unit
            }
            if (checkFailed) Text("检查更新失败，请检查网络后重试")
            if (browserFailed) Text("无法打开浏览器，请手动访问 GitHub")
            Text("检查 GitHub 上最新的正式发行版本。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
