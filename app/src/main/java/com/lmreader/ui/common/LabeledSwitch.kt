package com.lmreader.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 带说明文字的开关行。
 *
 * 抽到 common 的理由与 [LabeledSlider] 相同：「阅读器内的设置面板」与
 * 「设置 → 阅读器」两处都要放同一批开关，各写一份迟早会出现两页手感不一致。
 *
 * @param hint 第二行的说明文字；像「加载原图」这类开关的代价不写清楚，用户只会
 *   在内存吃紧时才发现自己开了它
 */
@Composable
internal fun LabeledSwitch(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
            )
        }
    }
}
