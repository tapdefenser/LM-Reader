package com.lmreader.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 吸附在**右侧**的侧滑抽屉（图库的图源筛选、书架的筛选菜单）。
 *
 * 为什么用 [Popup] 而不是普通的 Box：
 * 抽屉必须**覆盖**在页面上，而不是与页面并排。早期把它写成 Scaffold 的兄弟节点，
 * 结果它按 fillMaxSize 独占整屏、把页面挤出可视区，而面板本身又被画在屏幕之外——
 * 真机现象就是"点筛选按钮完全没反应"。用 Popup 之后它是独立窗口层，
 * 调用方不需要为了让它浮起来去改页面结构。
 *
 * 为什么不用框架的 `ModalNavigationDrawer`：它只能吸附在布局起始侧，没有选择侧别
 * 的参数。把子树设成 RTL 能把内容挪到右边，但会把面板内容整体镜像（真机上标题与
 * 数量文案都反了）。因此右侧抽屉自己实现；左侧主菜单继续用框架组件。
 *
 * 偏移量自己持有（[Animatable]）而不是用 `AnchoredDraggableState`：后者在首次布局前
 * 读取偏移会抛 "The offset was read before being initialized"，而只在布局 lambda 里
 * 读又不会让布局观察到变化，同样会导致"状态已打开、面板却没出现"。
 *
 * 手势方向由 `draggable(reverseDirection = …)` 表达，不需要固定的边缘手势区——
 * 早期那条 24dp 手势区正好盖住距边缘约 20dp 的顶栏按钮，把点击吃掉了。
 *
 * 无障碍：遮罩带"关闭"描述；返回键与点击外部都关闭；关闭到底后面板移出屏幕。
 * 调用方仍需提供顶栏按钮作为主入口——控件不依赖手势（开发文档 8.2）。
 */
@Composable
fun EndSideDrawer(
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    drawerWidth: Dp = 320.dp,
    content: @Composable () -> Unit,
) {
    if (!open) return

    val density = LocalDensity.current
    val widthPx = with(density) { drawerWidth.toPx() }
    val scope = rememberCoroutineScope()

    // offsetX：0 = 完全打开（贴右边），widthPx = 完全关闭（移出屏幕右侧）。
    val offsetX = remember { Animatable(widthPx) }
    // 打开（本函数只在 open = true 时被调用，因此这里只需要滑入动画）。
    LaunchedEffect(Unit) {
        offsetX.animateTo(
            targetValue = 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        )
    }

    Popup(
        // 全屏覆盖：默认 Popup 会按平台默认宽度约束内容，那样面板无法贴到屏幕边缘。
        properties = PopupProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
        onDismissRequest = onDismiss,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    // 关闭态下用负向拖动（向左）打开：偏移量从 widthPx 往 0 走。
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(0f, widthPx))
                        }
                    },
                    onDragStopped = { velocity ->
                        // 过半即切换，与 EhViewer 的手感一致。
                        val target = if (offsetX.value < widthPx / 2f) 0f else widthPx
                        offsetX.animateTo(
                            targetValue = target,
                            initialVelocity = velocity,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        )
                        if (target == widthPx) onDismiss() else onOpen()
                    },
                ),
        ) {
            val progress = (1f - offsetX.value / widthPx).coerceIn(0f, 1f)

            // 遮罩：点击关闭。透明度跟随拖动进度。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = progress * 0.32f))
                    .semantics { contentDescription = "关闭面板" }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            ) {
                content()
            }
        }
    }
}