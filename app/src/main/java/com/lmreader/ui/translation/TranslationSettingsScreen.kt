package com.lmreader.ui.translation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 翻译设置（**诚实的进度说明页**，用户选择的形态）。
 *
 * ## 为什么这里没有输入框
 *
 * 对应的是一整页 P3 规格（主 AI / OCR 与模型资产 / 翻译方式与语言文风 / LLM 采样 /
 * 遮罩与字体，几十项，见 `docs/翻译配置与上游对照.md`）。现在把它做成半页可填的
 * 表单，只会得到"填了但没有任何东西会用它"的控件——开发文档 17 的完成标准是
 * **不存在仅摆放未接线的核心控件**。所以这一页只回答一个问题：**现在什么能用、
 * 什么还不能**，并指向真正已经生效的入口。
 *
 * ## 已经能用的部分在哪里
 *
 * - 语言（源/目标、自动识别）：详情页 ⋮ → 翻译语言，**已经跟着漫画生效**；
 * - 文风：详情页 ⋮ → 文风设置，三级覆盖**已经生效**；
 * - 译名：详情页 ⋮ → 译名管理，**已经存进库**并会随请求下发；
 * - 排队：「翻译所选 / 全部翻译」写入真正的待翻译记录，侧栏「翻译队列」计数会变。
 *
 * 缺的是**执行**：模型资产（检测/OCR/主模型）的来源、校验和与下载流程还没做，
 * 因此没有任务会被真正跑起来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSettingsScreen(onBack: () -> Unit) {
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "这一页如实说明当前进度，不放还不能生效的输入框。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            Section("已经能用")
            Bullet("语言：源语言 / 目标语言 / 自动识别 —— 跟着每部漫画走（详情页 ⋮ → 翻译语言）")
            Bullet("文风：漫画 → 分类 → 全局的覆盖关系，页面上能看到最终生效的是哪一层")
            Bullet("译名：按「漫画 × 目标语言」存键值字典，人工录入的不会被自动抽取覆盖")
            Bullet("排队：翻译所选 / 全部翻译会写入真正的待翻译记录，侧栏「翻译队列」计数随之变化")
            Bullet("清除翻译文本：删掉该章译文并把状态退回「待翻译」")

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Section("还没做（P3）")
            Bullet("模型资产：检测 / OCR / 主模型的来源、校验和与下载流程")
            Bullet("执行：任务调度、进度与失败重试（现在只排队，不会真的开始翻译）")
            Bullet("主 AI 配置：接口地址、密钥、模型、请求格式、超时与并发")
            Bullet("页面与气泡：检测结果、遮罩样式与字体")
            Bullet("译文存放与阅读器叠加显示")

            Spacer(Modifier.height(12.dp))
            Text(
                text = "上面「已经能用」的那几项都不依赖模型：它们是你先把自己的语言、文风与" +
                    "译名准备好，等执行部分接上来就能直接用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp),
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
