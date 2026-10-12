package com.spake.brony.doubaoassistant.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.spake.brony.doubaoassistant.R

class AmbientBackgroundView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : android.view.View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = ContextCompat.getColor(context, R.color.voice_base)
    private var gradients = emptyList<Shader>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradients = listOf(
            gradient(w * .45f, h * 1.15f, w * .55f, h * 1.15f, .65f, R.color.voice_lavender),
            gradient(w.toFloat(), h * .46f, w.toFloat(), h * .54f, .62f, R.color.voice_blue),
            gradient(w * .03f, h * .05f, w * .97f, h * .95f, .62f, R.color.voice_pink),
        )
    }

    private fun gradient(x: Float, y: Float, rx: Float, ry: Float, stop: Float, colorId: Int): Shader {
        val color = ContextCompat.getColor(context, colorId)
        return RadialGradient(0f, 0f, 1f, intArrayOf(color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))), floatArrayOf(0f, stop), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx * 1.414214f, ry * 1.414214f); postTranslate(x, y) })
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(base)
        gradients.forEach { paint.shader = it; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
    }
}
