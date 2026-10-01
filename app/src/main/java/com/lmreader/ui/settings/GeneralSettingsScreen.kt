package com.lmreader.ui.settings

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.storage.settings.AppThemeMode
import com.lmreader.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val general = container.generalPreferences
    val theme by general.theme.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("通用") }, navigationIcon = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("应用语言", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            listOf(null to "跟随系统", "zh-Hans" to "简体中文", "en" to "English").forEach { (tag, label) ->
                ListItem(headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = general.languageTag == tag, onClick = null) },
                    modifier = Modifier.clickable {
                        general.setLanguageTag(tag)
                        var current = context
                        while (current is ContextWrapper && current !is Activity) current = current.baseContext
                        (current as? Activity)?.recreate()
                    })
            }
            Text("切换语言后，界面文案会随应用重新打开而更新。漫画标题、路径和用户输入保持原文。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
            Text("跟随系统：简体中文系统使用简体中文，其他系统语言（包括繁体中文）使用英语。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
            HorizontalDivider()
            Text("应用主题", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            listOf(AppThemeMode.SYSTEM to "跟随系统", AppThemeMode.LIGHT to "浅色", AppThemeMode.DARK to "深色").forEach { (mode, label) ->
                ListItem(headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = theme == mode, onClick = null) },
                    modifier = Modifier.clickable { general.setTheme(mode) })
            }
        }
    }
}
