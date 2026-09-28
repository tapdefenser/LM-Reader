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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.di.AppContainer

/**
 * 翻译设置（**一个页面**：语言 + 文风 + 译名入口 + 进度说明；用户口径）。
 *
 * 形状上的三个刻意选择：
 * 1. **语言不给缺省值**："用户没有设置过的时候你要设置成缺省，这样相当于首次启动就是
 *    要求用户填入了"——所以没设过时显示「未设置」，而不是替他填一个"简体中文"；
 * 2. **文风就是一个输入框**："点开就是一个输入框类似于 input()，如果留空就是自动应用
 *    分类"——因此没有单选、没有各层现值表，只在下面用一行说明当前生效的是哪一层；
 * 3. **译名管理在这里**（一行入口），不再挂在详情页 ⋮ 上。
 *
 * @param showSetupPrompt 用户是"想翻译但设置不全"被带进来的。此时弹一句提示——**这条提示
 *   必须在这里弹**：详情页那句 snackbar 会随导航把详情页移出组合而立刻消失，用户看不到。
 *   提示里明确写"填好后返回重新发起"，因为按用户口径**不自动续跑**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSettingsScreen(
    container: AppContainer,
    mangaId: String,
    onBack: () -> Unit,
    onOpenGlossary: () -> Unit,
    showSetupPrompt: Boolean = false,
    viewModel: TranslationSettingsViewModel = viewModel(
        key = "translation-settings-$mangaId",
        factory = TranslationSettingsViewModel.factory(container, mangaId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LanguageField?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 只在**首次进入**时弹一次：rememberSaveable 记住已弹过，用户改完设置回来
    // （同一 entry 重组）不会再弹第二遍。
    //
    // `promptShown` 不能作为 LaunchedEffect 的 key：那样它从 false 变 true 会重启效果，
    // 把刚亮起的 showSnackbar 协程取消掉，表现为提示一闪即逝（甚至完全看不到）。
    var promptShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showSetupPrompt) {
        if (showSetupPrompt && !promptShown) {
            promptShown = true
            snackbarHostState.showSnackbar("请先完成翻译设置，填好后返回章节列表重新发起翻译")
        }
    }

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
                title = { Text("翻译设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!state.languageConfigured) {
                Text(
                    text = "翻译语言还没设置：开始翻译之前请先选好源语言与目标语言。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            SectionTitle("翻译语言")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageBox(
                    label = "源语言",
                    value = state.sourceLabel,
                    hint = "正文是什么语言",
                    modifier = Modifier.weight(1f),
                    onClick = { editing = LanguageField.SOURCE },
                )
                Text("→", style = MaterialTheme.typography.titleLarge)
                LanguageBox(
                    label = "目标语言",
                    value = state.targetLabel,
                    hint = "译成什么语言",
                    modifier = Modifier.weight(1f),
                    onClick = { editing = LanguageField.TARGET },
                )
            }
            Text(
                text = "留空 = 跟随全局默认；全局也没设过时这里是「未设置」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("文风")
            OutlinedTextField(
                value = state.settings.customStyle.orEmpty(),
                onValueChange = viewModel::setStyle,
                label = { Text("这部作品的文风") },
                placeholder = { Text("留空 = 自动使用分类的文风") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Text(
                text = state.effectiveStyleSource,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("译名")
            ListItem(
                headlineContent = { Text("译名管理") },
                supportingContent = {
                    Text(
                        text = "这部作品的原词 → 译名对照表，人工录入的不会被自动抽取覆盖",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onOpenGlossary),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("还没做（P3）")
            Bullet("模型资产：检测 / OCR / 主模型的来源、校验和与下载流程")
            Bullet("执行：任务调度、进度与失败重试（现在只排队，不会真的开始翻译）")
            Bullet("主 AI 配置：接口地址、密钥、模型、请求格式、超时与并发")
            Bullet("页面与气泡：检测结果、遮罩样式与字体、译文叠加显示")
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 源/目标语言（两者共用一套面板，只是存到不同字段）。 */
internal enum class LanguageField { SOURCE, TARGET }

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
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == "未设置") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
        )
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
    state: TranslationSettingsUiState,
    onPickGlobal: () -> Unit,
    onPickAutoDetect: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val presets = if (field == LanguageField.SOURCE) SOURCE_LANGUAGE_PRESETS else TARGET_LANGUAGE_PRESETS
    val globalValue = if (field == LanguageField.SOURCE) state.globalSource else state.globalTarget
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
                label = globalValue?.let { "跟随全局默认（现在：$it）" } ?: "跟随全局默认（全局还没设置）",
                selected = current == null && !(field == LanguageField.SOURCE && state.autoDetectSource),
                onClick = onPickGlobal,
            )

            if (field == LanguageField.SOURCE) {
                LanguageOption(
                    label = AUTO_DETECT_LABEL,
                    hint = "不使用固定源语言，由识别器判断（更贵；识别错一次会连累整章）",
                    selected = state.autoDetectSource,
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
        supportingContent = if (hint == null) {
            null
        } else {
            { Text(hint, style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
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

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
    )
}

/**
 * 语言预设。
 *
 * 只放常见项：源语言按开发文档 TR04 的枚举，目标语言按 TR05。**其余语言靠面板表尾的
 * 自定义输入**——用户要的是"甚至支持任意语言的翻译"，因此预设表短一点更好维护，
 * 也不会让人以为"只能在这些里选"。
 */
internal val SOURCE_LANGUAGE_PRESETS = listOf(
    "日语", "英语", "韩语", "简体中文", "繁体中文", "中英混合",
    "法语", "西班牙语", "葡萄牙语", "德语", "意大利语", "俄语",
)

internal val TARGET_LANGUAGE_PRESETS = listOf(
    "简体中文", "繁体中文", "英语", "日语", "韩语", "俄语", "巴西葡语",
)
