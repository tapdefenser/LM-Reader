package com.lmreader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 带标题与当前值显示的滑杆。
 *
 * 抽到 common 是因为它同时被两处用：阅读器内的设置对话框，以及
 * 「设置 → 阅读器」页（后者放的是"只能在打开阅读器之前调"的参数）。
 * 两处要是各写一份，迟早会出现"同一个参数在两页里手感不一样"。
 *
 * @param display 右侧显示的当前值文本（含单位）；由调用方决定，因为单位不一定是同一个
 * @param steps 中间刻度数 = 取值个数 - 2（Material 滑杆的约定）
 */
@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
