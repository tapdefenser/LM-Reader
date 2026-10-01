package com.lmreader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import com.lmreader.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 顶栏里的搜索框：**胶囊形、比 Material 默认更扁**。
 *
 * ## 为什么不用 `OutlinedTextField`
 *
 * 它有两个改不动的地方，正好是这里唯二要改的：
 * 1. **形状**是描边圆角矩形，描边会把顶栏切成一块"表单"，不是"一条搜索栏"；
 * 2. **高度**由 `TextFieldDefaults.MinHeight`（56dp）兜底，`Modifier.height` 更小会
 *    被内部 `defaultMinSize` 顶回去，于是顶栏被撑高、标题栏的视觉比重被抢掉。
 *
 * 因此这里用 [BasicTextField] 自绘外框：高度与圆角都由我们定，其余行为（单行、
 * 文字在框内左右拖动、IME 搜索键）与原来的输入框一致。
 *
 * ## 一个必须保留的约束
 *
 * **`value` 只由调用方的 `onValueChange` 同步更新。** 两个页面都对输入做了防抖，
 * 若把防抖之后的文本写回 `value`，用户连打几个字符时那次延迟发射会带着旧文本覆盖
 * 输入框并把光标弹回开头（真机症状："每次只能输入一个字符"）。本组件不持有文本状态，
 * 也不做任何回写，因此这个坑不会经由它复活。
 *
 * @param onSearch IME 上的搜索键。**不传时保持 IME 的默认行为（收起键盘）**——
 *   传一个空实现反而会把这次按键吃掉，键盘不再收起。
 */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        maxLines = 1,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = onSearch?.let { action -> KeyboardActions(onSearch = { action() }) }
            ?: KeyboardActions.Default,
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 比 56dp 的默认高度矮一截：顶栏里的搜索框是"一条"，不是一块输入区。
                    .height(SEARCH_FIELD_HEIGHT)
                    // `percent = 50` 让圆角恒等于高度的一半，即无论以后把高度调成多少，
                    // 它都是一个胶囊，而不需要跟着改半径。
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = SEARCH_FIELD_H_PADDING),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 搜索框高度。默认输入框是 56dp，这里压扁到 40dp。 */
private val SEARCH_FIELD_HEIGHT = 40.dp

/** 左右内边距；胶囊两端的留白要比矩形输入框大一点才不显得挤。 */
private val SEARCH_FIELD_H_PADDING = 16.dp
