package com.spake.brony.doubaoassistant.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.ConversationPhase

class VoiceMarkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.voice_accent) }
    private var phase = ConversationPhase.PAUSED
    private var level = 0f
    private var target = 0f
    private var animator: ValueAnimator? = null
    private val heights = floatArrayOf(.35f, .62f, .85f, 1f, .85f, .62f, .35f)

    fun setState(value: ConversationPhase, volume: Float) {
        val requested = if (value in listOf(ConversationPhase.PAUSED, ConversationPhase.ENDED, ConversationPhase.ERROR, ConversationPhase.NETWORK)) 0f
            else volume.coerceIn(0f, 1f)
        if (phase == value && target == requested) return
        phase = value
        target = requested
        stopAnimation()
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled() && level != target) {
            animator = ValueAnimator.ofFloat(level, target).apply {
                duration = 100
                addUpdateListener { level = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { level = target; invalidate() }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator?.removeAllUpdateListeners()
        animator = null
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); level = target; invalidate() }
    override fun onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) stopAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (phase == ConversationPhase.ENDED) return
        val density = resources.displayMetrics.density
        val wave = level > .015f
        val count = if (wave) 7 else 3
        val spacing = (if (wave) 7 else 11) * density
        paint.alpha = if (wave) (130 + level * 100).toInt() else 140
        repeat(count) { index ->
            val x = width / 2f + (index - (count - 1) / 2f) * spacing
            if (wave) {
                val bar = (4f + 25f * heights[index] * level) * density
                canvas.drawRoundRect(x - 1.5f * density, height / 2f - bar / 2, x + 1.5f * density, height / 2f + bar / 2, 2f * density, 2f * density, paint)
            } else canvas.drawCircle(x, height / 2f, 2.5f * density, paint)
        }
    }
}
