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
 * ## 它是什么
 *
 * 夹在两章之间的一张"图"。它在项列表里**占一格**，和普通页面没有本质区别——只是显示
 * 的不是图片而是"已读完哪一章 / 下面是哪一章"。因此从上一章末页到下一章首页要翻**两次**，
 * 这是设计意图（用户明确要求）；想一次翻过去就在设置里关掉「章节过渡页」。
 *
 * 它同时也是预载失败的落点：可以在原地显示原因与重试，而不是把读者丢回上一页。
 *
 * ## 它没有方向
 *
 * 往前翻会遇到它、往后翻会遇到同一个它（[ReaderItem.Transition] 里没有 `forward`）。
 * 它不表达"正在进入下一章"这个动作，只表达"这里是前一章与后一章之间"。
 *
 * ## 与 Mihon 的对齐
 *
 * **没有目标章节时仍然显示过渡页**，文案是"已是最后一章"。Mihon 在列表端点同样插入
 * 过渡项（`to == null`），这是"到底了"的明确表达，不是错误状态。
 *
 * ## 它没有按钮
 *
 * 用户明确要求：过渡页上没有任何按钮，它就是一张正常的图。因此这里不含任何操作入口
 * （除了载入失败时的重试）；页码相关的控件由 [ReaderBottomBar] 置零置灰。
 *
 * 未实现（记录在此以免被当成遗漏）：Mihon 的完整版还在这里显示章节下载按钮、
 * 页码范围与封面缩略图。本项目没有下载子系统，因此只保留标题与状态。
 */
@Composable
internal fun ChapterTransitionView(
    transition: ReaderItem.Transition,
    settings: ReaderSettings,
    onRetry: () -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
) {
    val target = transition.to
    val source = transition.from

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
            val heading = if (target == null) "已是最后一章" else "下一章"
            Text(
                text = heading,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )

            // 显示"已读完哪一章 → 下面是哪一章"，让读者一眼看出中间夹了什么。
            if (source != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已读完 ${source.title}",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

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
                ViewerChapter.LoadState.LOADING -> {
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

                ViewerChapter.LoadState.Loaded, null -> Unit
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
