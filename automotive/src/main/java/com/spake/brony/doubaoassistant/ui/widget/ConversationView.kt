package com.spake.brony.doubaoassistant.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.ui.DesignDimensions

class ConversationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    var onReplay: ((String) -> Unit)? = null
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var content = ConversationContent()
    private var captions = true
    private var compactWindow = false
    private var controlsOnLeft = false
    private val scrollToEnd = Runnable { fullScroll(View.FOCUS_DOWN) }
    private var dimensions = DesignDimensions(resources.configuration.screenWidthDp.toFloat(), resources.configuration.screenHeightDp.toFloat())

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    fun setConversation(value: ConversationContent, showCaptions: Boolean) {
        if (content == value && captions == showCaptions && column.childCount > 0) return
        val previous = content
        val sameCaptions = captions == showCaptions
        content = value
        captions = showCaptions
        if (sameCaptions && previous.messages == value.messages && previous.liveText == value.liveText) return
        // 流式回复只改最后一条文字，避免每个 token 重建整列 TextView。
        if (sameCaptions && captions && previous.liveText == value.liveText &&
            value.messages.isNotEmpty() && previous.messages.size == value.messages.size &&
            previous.messages.dropLast(1) == value.messages.dropLast(1) &&
            previous.messages.last().id == value.messages.last().id &&
            previous.messages.last().role == value.messages.last().role &&
            column.childCount == value.messages.size + if (value.liveText.isEmpty()) 0 else 1) {
            val follow = shouldFollow()
            val row = column.getChildAt(value.messages.lastIndex)
            (if (row is LinearLayout) row.getChildAt(0) as TextView else row as TextView).text = value.messages.last().text
            followToBottom(follow)
            return
        }
        if (sameCaptions && captions && previous.messages == value.messages &&
            previous.liveText.isNotEmpty() && value.liveText.isNotEmpty() &&
            column.childCount == value.messages.size + 1) {
            val follow = shouldFollow()
            (column.getChildAt(column.childCount - 1) as TextView).apply { text = withCaret(value.liveText, textSize) }
            followToBottom(follow)
            return
        }
        render()
    }

    fun setCompactWindow() {
        compactWindow = true
        setPadding(0, paddingTop, 0, paddingBottom)
        render()
    }

    fun setDimensions(value: DesignDimensions) {
        val changed = dimensions != value
        dimensions = value
        updateControlPadding()
        if (!changed) return
        render()
    }

    fun setControlsOnLeft(left: Boolean) {
        controlsOnLeft = left
        updateControlPadding()
    }

    private fun updateControlPadding() {
        if (compactWindow) return
        val inset = dp(dimensions.safeX + dimensions.control + 16f)
        setPadding(if (controlsOnLeft) inset else 0, paddingTop,
            if (controlsOnLeft) 0 else inset, paddingBottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 为控制按钮留白后，按剩余文字区域居中。
        val available = ((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            / resources.displayMetrics.density).coerceAtLeast(0f)
        val textWidth = if (dimensions.width <= 850) available * .8f else (available * .66f).coerceAtMost(1240f)
        val horizontal = if (compactWindow) dp(12f) else dp((available - textWidth) / 2)
        column.setPadding(horizontal, column.paddingTop, horizontal, column.paddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun render() {
        val follow = shouldFollow()
        removeCallbacks(scrollToEnd)
        column.removeAllViews()
        if (!captions) return
        val metrics = dimensions
        val aiSize = if (compactWindow) 16f else metrics.aiText
        val userSize = if (compactWindow) 14f else metrics.userText
        val horizontal = column.paddingLeft
        column.setPadding(horizontal, 0, horizontal, dp(25f))
        if (content.messages.isEmpty() && content.liveText.isEmpty()) {
            column.gravity = Gravity.CENTER_VERTICAL
            column.setPadding(horizontal, 0, horizontal, dp(if (compactWindow || metrics.compact) 0f else metrics.height * .12f))
            addText(context.getString(R.string.voice_prompt), aiSize, R.color.voice_secondary, .06f, lineHeight = 1.4f)
            addText(context.getString(R.string.voice_prompt_support), if (compactWindow) 13f else (metrics.width * .013f).coerceIn(17f, 25f), R.color.voice_muted, .02f, marginTop = 20f, lineHeight = 1.4f)
        } else {
            column.gravity = Gravity.TOP
            content.messages.forEach { message ->
                val assistantStyle = message.isAssistant || message.isSystem
                val parent = if (assistantStyle) LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    column.addView(this, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(20f)
                    })
                } else column
                addText(message.text, if (assistantStyle) aiSize else userSize,
                    if (assistantStyle) R.color.voice_text else R.color.voice_secondary,
                    0f,
                    marginBottom = if (assistantStyle) 0f else 20f,
                    lineHeight = if (assistantStyle) 1.45f else 1.5f,
                    weight = 400,
                    opacity = if (assistantStyle) 1f else .7f,
                    parent = parent)
                if (message.isAssistant) parent.addView(ImageButton(context).apply {
                    visibility = View.GONE // 重播入口暂时隐藏，保留后续恢复能力。
                    setImageResource(R.drawable.ic_voice_speaker)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(12f), dp(14f), dp(16f), dp(14f))
                    alpha = .5f
                    background = ContextCompat.getDrawable(context, R.drawable.voice_toolbar_button)
                    contentDescription = context.getString(R.string.voice_replay_reply)
                    setOnClickListener { onReplay?.invoke(message.id) }
                }, LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { gravity = Gravity.START })
            }
            if (content.liveText.isNotEmpty()) addText(content.liveText, userSize, R.color.voice_secondary, 0f, marginTop = 8f, caret = true, opacity = .7f)
        }
        followToBottom(follow)
    }

    private fun addText(text: String, size: Float, color: Int, spacing: Float, marginTop: Float = 0f, marginBottom: Float = 0f, lineHeight: Float = 1.5f, weight: Int = 400, caret: Boolean = false, opacity: Float = 1f, parent: LinearLayout = column) {
        parent.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(ContextCompat.getColor(context, color))
            alpha = opacity
            includeFontPadding = false
            letterSpacing = spacing
            typeface = Typeface.create(typeface, weight, false)
            setFallbackLineSpacing(false)
            setLineHeight((textSize * lineHeight + .5f).toInt())
            if (caret) {
                this.text = withCaret(text, textSize)
            }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(marginTop); bottomMargin = dp(marginBottom)
        })
    }

    private fun withCaret(text: String, textSize: Float): CharSequence {
        val mark = InsetDrawable(GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.voice_accent))
            cornerRadius = dp(2f).toFloat()
        }, dp(8f), 0, 0, 0).apply { setBounds(0, 0, dp(11f), (textSize * .8f).toInt()) }
        return SpannableString(text + "\uFFFC").apply {
            setSpan(ImageSpan(mark, ImageSpan.ALIGN_BASELINE), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun shouldFollow() = column.height - scrollY - height < dp(80f)

    private fun followToBottom(follow: Boolean) {
        removeCallbacks(scrollToEnd)
        if (follow && captions && (content.messages.isNotEmpty() || content.liveText.isNotEmpty())) post(scrollToEnd)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(scrollToEnd)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
}
