package com.spake.brony.doubaoassistant.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import com.spake.brony.doubaoassistant.R

// 使用原设计 54 × 31 的轨道和 23 的圆点，保留原生 checked/无障碍语义。
class CaptionSwitchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : CompoundButton(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = ContextCompat.getColor(context, R.color.voice_accent)
    private val selected = ContextCompat.getColor(context, R.color.voice_selected)
    private val panel = ContextCompat.getColor(context, R.color.voice_panel)
    private val muted = ContextCompat.getColor(context, R.color.voice_muted)

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // 轨道和圆点由 Canvas 绘制，不能依赖原生 Drawable 的状态变化触发重绘。
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val trackHeight = 31 * density
        val trackWidth = 54 * density
        val x = (width - trackWidth) / 2f
        val y = (height - trackHeight) / 2f
        paint.color = if (isChecked) accent else selected
        canvas.drawRoundRect(x, y, x + trackWidth, y + trackHeight, trackHeight / 2, trackHeight / 2, paint)
        paint.color = if (isChecked) panel else muted
        canvas.drawCircle(x + (if (isChecked) 38.5f else 15.5f) * density, height / 2f, 11.5f * density, paint)
    }
}
