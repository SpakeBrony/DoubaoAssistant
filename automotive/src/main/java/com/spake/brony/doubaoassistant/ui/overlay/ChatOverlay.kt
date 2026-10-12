package com.spake.brony.doubaoassistant.ui.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.MainThread
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationSnapshot
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.databinding.OverlayChatBinding
import com.spake.brony.doubaoassistant.ui.binding.bindChatControl
import com.spake.brony.doubaoassistant.ui.binding.bindMicrophone
import com.spake.brony.doubaoassistant.viewmodel.KwsUiState

// 由录音服务持有，只使用 Application Context；返回前台或服务销毁时移除窗口。
@MainThread
internal class ChatOverlay(
    context: Context,
    private val onToggleChat: (Boolean) -> Unit = {},
    private val onToggleMicrophone: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val application = context.applicationContext
    private val windows = application.getSystemService(WindowManager::class.java)
    private val geometry = application.getSharedPreferences("chat_overlay_window", Context.MODE_PRIVATE)
    private var binding: OverlayChatBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private var state = KwsUiState()
    val isShowing: Boolean get() = binding != null
    private fun dp(value: Int) = (value * application.resources.displayMetrics.density + .5f).toInt()

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val window = windows.currentWindowMetrics
            val insets = window.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Point((window.bounds.width() - insets.left - insets.right).coerceAtLeast(1),
                (window.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1))
        }
        val size = Point()
        @Suppress("DEPRECATION")
        windows.defaultDisplay.getSize(size)
        return Point(size.x.coerceAtLeast(1), size.y.coerceAtLeast(1))
    }

    fun show(appearance: Appearance): Boolean {
        if (!Settings.canDrawOverlays(application)) { close(); return false }
        if (isShowing) return true
        val configuration = Configuration(application.resources.configuration)
        val night = when (appearance) {
            Appearance.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Appearance.DARK -> Configuration.UI_MODE_NIGHT_YES
            Appearance.SYSTEM -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        val themed = ContextThemeWrapper(application.createConfigurationContext(configuration), R.style.Theme_DoubaoAssistant)
        val next = OverlayChatBinding.inflate(LayoutInflater.from(themed))
        val root = next.root as OverlayWindowView
        next.overlayConversation.setCompactWindow()
        val screen = screenSize()
        val width = geometry.getInt("width", dp(320)).coerceIn(dp(240).coerceAtMost(screen.x), dp(640).coerceAtMost(screen.x))
        val height = geometry.getInt("height", dp(360)).coerceIn(dp(220).coerceAtMost(screen.y), dp(720).coerceAtMost(screen.y))
        val layout = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = geometry.getInt("x", screen.x - width - dp(16)).coerceIn(0, screen.x - width)
            y = geometry.getInt("y", dp(96)).coerceIn(0, screen.y - height)
        }
        var start = Rect()
        root.onGestureStart = { start = Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height) }
        root.onDrag = { dx, dy ->
            val bounds = screenSize()
            layout.x = (start.left + dx.toInt()).coerceIn(0, (bounds.x - layout.width).coerceAtLeast(0))
            layout.y = (start.top + dy.toInt()).coerceIn(0, (bounds.y - layout.height).coerceAtLeast(0))
            updateLayout()
        }
        root.onResize = { corner, dx, dy ->
            val bounds = screenSize()
            val maxWidth = dp(640).coerceAtMost(if (corner.left) start.right else bounds.x - start.left)
            val maxHeight = dp(720).coerceAtMost(if (corner.top) start.bottom else bounds.y - start.top)
            layout.width = (start.width() + (if (corner.left) -dx else dx).toInt())
                .coerceIn(dp(240).coerceAtMost(maxWidth), maxWidth)
            layout.height = (start.height() + (if (corner.top) -dy else dy).toInt())
                .coerceIn(dp(220).coerceAtMost(maxHeight), maxHeight)
            layout.x = if (corner.left) start.right - layout.width else start.left
            layout.y = if (corner.top) start.bottom - layout.height else start.top
            updateLayout()
        }
        root.onGestureEnd = ::saveGeometry
        next.overlayMicrophone.setOnClickListener { onToggleMicrophone(state.isListening) }
        next.overlayToggleChat.setOnClickListener { if (state.canControlChat) onToggleChat(state.isPaused) }
        return try {
            windows.addView(root, layout)
            binding = next
            params = layout
            render()
            true
        } catch (e: RuntimeException) {
            if (binding === next) close() else next.unbind()
            Log.e("ChatOverlay", "Failed to show background chat window", e)
            false
        }
    }

    private fun updateLayout() {
        val view = binding?.root ?: return
        try { windows.updateViewLayout(view, params ?: return) }
        catch (e: RuntimeException) {
            Log.w("ChatOverlay", "Failed to update background chat window", e)
            close()
        }
    }

    private fun saveGeometry() {
        val layout = params ?: return
        geometry.edit().putInt("x", layout.x).putInt("y", layout.y)
            .putInt("width", layout.width).putInt("height", layout.height).apply()
    }

    fun update(kws: KwsSnapshot, asr: AsrSnapshot, chat: ConversationSnapshot, audioLevel: Float = 0f) {
        state = KwsUiState(snapshot = kws, asr = asr, conversation = chat.content,
            chatError = chat.error, audioLevel = audioLevel)
        render()
    }

    private fun render() {
        val view = binding ?: return
        view.overlayConversation.setConversation(state.conversation, true)
        bindMicrophone(view.overlayMicrophone, state.isListening)
        view.overlayMicrophone.alpha = if (state.isListening) 1f else .45f
        view.overlayVoiceMark.setState(state.phase, state.waveformLevel)
        bindChatControl(view.overlayToggleChat, state.isPaused)
        view.overlayToggleChat.isEnabled = state.canControlChat
        view.overlayToggleChat.alpha = if (state.canControlChat) 1f else .4f
    }

    override fun close() {
        val previous = binding ?: return
        saveGeometry()
        binding = null
        params = null
        val root = previous.root as OverlayWindowView
        root.onGestureStart = null
        root.onDrag = null
        root.onResize = null
        root.onGestureEnd = null
        previous.overlayMicrophone.setOnClickListener(null)
        previous.overlayToggleChat.setOnClickListener(null)
        previous.unbind()
        try { windows.removeViewImmediate(root) }
        catch (e: IllegalArgumentException) { Log.w("ChatOverlay", "Background chat window already removed", e) }
    }
}
