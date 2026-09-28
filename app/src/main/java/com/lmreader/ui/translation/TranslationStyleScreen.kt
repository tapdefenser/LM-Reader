package com.lmreader.ui.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.StyleMode
import com.lmreader.di.AppContainer

/**
 * 文风设置：**覆盖**关系（漫画 → 分类 → 全局），不是继承链。
 *
 * 用户口径："如果漫画设置了就用漫画的，如果漫画的留空了就用分类的，如果分类的还留空
 * 就用全局的"。因此页面上三件事都要给出来：
 * 1. 这一层怎么选（用自定义 / 跟随分类）；
 * 2. 每一层**现在**是什么（漫画的文本、分类的名字与文本、全局的文本）；
 * 3. **最终生效的是哪一层**（[TranslationStyleUiState.effectiveSourceLabel]）——
 *    覆盖链最容易出的问题就是用户看到一段文风却不知道它从哪来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationStyleScreen(
    container: AppContainer,
    mangaId: String,
    onBack: () -> Unit,
    viewModel: TranslationStyleViewModel = viewModel(
        key = "translation-style-$mangaId",
        factory = TranslationStyleViewModel.factory(container, mangaId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文风设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "文风就是发给模型的翻译要求。这里是覆盖关系：漫画留空 → 用分类的；" +
                    "分类也留空 → 用全局的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            // 整行都可点，而不是只有左边那个小圆钮：行里有一句解释文字，用户很自然会点它。
            ListItem(
                headlineContent = { Text("这部作品使用自定义文风") },
                supportingContent = {
                    Text("选中后下面的文本框生效；文本留空则仍然往下回退", style = MaterialTheme.typography.bodySmall)
                },
                leadingContent = {
                    RadioButton(
                        selected = state.settings.styleMode == StyleMode.CUSTOM,
                        onClick = { viewModel.setMode(StyleMode.CUSTOM) },
                    )
                },
                modifier = Modifier.clickable { viewModel.setMode(StyleMode.CUSTOM) },
            )
            ListItem(
                headlineContent = { Text("跟随分类与全局") },
                supportingContent = {
                    Text(
                        text = state.categoryName?.let { "先看分类「$it」，它没设就用全局默认" }
                            ?: "这部作品不在书架，没有分类这一层，直接用全局默认",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = state.settings.styleMode != StyleMode.CUSTOM,
                        onClick = { viewModel.setMode(StyleMode.GLOBAL) },
                    )
                },
                modifier = Modifier.clickable { viewModel.setMode(StyleMode.GLOBAL) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            OutlinedTextField(
                value = state.settings.customStyle.orEmpty(),
                onValueChange = viewModel::setCustomStyle,
                label = { Text("这部作品的文风") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("最终生效")
            Text(
                text = "${state.effectiveSourceLabel}：\n${state.effectiveStyle}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("各层现在的值")
            Text(
                text = buildString {
                    append("· 这部作品：")
                    append(
                        if (state.settings.styleMode == StyleMode.CUSTOM &&
                            !state.settings.customStyle.isNullOrBlank()
                        ) {
                            "已设置"
                        } else {
                            "留空"
                        },
                    )
                    append('\n')
                    append("· 分类「${state.categoryName ?: "无"}」：")
                    append(state.categoryStyle?.let { "已设置" } ?: "留空")
                    append('\n')
                    append("· 全局默认：已设置")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
    )
}
