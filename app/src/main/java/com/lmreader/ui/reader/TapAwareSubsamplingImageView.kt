package com.lmreader.ui.reader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * 图片引擎视图 + 单击回调。
 *
 * ## 为什么必须继承而不是在 Compose 里套一层手势检测
 *
 * Compose 的 `pointerInput` 一旦开始消费指针事件，作为子 View 的
 * [SubsamplingScaleImageView] 就收不到后续的 MOVE/POINTER 事件，双指缩放与拖动会直接失效。
 * 因此触摸**全部由库自己处理**，点按区域的命中检测搭在它内部的手势检测器上——
 * 这与 Mihon 的做法一致（它也是在 `Pager.dispatchTouchEvent` 里把同一个事件既交给
 * ViewPager 又交给手势检测器，而不是在父层拦截）。
 *
 * ## 单击与双击为什么不冲突
 *
 * `onSingleTapConfirmed` 只在系统确认"这不是双击的第一次点击"之后才回调，
 * 而库自己的双击缩放走 `onDoubleTap`。两者由同一个 GestureDetector 的顺序保证互斥，
 * 所以双击缩放不会顺带触发一次"显示/隐藏控制栏"。
 *
 * 回调坐标是**归一化**的（0..1），因为点按区域表（[com.lmreader.core.model.NavigationRegions]）
 * 用的就是归一化矩形；在这里换算可以避免每一层都各自记一遍视图尺寸。
 */
internal class TapAwareSubsamplingImageView(
    context: Context,
    private val onSingleTap: (x: Float, y: Float) -> Unit,
    private val onLongPress: (() -> Unit)? = null,
) : SubsamplingScaleImageView(context) {

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                val width = width.toFloat()
                val height = height.toFloat()
                if (width <= 0f || height <= 0f) return false
                onSingleTap(event.x / width, event.y / height)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                onLongPress?.invoke()
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 先把事件交给库（缩放/拖动主要由它消费），再喂给手势检测器。
        // 顺序与 Mihon 的 Pager 一致：super 先行，检测器只做旁路观察。
        val handled = super.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // 始终返回 true：本视图是整屏的触摸目标，返回 false 会让父层（分页器）
        // 抢走后续事件，导致一次缩放被半途打断。
        return true
    }
}
