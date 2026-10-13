package com.spake.brony.doubaoassistant.ui.widget

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import com.spake.brony.doubaoassistant.BuildConfig
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
    private var jsonDialog: AlertDialog? = null
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
            previous.messages.last().jsonMessage == value.messages.last().jsonMessage &&
            column.childCount == value.messages.size + if (value.liveText.isEmpty()) 0 else 1) {
            val follow = shouldFollow()
            val row = column.getChildAt(value.messages.lastIndex)
            (if (row is LinearLayout) row.tag as TextView else row as TextView).text = value.messages.last().text
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
                val json = message.jsonMessage.takeIf { BuildConfig.DEBUG && !it.isNullOrBlank() }
                var jsonButton: ImageButton? = null
                val textParent = if (json != null) LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    parent.addView(this, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        if (!assistantStyle) bottomMargin = dp(20f)
                    })
                    jsonButton = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_voice_json)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(5f), 0, dp(5f), 0)
                        alpha = .35f
                        background = null
                        contentDescription = context.getString(if (assistantStyle) R.string.voice_view_json_response else R.string.voice_view_intent_match)
                        setOnClickListener { showJson(json, if (assistantStyle) R.string.voice_json_response else R.string.voice_intent_match) }
                    }
                    addView(jsonButton, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { rightMargin = dp(6f) })
                } else parent
                val replyText = addText(message.text, if (assistantStyle) aiSize else userSize,
                    if (assistantStyle) R.color.voice_text else R.color.voice_secondary,
                    0f,
                    marginBottom = if (assistantStyle) 0f else 20f,
                    lineHeight = if (assistantStyle) 1.45f else 1.5f,
                    weight = 400,
                    opacity = if (assistantStyle) 1f else .7f,
                    parent = textParent)
                if (json != null) {
                    replyText.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    // 按第一行字体高度对齐，避免图标因大按钮或多行文字而下沉。
                    val font = replyText.paint.fontMetricsInt
                    jsonButton?.layoutParams?.height = font.descent - font.ascent
                }
                if (assistantStyle) parent.tag = replyText
                else if (json != null) textParent.tag = replyText
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

    private fun addText(text: String, size: Float, color: Int, spacing: Float, marginTop: Float = 0f, marginBottom: Float = 0f, lineHeight: Float = 1.5f, weight: Int = 400, caret: Boolean = false, opacity: Float = 1f, parent: LinearLayout = column): TextView {
        val view = TextView(context).apply {
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
        }
        parent.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(marginTop); bottomMargin = dp(marginBottom)
        })
        return view
    }

    private fun showJson(json: String, title: Int) {
        if (!BuildConfig.DEBUG || !isAttachedToWindow) return
        jsonDialog?.dismiss()
        val text = TextView(context).apply {
            this.text = json
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        val scroll = ScrollView(context).apply {
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        // 详细悬浮框使用 Application Context，弹窗也必须使用悬浮窗口类型。
        var owner = context
        while (owner is ContextWrapper && owner !is Activity && owner.baseContext !== owner) owner = owner.baseContext
        if (owner !is Activity) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener { if (jsonDialog === dialog) jsonDialog = null }
        jsonDialog = dialog
        dialog.show()
        dialog.window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        scroll.layoutParams = scroll.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * .6f).toInt()
        }
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
        jsonDialog?.dismiss()
        removeCallbacks(scrollToEnd)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
}
