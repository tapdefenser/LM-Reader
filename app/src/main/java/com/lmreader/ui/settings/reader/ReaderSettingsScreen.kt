package com.lmreader.ui.settings.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.model.ReaderSettings
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.LabeledSlider
import kotlinx.coroutines.launch

/**
 * 设置 →「阅读器」：**只在打开阅读器之前生效**的那些参数。
 *
 * ## 为什么这两项不放在阅读中的设置面板里
 *
 * - 「预载页数」决定阅读器**打开时**要预取多少字节（`PagePrefetcher` 的磁盘预取格数）；
 * - 「缓存章节数」决定阅读器**在内存里保留多少章的页清单**（也就是显示窗口有多大）。
 *
 * 两者都在 `ReaderViewModel.init` 之后按当时读到的设置生效，阅读中改动不会追溯生效。
 * 放在阅读中的面板里会让人以为"拉一下立刻变快"，所以挪到这里，入口只此一处。
 *
 * 阅读中的那套设置面板仍然保留：方向、连续模式、缩放、裁白边、音量键、亮度、点按区域
 * ——那些改一下就能立刻看到效果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val preferences = container.readerPreferences
    // 首帧用默认值渲染即可：这一页是"读当前值 + 改它"，没有需要等待的异步决策，
    // 因此不需要像导航那样先挂起再渲染。
    val settings by preferences.settings.collectAsStateWithLifecycle(
        initialValue = ReaderSettings(),
    )
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "这里的改动会在「下次打开」阅读器时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            HorizontalDivider()
            LabeledSlider(
                label = "预载页数",
                value = settings.preloadPages.toFloat(),
                range = ReaderSettings.PRELOAD_PAGES_MIN.toFloat()..
                    ReaderSettings.PRELOAD_PAGES_MAX.toFloat(),
                steps = stepCount(ReaderSettings.PRELOAD_PAGES_MIN, ReaderSettings.PRELOAD_PAGES_MAX),
                display = "${settings.preloadPages} 页",
                modifier = Modifier.padding(top = 12.dp),
                hint = "从当前页往前/往后各预读多少页（章节过渡页也算一页）。" +
                    "它决定翻页要不要等磁盘读取，也是「内存里同时保留几张已解码图」的依据。",
            ) { value ->
                scope.launch { preferences.update { it.copy(preloadPages = value.toInt()) } }
            }

            LabeledSlider(
                label = "缓存章节数（前后各）",
                value = settings.cachedChaptersPerSide.toFloat(),
                range = ReaderSettings.CACHED_CHAPTERS_MIN.toFloat()..
                    ReaderSettings.CACHED_CHAPTERS_MAX.toFloat(),
                steps = stepCount(
                    ReaderSettings.CACHED_CHAPTERS_MIN,
                    ReaderSettings.CACHED_CHAPTERS_MAX,
                ),
                display = "${settings.cachedChaptersPerSide} 章",
                modifier = Modifier.padding(top = 16.dp),
                hint = "当前章前后各保留多少章的页清单（纯元数据，每章几十 KB 量级）。" +
                    "缓存越大，往回翻、往回跳章越不用重新读目录；调小后会按「离当前章最远」" +
                    "的顺序释放，当前章与相邻章永不清除。",
            ) { value ->
                scope.launch {
                    preferences.update { it.copy(cachedChaptersPerSide = value.toInt()) }
                }
            }
        }
    }
}

/** Material 滑杆的 `steps` 约定：中间刻度数 = 取值个数 - 2。 */
private fun stepCount(min: Int, max: Int): Int = (max - min - 1).coerceAtLeast(0)
