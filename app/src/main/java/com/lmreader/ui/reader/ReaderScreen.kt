package com.lmreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    mangaId: String,
    chapterId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        key = "$mangaId:$chapterId",
        factory = ReaderViewModel.factory(container, mangaId, chapterId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val leave = {
        viewModel.saveProgress()
        onBack()
    }
    BackHandler(onBack = leave)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            state.error != null -> ReaderError(state.error.orEmpty(), viewModel::reload, leave)
            state.pages.isNotEmpty() && state.pageSource != null -> key(state.currentChapter?.chapterId) {
                ReaderPager(state, viewModel)
            }
        }

        if (state.chromeVisible && state.error == null && state.pages.isNotEmpty()) {
            TopAppBar(
                title = {
                    Column {
                        Text(state.mangaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            state.currentChapter?.title.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderBottomBar(state, viewModel, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ReaderPager(state: ReaderUiState, viewModel: ReaderViewModel) {
    val source = requireNotNull(state.pageSource)
    val pagerState = rememberPagerState(
        initialPage = state.currentPageIndex,
        pageCount = { state.pages.size },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(viewModel::onPageChanged)
    }
    LaunchedEffect(state.currentPageIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != state.currentPageIndex) {
            pagerState.scrollToPage(state.currentPageIndex)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        beyondViewportPageCount = 1,
        key = { state.pages[it].pageId },
    ) { index ->
        ReaderPageImage(
            source = source,
            page = state.pages[index],
            onTap = viewModel::toggleChrome,
        )
    }
}

@Composable
private fun ReaderPageImage(source: PageSource, page: ReaderPage, onTap: () -> Unit) {
    var bitmap by remember(source, page.pageId) { mutableStateOf<Bitmap?>(null) }
    var error by remember(source, page.pageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, page.pageId) {
        try {
            bitmap = withContext(Dispatchers.IO) { decodeSampled(source, page) }
            if (bitmap == null) error = "图片解码失败"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取页面"
        }
    }
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.recycle() }
    }
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(page.pageId) {
            detectTapGestures(onTap = { onTap() })
        },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = page.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            error != null -> Text(error.orEmpty(), color = Color.White)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun ReaderBottomBar(state: ReaderUiState, viewModel: ReaderViewModel, modifier: Modifier) {
    var sliderValue by remember(state.currentChapter?.chapterId) {
        mutableFloatStateOf(state.currentPageIndex.toFloat())
    }
    LaunchedEffect(state.currentPageIndex) { sliderValue = state.currentPageIndex.toFloat() }
    Surface(color = Color.Black.copy(alpha = 0.76f), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Slider(
                value = sliderValue.coerceIn(0f, state.pages.lastIndex.toFloat()),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val page = sliderValue.roundToInt().coerceIn(state.pages.indices)
                    viewModel.onPageChanged(page)
                },
                valueRange = 0f..state.pages.lastIndex.coerceAtLeast(0).toFloat(),
                steps = (state.pages.size - 2).coerceAtLeast(0),
                enabled = state.pages.size > 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::openPreviousChapter, enabled = state.hasPreviousChapter) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一章",
                        tint = if (state.hasPreviousChapter) Color.White else Color.Gray,
                    )
                }
                Text("${state.currentPageIndex + 1} / ${state.pages.size}", color = Color.White)
                IconButton(onClick = viewModel::openNextChapter, enabled = state.hasNextChapter) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一章",
                        tint = if (state.hasNextChapter) Color.White else Color.Gray,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderError(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

private suspend fun decodeSampled(source: PageSource, page: ReaderPage): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.open(page).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= TARGET_LONG_EDGE ||
        bounds.outHeight / (sample * 2) >= TARGET_LONG_EDGE
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return source.open(page).use { BitmapFactory.decodeStream(it, null, options) }
}

private const val TARGET_LONG_EDGE = 2560
