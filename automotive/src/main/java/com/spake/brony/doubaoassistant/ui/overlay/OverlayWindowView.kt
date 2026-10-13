package com.spake.brony.doubaoassistant.ui.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** 先让聊天列表滚动和按钮响应，停留长按后才接管窗口拖动。 */
class OverlayWindowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    enum class Corner(val left: Boolean, val top: Boolean) {
        TOP_LEFT(true, true), TOP_RIGHT(false, true), BOTTOM_LEFT(true, false), BOTTOM_RIGHT(false, false)
    }
    var onGestureStart: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onResize: ((Corner, Float, Float) -> Unit)? = null
    var onGestureEnd: (() -> Unit)? = null
    var resizeEnabled: Boolean = true
    init { clipToOutline = true }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var corner: Corner? = null
    private val longPress = Runnable {
        if (tracking && corner == null) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishGesture()
                tracking = true
                downX = event.rawX
                downY = event.rawY
                val edge = 28f * density
                corner = if (resizeEnabled && (event.x < edge || event.x > width - edge) &&
                    (event.y < edge || event.y > height - edge)) {
                    Corner.entries.first { it.left == (event.x < edge) && it.top == (event.y < edge) }
                } else null
                onGestureStart?.invoke()
                if (corner == null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> cancelEarlyMovement(event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> finishGesture()
            MotionEvent.ACTION_UP -> if (!dragging && corner == null) finishGesture()
        }
        return tracking && (dragging || corner != null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                cancelEarlyMovement(event)
                if (tracking) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val resize = corner
                    if (resize != null) onResize?.invoke(resize, dx, dy)
                    else if (dragging) onDrag?.invoke(dx, dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tracking && dragging) onDrag?.invoke(event.rawX - downX, event.rawY - downY)
                if (tracking && !dragging && corner == null) performClick()
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishGesture()
        }
        return true
    }

    private fun cancelEarlyMovement(event: MotionEvent) {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (!dragging && corner == null && dx * dx + dy * dy > slop.toFloat() * slop) {
            tracking = false
            removeCallbacks(longPress)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && !dragging && corner == null) {
            tracking = false
            removeCallbacks(longPress)
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun finishGesture() {
        removeCallbacks(longPress)
        if (tracking && (dragging || corner != null)) onGestureEnd?.invoke()
        tracking = false
        dragging = false
        corner = null
    }

    override fun onDetachedFromWindow() {
        finishGesture()
        super.onDetachedFromWindow()
    }
}
