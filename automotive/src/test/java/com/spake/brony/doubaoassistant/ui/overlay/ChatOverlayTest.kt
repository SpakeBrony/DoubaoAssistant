package com.spake.brony.doubaoassistant.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.spake.brony.doubaoassistant.data.model.*
import com.spake.brony.doubaoassistant.databinding.OverlayChatBinding
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatOverlayTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val geometry get() = application.getSharedPreferences("chat_overlay_window", Context.MODE_PRIVATE)
    private val listening = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE, asrRequested = true)

    @Before fun setUp() {
        geometry.edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun tearDown() {
        geometry.edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun binding(overlay: ChatOverlay) = overlay.javaClass.getDeclaredField("binding")
        .apply { isAccessible = true }.get(overlay) as OverlayChatBinding

    private fun layout(view: View): WindowManager.LayoutParams {
        val params = view.layoutParams as WindowManager.LayoutParams
        view.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, params.width, params.height)
        return params
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun longPress() = shadowOf(Looper.getMainLooper()).idleFor(
        Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))

    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun permissionThemesConversationStreamingAndControlState() {
        val actions = mutableListOf<Boolean>()
        val overlay = ChatOverlay(application, actions::add)
        try {
            ShadowSettings.setCanDrawOverlays(false)
            assertFalse(overlay.show(Appearance.SYSTEM))
            ShadowSettings.setCanDrawOverlays(true)
            var lightColor = 0
            for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
                assertTrue(overlay.show(appearance))
                val view = binding(overlay)
                val params = layout(view.root)
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                val content = ConversationContent(messages = listOf(
                    ConversationMessage("说说小窗的变化", false, "user"),
                    ConversationMessage("聊天内容会同步显示。", true, "assistant")), liveText = "还有")
                val asr = AsrSnapshot(conversation = ConversationContent(liveText = "还有"))
                overlay.update(listening, asr, ConversationSnapshot(content), .6f)
                assertTrue(texts(view.overlayConversation).any { it.text.toString() == "聊天内容会同步显示。" })
                assertTrue(texts(view.overlayConversation).any { it.text.toString().startsWith("还有") })
                assertFalse(texts(view.root).any { it.text.toString() == "监听中" })
                assertEquals("暂停聊天", view.overlayToggleChat.contentDescription)
                assertTrue(view.overlayToggleChat.performClick())
                assertFalse(actions.last())
                val streamed = content.copy(messages = content.messages.dropLast(1) + content.messages.last().copy(text = "聊天内容会同步显示，也支持滚动。"))
                overlay.update(listening, asr, ConversationSnapshot(streamed))
                val reply = texts(view.overlayConversation).first { it.text.toString().endsWith("也支持滚动。") }
                if (appearance == Appearance.LIGHT) lightColor = reply.currentTextColor
                else assertNotEquals(lightColor, reply.currentTextColor)
                overlay.update(listening.copy(mode = AssistantMode.MUTED, asrRequested = false), AsrSnapshot(), ConversationSnapshot(streamed))
                assertEquals("恢复聊天", view.overlayToggleChat.contentDescription)
                assertEquals("关闭麦克风和本地唤醒", view.overlayMicrophone.contentDescription)
                view.overlayToggleChat.performClick()
                assertTrue(actions.last())
                overlay.update(listening, AsrSnapshot(error = "failed"), ConversationSnapshot(streamed))
                assertEquals("恢复聊天", view.overlayToggleChat.contentDescription)
                overlay.close()
                overlay.close()
                assertFalse(overlay.isShowing)
                assertNull(view.root.parent)
            }
            assertTrue(overlay.show(Appearance.SYSTEM))
            ShadowSettings.setCanDrawOverlays(false)
            assertFalse(overlay.show(Appearance.SYSTEM))
            assertFalse(overlay.isShowing)
        } finally { overlay.close() }
    }

    @Test fun onlyLongPressDragsAndNewInstanceRestoresPositionAndSize() {
        var overlay = ChatOverlay(application)
        try {
            geometry.edit().putInt("x", 400).putInt("y", 100).commit()
            assertTrue(overlay.show(Appearance.LIGHT))
            val view = binding(overlay).root
            val params = layout(view)
            touch(view, MotionEvent.ACTION_DOWN, 120f, 28f)
            touch(view, MotionEvent.ACTION_MOVE, 160f, 68f)
            longPress()
            touch(view, MotionEvent.ACTION_UP, 160f, 68f)
            assertEquals(400, params.x)
            assertEquals(100, params.y)
            touch(view, MotionEvent.ACTION_DOWN, 120f, 28f)
            longPress()
            touch(view, MotionEvent.ACTION_MOVE, 170f, 68f)
            touch(view, MotionEvent.ACTION_UP, 170f, 68f)
            assertEquals(450, params.x)
            assertEquals(140, params.y)
            // 结束手势时已保存，不依赖 close 或旧实例的内存。
            assertEquals(450, geometry.getInt("x", -1))
            assertEquals(140, geometry.getInt("y", -1))
            overlay.close()
            overlay = ChatOverlay(application)
            assertTrue(overlay.show(Appearance.LIGHT))
            val restored = layout(binding(overlay).root)
            assertEquals(450, restored.x)
            assertEquals(140, restored.y)
            assertEquals(params.width, restored.width)
            assertEquals(params.height, restored.height)
        } finally { overlay.close() }
    }

    @Test fun longPressCannotTriggerPauseButtonAndOrdinaryTapCan() {
        val actions = mutableListOf<Boolean>()
        val overlay = ChatOverlay(application, actions::add)
        try {
            assertTrue(overlay.show(Appearance.LIGHT))
            overlay.update(listening, AsrSnapshot(), ConversationSnapshot())
            val binding = binding(overlay)
            layout(binding.root)
            val button = Rect(0, 0, binding.overlayToggleChat.width, binding.overlayToggleChat.height)
            (binding.root as ViewGroup).offsetDescendantRectToMyCoords(binding.overlayToggleChat, button)
            val x = button.centerX().toFloat()
            val y = button.centerY().toFloat()
            touch(binding.root, MotionEvent.ACTION_DOWN, x, y)
            touch(binding.root, MotionEvent.ACTION_UP, x, y)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false), actions)
            touch(binding.root, MotionEvent.ACTION_DOWN, x, y)
            longPress()
            touch(binding.root, MotionEvent.ACTION_MOVE, x - 30f, y - 30f)
            touch(binding.root, MotionEvent.ACTION_UP, x - 30f, y - 30f)
            assertEquals(listOf(false), actions)
        } finally { overlay.close() }
    }

    @Test fun microphoneTapsUseCurrentStateAndLongPressDoesNotToggleIt() {
        val actions = mutableListOf<Boolean>()
        val overlay = ChatOverlay(application, onToggleMicrophone = actions::add)
        try {
            assertTrue(overlay.show(Appearance.LIGHT))
            overlay.update(listening, AsrSnapshot(), ConversationSnapshot())
            val binding = binding(overlay)
            layout(binding.root)
            val button = Rect(0, 0, binding.overlayMicrophone.width, binding.overlayMicrophone.height)
            (binding.root as ViewGroup).offsetDescendantRectToMyCoords(binding.overlayMicrophone, button)
            val x = button.centerX().toFloat()
            val y = button.centerY().toFloat()
            touch(binding.root, MotionEvent.ACTION_DOWN, x, y)
            touch(binding.root, MotionEvent.ACTION_UP, x, y)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), actions)
            overlay.update(KwsSnapshot(), AsrSnapshot(), ConversationSnapshot())
            assertTrue(overlay.isShowing)
            assertEquals("开启麦克风", binding.overlayMicrophone.contentDescription)
            touch(binding.root, MotionEvent.ACTION_DOWN, x, y)
            touch(binding.root, MotionEvent.ACTION_UP, x, y)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false), actions)
            touch(binding.root, MotionEvent.ACTION_DOWN, x, y)
            longPress()
            touch(binding.root, MotionEvent.ACTION_MOVE, x - 30f, y - 30f)
            touch(binding.root, MotionEvent.ACTION_UP, x - 30f, y - 30f)
            assertEquals(listOf(true, false), actions)
        } finally { overlay.close() }
    }

    @Test fun chatScrollDoesNotMoveWindowOrBecomeLongPressDrag() {
        val overlay = ChatOverlay(application)
        try {
            assertTrue(overlay.show(Appearance.LIGHT))
            overlay.update(listening, AsrSnapshot(), ConversationSnapshot(ConversationContent(messages =
                (1..30).map { ConversationMessage("第 $it 条聊天内容，向上滑动可查看历史。", it % 2 == 0, "$it") })))
            val binding = binding(overlay)
            val params = layout(binding.root)
            binding.overlayConversation.scrollTo(0, 0)
            val startX = params.x
            val startY = params.y
            touch(binding.root, MotionEvent.ACTION_DOWN, 120f, 220f)
            touch(binding.root, MotionEvent.ACTION_MOVE, 120f, 100f)
            longPress()
            touch(binding.root, MotionEvent.ACTION_MOVE, 120f, 70f)
            touch(binding.root, MotionEvent.ACTION_UP, 120f, 70f)
            assertTrue(binding.overlayConversation.scrollY > 0)
            assertEquals(startX, params.x)
            assertEquals(startY, params.y)
        } finally { overlay.close() }
    }

    @Test fun fourCornersResizeWithOppositeCornerFixedAndLimitsPersist() {
        for (corner in OverlayWindowView.Corner.entries) {
            val overlay = ChatOverlay(application)
            geometry.edit().clear().putInt("x", 350).putInt("y", 150).commit()
            try {
                assertTrue(overlay.show(Appearance.LIGHT))
                val view = binding(overlay).root
                val params = layout(view)
                val fixedX = if (corner.left) params.x + params.width else params.x
                val fixedY = if (corner.top) params.y + params.height else params.y
                val x = if (corner.left) 10f else params.width - 10f
                val y = if (corner.top) 10f else params.height - 10f
                touch(view, MotionEvent.ACTION_DOWN, x, y)
                touch(view, MotionEvent.ACTION_MOVE, x + if (corner.left) -60f else 60f, y + if (corner.top) -40f else 40f)
                assertEquals(380, params.width)
                assertEquals(400, params.height)
                assertEquals(fixedX, if (corner.left) params.x + params.width else params.x)
                assertEquals(fixedY, if (corner.top) params.y + params.height else params.y)
                touch(view, MotionEvent.ACTION_MOVE, x + if (corner.left) 10000f else -10000f, y + if (corner.top) 10000f else -10000f)
                assertEquals(240, params.width)
                assertEquals(220, params.height)
                touch(view, MotionEvent.ACTION_MOVE, x + if (corner.left) -10000f else 10000f, y + if (corner.top) -10000f else 10000f)
                assertTrue(params.width <= 640)
                assertTrue(params.height <= 720)
                assertTrue(params.x >= 0 && params.y >= 0)
                assertTrue(params.x + params.width <= 1280)
                assertTrue(params.y + params.height <= 800)
                touch(view, MotionEvent.ACTION_UP, x, y)
                assertEquals(params.width, geometry.getInt("width", -1))
                assertEquals(params.height, geometry.getInt("height", -1))
                val width = params.width
                val height = params.height
                overlay.close()
                val reopened = ChatOverlay(application)
                try {
                    assertTrue(reopened.show(Appearance.LIGHT))
                    val restored = layout(binding(reopened).root)
                    assertEquals(width, restored.width)
                    assertEquals(height, restored.height)
                } finally { reopened.close() }
            } finally { overlay.close() }
        }
    }

    @Test fun dragClampsAndCancelledGestureStopsMoving() {
        val overlay = ChatOverlay(application)
        try {
            assertTrue(overlay.show(Appearance.LIGHT))
            val view = binding(overlay).root
            val params = layout(view)
            touch(view, MotionEvent.ACTION_DOWN, 120f, 28f)
            longPress()
            touch(view, MotionEvent.ACTION_MOVE, -10000f, -10000f)
            assertEquals(0, params.x)
            assertEquals(0, params.y)
            touch(view, MotionEvent.ACTION_MOVE, 10000f, 10000f)
            assertTrue(params.x + params.width <= 1280)
            assertTrue(params.y + params.height <= 800)
            val x = params.x
            val y = params.y
            touch(view, MotionEvent.ACTION_CANCEL, 10000f, 10000f)
            touch(view, MotionEvent.ACTION_MOVE, -10000f, -10000f)
            assertEquals(x, params.x)
            assertEquals(y, params.y)
        } finally { overlay.close() }
    }

    @Test fun oldGeometryIsClampedWhenScreenChanges() {
        geometry.edit().putInt("x", 4000).putInt("y", -100).putInt("width", 9000).putInt("height", 1).commit()
        val overlay = ChatOverlay(application)
        try {
            assertTrue(overlay.show(Appearance.LIGHT))
            val params = layout(binding(overlay).root)
            assertEquals(640, params.width)
            assertEquals(220, params.height)
            assertEquals(640, params.x)
            assertEquals(0, params.y)
        } finally { overlay.close() }
    }

    @Test fun rendersMiniChatInBothThemesAndAtMinimumSize() {
        if (android.os.Build.VERSION.SDK_INT != 35) return
        for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            for (minimum in listOf(false, true)) {
                geometry.edit().clear().putInt("width", if (minimum) 240 else 320)
                    .putInt("height", if (minimum) 220 else 360).commit()
                val overlay = ChatOverlay(application)
                try {
                    assertTrue(overlay.show(appearance))
                    overlay.update(listening, AsrSnapshot(), ConversationSnapshot(ConversationContent(messages = listOf(
                        ConversationMessage("悬浮窗的位置会保存吗？", false, "user"),
                        ConversationMessage("会的。下次打开，会回到你上次放置的位置。你也可以拖动四个角，调整窗口大小。", true, "assistant")))))
                    val binding = binding(overlay)
                    shadowOf(Looper.getMainLooper()).idle()
                    val params = layout(binding.root)
                    assertTrue(binding.overlayConversation.height > 0)
                    assertEquals(48, binding.overlayToggleChat.width)
                    val bitmap = Bitmap.createBitmap(params.width, params.height, Bitmap.Config.ARGB_8888)
                    try {
                        binding.root.draw(Canvas(bitmap))
                        val output = File("build/reports/ui-snapshots/overlay-${appearance.name.lowercase()}-${if (minimum) "minimum" else "default"}.png")
                        output.parentFile!!.mkdirs()
                        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                } finally { overlay.close() }
            }
        }
    }
}
