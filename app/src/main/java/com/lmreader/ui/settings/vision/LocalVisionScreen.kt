package com.lmreader.ui.settings.vision

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.settings.api.ApiTopBar
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LocalVisionScreen(container: AppContainer,onBack: () -> Unit) {
    val context=LocalContext.current.applicationContext
    val vm: LocalVisionViewModel=viewModel(factory=viewModelFactory { initializer { LocalVisionViewModel(container.localVision,context) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::image) }
    var languageMenu by remember { mutableStateOf(false) }; var modeMenu by remember { mutableStateOf(false) }
    var demoMenu by remember { mutableStateOf(false) }; var overlays by remember { mutableStateOf(true) }
    var showNotices by remember { mutableStateOf(false) }
    Scaffold(topBar={ ApiTopBar("本地 OCR / Seg 测试",onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("选择一张图片或生成测试图片，查看气泡轮廓、文字框与识别结果。所有处理在设备上完成。",style=MaterialTheme.typography.bodyMedium) }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={ picker.launch(arrayOf("image/*")) },enabled=!state.running) { Text("选择图片") }
                    Box {
                        OutlinedButton(onClick={ demoMenu=true },enabled=!state.running) { Text("生成测试图片") }
                        DropdownMenu(expanded=demoMenu,onDismissRequest={ demoMenu=false }) {
                            VisionDemo.entries.forEach { sample -> DropdownMenuItem(text={ Text(sample.label) },onClick={ demoMenu=false; vm.demo(sample) }) }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick={ languageMenu=true },enabled=!state.running) { Text("语言：${state.language.label}") }
                        DropdownMenu(languageMenu,{ languageMenu=false }) {
                            LocalOcrLanguage.entries.forEach { language -> DropdownMenuItem(text={ Text(language.label) },onClick={ languageMenu=false; vm.language(language) }) }
                        }
                    }
                    Box {
                        OutlinedButton(onClick={ modeMenu=true },enabled=!state.running) { Text(state.mode.label) }
                        DropdownMenu(modeMenu,{ modeMenu=false }) {
                            VisionTestMode.entries.forEach { mode -> DropdownMenuItem(text={ Text(mode.label) },onClick={ modeMenu=false; vm.mode(mode) }) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick=vm::run,enabled=state.image!=null && !state.running) { Text("开始测试") }
                    if (state.running) OutlinedButton(onClick=vm::cancel,enabled=!state.cancelling) { Text(if (state.cancelling) "等待本次推理结束" else "取消") }
                }
                state.progress?.let { p ->
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                    Text(if (state.cancelling) "取消中，本次推理返回后停止" else "${p.stage} ${p.completed}/${p.total}",style=MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error) } }
            state.image?.let { bitmap ->
                item { Text("${state.imageLabel} · ${bitmap.width} × ${bitmap.height}",style=MaterialTheme.typography.bodySmall) }
                item {
                    Row { Switch(checked=overlays,onCheckedChange={ overlays=it }); Text("显示检测标记",Modifier.padding(start=8.dp,top=12.dp)) }
                    val image=remember(bitmap) { bitmap.asImageBitmap() }
                    Canvas(Modifier.fillMaxWidth().height(420.dp)) {
                        val scale=min(size.width/bitmap.width,size.height/bitmap.height)
                        val offset=Offset((size.width-bitmap.width*scale)/2,(size.height-bitmap.height*scale)/2)
                        drawImage(image,dstOffset=IntOffset(offset.x.toInt(),offset.y.toInt()),
                            dstSize=IntSize((bitmap.width*scale).toInt(),(bitmap.height*scale).toInt()))
                        if (overlays) {
                            fun point(p: PixelPoint)=Offset(offset.x+p.x*scale,offset.y+p.y*scale)
                            fun box(r: PixelRect,color: Color) = drawRect(color,point(PixelPoint(r.left,r.top)),
                                androidx.compose.ui.geometry.Size(r.width*scale,r.height*scale),style=Stroke(2.dp.toPx()))
                            state.segmentation?.regions?.forEach { r ->
                                val color=if (r.kind==RegionKind.BUBBLE) Color(0xFFE64A19) else Color(0xFF7B1FA2)
                                box(r.bounds,color)
                                if (r.contour.isNotEmpty()) {
                                    val path=Path().apply { val first=point(r.contour.first()); moveTo(first.x,first.y)
                                        r.contour.drop(1).forEach { val p=point(it); lineTo(p.x,p.y) }; close() }
                                    drawPath(path,color.copy(alpha=.13f)); drawPath(path,color,style=Stroke(1.dp.toPx()))
                                }
                            }
                            state.ocr?.lines?.forEach { box(it.bounds,Color(0xFF1565C0)) }
                        }
                    }
                }
            }
            state.segmentation?.let { result -> item {
                Text("气泡 ${result.regions.count { it.kind==RegionKind.BUBBLE }} · 游离文字 ${result.regions.count { it.kind==RegionKind.FREE_TEXT }} · ${result.elapsedMillis} ms · ${result.backend}")
                Text("橙色：气泡轮廓；紫色：游离文字；蓝色：OCR 文字框",style=MaterialTheme.typography.bodySmall)
            } }
            state.ocr?.let { result ->
                item { Text("OCR ${result.lines.size} 行 · ${result.elapsedMillis} ms · ONNX CPU") }
                if (result.lines.isEmpty()) item { Text("未检测到文字") }
                else item { SelectionContainer { Text(result.text,style=MaterialTheme.typography.bodyLarge) } }
                item { Text("坐标为上方测试图片的像素。低置信度文字保留显示，供人工核对。",style=MaterialTheme.typography.bodySmall) }
            }
            item { TextButton(onClick={ showNotices=true }) { Text("模型来源与开源许可") } }
        }
    }
    if (showNotices) {
        val notices by produceState("正在读取许可说明") {
            value=withContext(Dispatchers.IO) {
                listOf("Models.txt","manga-translator-MIT.txt","PaddleOCR-Apache-2.0.txt").joinToString("\n\n") { name ->
                    context.assets.open("vision/notices/$name").bufferedReader().use { it.readText() }
                }
            }
        }
        AlertDialog(onDismissRequest={ showNotices=false },title={ Text("模型来源与开源许可") },
            text={ SelectionContainer { Text(notices,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall) } },
            confirmButton={ TextButton(onClick={ showNotices=false }) { Text("关闭") } })
    }
}
