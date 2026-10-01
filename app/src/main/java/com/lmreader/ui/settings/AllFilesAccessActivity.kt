package com.lmreader.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmreader.core.storage.access.StorageAccess
import com.lmreader.ui.theme.LmReaderTheme

/**
 * 「全部文件访问」说明页。
 *
 * Android 11+ 要求申请 `MANAGE_EXTERNAL_STORAGE` 的应用提供一个可到达的说明页，
 * 系统授权页会链接到这里。这里如实写清三件事，而不是把权限当成不可解释的前置条件：
 * 为什么需要、会读什么、不授权还能怎么用。
 */
class AllFilesAccessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LmReaderTheme {
                AllFilesAccessContent(
                    status = StorageAccess.describe(this),
                    onOpenSettings = { startActivity(StorageAccess.allFilesAccessIntent(this)) },
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllFilesAccessContent(
    status: String,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全部文件访问") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(text = status, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "为什么需要：\n" +
                    "本应用只做一件事——读取你指定的漫画目录并把它们列成书架。" +
                    "Android 11 起，系统的目录选择器不允许选中存储根、Download 根、" +
                    "Android/data 与 Android/obb，而漫画库经常正好放在这些位置，\n" +
                    "因此需要「全部文件访问」才能选中它们。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "会读什么：\n" +
                    "只读取你自己添加的那些路径。应用不上传任何文件，不扫描你未添加的目录；" +
                    "图片与 ComicInfo.xml 之外的普通文件不会被读取。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "不授权也能用：\n" +
                    "不开启时仍可用系统选择器逐目录授权（权限面更小），" +
                    "只是无法选中上面提到的那些受限位置。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("打开系统授权页")
            }
        }
    }
}
