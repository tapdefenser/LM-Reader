package com.lmreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.ReaderSettings

/**
 * 章节过渡页（Mihon `ChapterTransition` + `PagerTransitionHolder` / `ReaderTransitionView`）。
 *
 * ## 它解决什么问题
 *
 * 分页阅读器到了末页**不跨章翻页**——跨界的表达方式就是这一页。读者翻过它进入下一章，
 * 于是"多章节连续读"有了明确的落点；同时它也是预载失败的落点，可以在原地显示原因与
 * 重试，而不是把读者丢回上一页。
 *
 * ## 与 Mihon 的两处对齐细节
 *
 * 1. **没有目标章节时仍然显示过渡页**，文案是"已是最后一章"。Mihon 在列表端点同样插入
 *    过渡项（`to == null`），这是"到底了"的明确表达，不是错误状态。
 * 2. **过渡页永远可见**。上一版在"关闭始终显示过渡页"时会让已加载的相邻章直接接页，
 *    但预载窗口现在可以有很多章，读者根本无从判断某一章是"作品本来就到这"还是
 *    "还有更多但没预载"。因此改为总是显示，`pauseOnChapterTransition` 只决定到达之后
 *    停不停。
 *
 * 未实现（记录在此以免被当成遗漏）：Mihon 的完整版还在这里显示章节下载按钮、
 * 页码范围与封面缩略图。本项目没有下载子系统，因此只保留标题、状态与重试。
 */
@Composable
internal fun ChapterTransitionView(
    transition: ReaderItem.Transition,
    settings: ReaderSettings,
    onRetry: () -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
) {
    val target = transition.to

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            // 点按区域在过渡页上同样有效，否则读者会以为界面卡住了。
            .pointerTapNormalized(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val heading = when {
                target == null && transition.forward -> "已是最后一章"
                target == null -> "已是第一章"
                transition.forward -> "下一章"
                else -> "上一章"
            }
            Text(
                text = heading,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )

            if (target != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = target.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
            when (target?.state) {
                ViewerChapter.LoadState.LOADING, ViewerChapter.LoadState.WAITING -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "正在载入…",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                ViewerChapter.LoadState.FAILED -> {
                    Text(
                        text = "这一章载入失败",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRetry) { Text("重试") }
                }

                ViewerChapter.LoadState.Loaded, null -> {
                    // 加载完了但用户要求始终显示过渡：给一个可点的前进提示，
                    // 否则这一页看起来像死路。
                    if (transition.forward) {
                        Text(
                            text = "继续滑动进入下一章",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { onRetry() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 归一化的点按接入口。
 *
 * 过渡页是纯 Compose 组件（不像页面那样由图片引擎接管触摸），因此这里可以安全地
 * 用 Compose 手势；换算成归一化坐标后再交给同一套点按区域判定，保证"点哪都一样"。
 */
private fun Modifier.pointerTapNormalized(onTap: (Float, Float) -> Unit): Modifier = this.then(
    Modifier.pointerInput(onTap) {
        detectTapGestures { offset ->
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width <= 0f || height <= 0f) return@detectTapGestures
            onTap(offset.x / width, offset.y / height)
        }
    },
)
