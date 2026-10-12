package com.spake.brony.doubaoassistant.data.repository

import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AssistantPreferencesTest {
    @Test fun allSettingsPersistAndExistingKeysRemainReadable() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("assistant", 0)
        storage.edit().clear().commit()
        try {
            val preferences = AssistantPreferences(context)
            assertEquals(AnswerMode.CHAT, preferences.answerMode)
            assertEquals(ReplyStyle.SIMPLE, preferences.replyStyle)
            assertEquals(Appearance.SYSTEM, preferences.appearance)
            assertEquals("豆包", preferences.assistantName)
            assertTrue(preferences.captions)
            assertEquals(1, preferences.speed)
            assertFalse(preferences.backgroundChat)
            assertFalse(preferences.controlsOnLeft)
            assertFalse(preferences.microphoneRequested)

            preferences.answerMode = AnswerMode.SINGLE
            preferences.replyStyle = ReplyStyle.DETAILED
            preferences.appearance = Appearance.DARK
            preferences.assistantName = "小白"
            preferences.captions = false
            preferences.speed = 2
            preferences.backgroundChat = true
            preferences.controlsOnLeft = true
            preferences.microphoneRequested = true
            val restored = AssistantPreferences(context)
            assertEquals(AnswerMode.SINGLE, restored.answerMode)
            assertEquals(ReplyStyle.DETAILED, restored.replyStyle)
            assertEquals(Appearance.DARK, restored.appearance)
            assertEquals("小白", restored.assistantName)
            assertFalse(restored.captions)
            assertEquals(2, restored.speed)
            assertTrue(restored.backgroundChat)
            assertTrue(restored.controlsOnLeft)
            assertTrue(restored.microphoneRequested)

            // 直接写旧版键，确认整理常量后仍兼容原设置。
            storage.edit().putString("assistant_name", "小明").putString("appearance", "LIGHT")
                .putInt("speed", 99).putString("answer_mode", "unknown").commit()
            assertEquals("小明", restored.assistantName)
            assertEquals(Appearance.LIGHT, restored.appearance)
            assertEquals(2, restored.speed)
            assertEquals(AnswerMode.CHAT, restored.answerMode)
        } finally { storage.edit().clear().commit() }
    }
}
