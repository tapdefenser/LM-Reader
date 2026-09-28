package com.lmreader.ui.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.di.AppContainer

/**
 * 翻译语言设置（跟着**漫画**走，从详情页右上角 ⋮ 进）。
 *
 * ## 形状（用户口径）
 *
 * 源语言与目标语言"左右各是一个选择列表"，每个列表**表尾有一个输入框**，可以输入
 * 任何语言（"我们要能够甚至支持任意语言的翻译"）。因此两项用同一个选择面板：
 * 预设项 + 自动识别（仅源语言）+ 表尾自定义输入。
 *
 * ## 与全局默认的关系
 *
 * 每一层都可以"留空 = 跟随全局"（见 [com.lmreader.core.model.MangaTranslationSettings]）。
 * 所以面板第一项是「跟随全局默认（现在：日语 / 简体中文）」，**不是**把全局值复制到漫画上——
 * 复制之后用户改全局默认，这部漫画就悄悄不跟了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationLanguageScreen(
    container: AppContainer,
    mangaId: String,
    onBack: () -> Unit,
    viewModel: TranslationLanguageViewModel = viewModel(
        key = "translation-language-$mangaId",
        factory = TranslationLanguageViewModel.factory(container, mangaId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LanguageField?>(null) }

    editing?.let { field ->
        LanguagePickerSheet(
            field = field,
            state = state,
            onPickGlobal = {
                when (field) {
                    LanguageField.SOURCE -> viewModel.setSourceLanguage("")
                    LanguageField.TARGET -> viewModel.setTargetLanguage("")
                }
                editing = null
            },
            onPickAutoDetect = {
                viewModel.setAutoDetectSource(true)
                editing = null
            },
            onPick = { language ->
                when (field) {
                    LanguageField.SOURCE -> viewModel.setSourceLanguage(language)
                    LanguageField.TARGET -> viewModel.setTargetLanguage(language)
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译语言") },
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
                text = "这部作品的翻译语言。留空 = 跟随全局默认；改全局默认时这里会跟着变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            // 源 → 目标，左右并排（用户口径）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageBox(
                    label = "源语言",
                    value = if (state.settings.autoDetectSource) {
                        "自动识别"
                    } else {
                        state.settings.sourceLanguage ?: "跟随全局（${state.globalSource}）"
                    },
                    hint = "正文是什么语言",
                    modifier = Modifier.weight(1f),
                    onClick = { editing = LanguageField.SOURCE },
                )
                Text("→", style = MaterialTheme.typography.titleLarge)
                LanguageBox(
                    label = "目标语言",
                    value = state.settings.targetLanguage ?: "跟随全局（${state.globalTarget}）",
                    hint = "译成什么语言",
                    modifier = Modifier.weight(1f),
                    onClick = { editing = LanguageField.TARGET },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = "当前生效：源 ${state.effectiveSourceLabel} → 目标 ${state.effectiveTarget}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = "译名字典按「漫画 × 目标语言」分开存：换目标语言等于换一套字典，" +
                    "已录入的译名不会丢，但要在新语言下重新录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 源/目标语言（两者共用一套面板，只是存到不同字段）。 */
internal enum class LanguageField { SOURCE, TARGET }

/**
 * 语言预设。
 *
 * 只放常见项：源语言按开发文档 TR04 的枚举（日/英/韩/简繁/中英混合/法/西/葡/德/意/俄），
 * 目标语言按 TR05。**其余语言靠面板表尾的自定义输入**——用户要的是"甚至支持任意语言"，
 * 因此预设表短一点比长一点更好维护，也不会让人以为"只能在这些里选"。
 */
internal val SOURCE_LANGUAGE_PRESETS = listOf(
    "日语", "英语", "韩语", "简体中文", "繁体中文", "中英混合",
    "法语", "西班牙语", "葡萄牙语", "德语", "意大利语", "俄语",
)

internal val TARGET_LANGUAGE_PRESETS = listOf(
    "简体中文", "繁体中文", "英语", "日语", "韩语", "俄语", "巴西葡语",
)

@Composable
private fun LanguageBox(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 语言选择面板：预设列表 + 表尾自定义输入（用户口径："表尾增加输入框"）。
 *
 * 自定义输入存的是用户敲的那串文字本身，不做规范化：它将来直接进翻译请求，
 * 而"乌克兰语"这类语言名不可能在预设表里穷举。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    field: LanguageField,
    state: TranslationLanguageUiState,
    onPickGlobal: () -> Unit,
    onPickAutoDetect: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val presets = if (field == LanguageField.SOURCE) SOURCE_LANGUAGE_PRESETS else TARGET_LANGUAGE_PRESETS
    val current = when (field) {
        LanguageField.SOURCE -> state.settings.sourceLanguage
        LanguageField.TARGET -> state.settings.targetLanguage
    }
    var custom by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = if (field == LanguageField.SOURCE) "源语言" else "目标语言",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
            )

            LanguageOption(
                label = when (field) {
                    LanguageField.SOURCE -> "跟随全局默认（现在：${state.globalSource}）"
                    LanguageField.TARGET -> "跟随全局默认（现在：${state.globalTarget}）"
                },
                selected = current == null && !(field == LanguageField.SOURCE && state.settings.autoDetectSource),
                onClick = onPickGlobal,
            )

            if (field == LanguageField.SOURCE) {
                LanguageOption(
                    label = "自动识别",
                    hint = "不使用固定源语言，由识别器判断（更贵；识别错一次会连累整章）",
                    selected = state.settings.autoDetectSource,
                    onClick = onPickAutoDetect,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            presets.forEach { language ->
                LanguageOption(
                    label = language,
                    selected = current == language,
                    onClick = { onPick(language) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "其它语言（自己输入，输入什么就存什么）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    singleLine = true,
                    label = { Text("例如：乌克兰语") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onPick(custom) },
                    enabled = custom.isNotBlank(),
                ) { Text("使用") }
            }
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = hint?.let { text ->
            { Text(text, style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
