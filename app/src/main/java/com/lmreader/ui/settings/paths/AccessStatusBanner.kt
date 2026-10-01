package com.lmreader.ui.settings.paths

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.WarningAmber
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 授权状态横幅（用户要求：每次启动都要有授权检测，并把结论显示出来）。
 *
 * 两种状态用同一个组件呈现，区别只在文案与动作：
 * - 未获得「全部文件访问」：这是**可选项**，不授权也能用系统选择器逐目录授权，
 *   因此语气是说明而不是报错，并且给出"去开启"与"了解详情"两个入口；
 * - 有路径授权失效：这是**必须处理**的问题，用错误色，动作是"查看是哪条"。
 *
 * 刻意不做成可关闭的 Toast：授权状态是持续状态而不是一次性事件，
 * 关掉之后用户下次仍然需要知道为什么图库是空的。
 */
@Composable
fun AccessStatusBanner(
    message: String?,
    isError: Boolean,
    onAction: () -> Unit,
    actionLabel: String,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(color = containerColor, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.WarningAmber else Icons.Filled.LockOpen,
                contentDescription = null,
                tint = contentColor,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    TextButton(onClick = onSecondaryAction) {
                        Text(secondaryActionLabel, color = contentColor)
                    }
                }
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = contentColor)
            }
        }
    }
}
