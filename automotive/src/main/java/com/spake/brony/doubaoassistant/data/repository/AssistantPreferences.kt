package com.spake.brony.doubaoassistant.data.repository

import android.content.Context
import androidx.core.content.edit
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.AssistantName
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import com.spake.brony.doubaoassistant.data.model.OverlayStyle

class AssistantPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var assistantName: String
        get() = preferences.getString(ASSISTANT_NAME, null)?.takeIf(AssistantName::isValid) ?: AssistantName.DEFAULT
        set(value) {
            require(AssistantName.isValid(value))
            preferences.edit { putString(ASSISTANT_NAME, value) }
        }

    var appearance: Appearance
        get() = Appearance.entries.firstOrNull { it.name == preferences.getString(APPEARANCE, null) } ?: Appearance.SYSTEM
        set(value) { preferences.edit { putString(APPEARANCE, value.name) } }

    var captions: Boolean
        get() = preferences.getBoolean(CAPTIONS, true)
        set(value) { preferences.edit { putBoolean(CAPTIONS, value) } }

    var speed: Int
        get() = preferences.getInt(SPEED, 1).coerceIn(0, 2)
        set(value) { preferences.edit { putInt(SPEED, value.coerceIn(0, 2)) } }

    var replyStyle: ReplyStyle
        get() = ReplyStyle.entries.firstOrNull { it.name == preferences.getString(REPLY_STYLE, null) } ?: ReplyStyle.SIMPLE
        set(value) { preferences.edit { putString(REPLY_STYLE, value.name) } }

    var aiControlEnabled: Boolean
        get() = preferences.getBoolean(AI_CONTROL_ENABLED, true)
        set(value) { preferences.edit { putBoolean(AI_CONTROL_ENABLED, value) } }

    var debugMode: Boolean
        get() = preferences.getBoolean(DEBUG_MODE, false)
        set(value) { preferences.edit { putBoolean(DEBUG_MODE, value) } }

    var answerMode: AnswerMode
        get() = AnswerMode.entries.firstOrNull { it.name == preferences.getString(ANSWER_MODE, null) } ?: AnswerMode.CHAT
        set(value) { preferences.edit { putString(ANSWER_MODE, value.name) } }

    var microphoneRequested: Boolean
        get() = preferences.getBoolean(MICROPHONE_REQUESTED, false)
        set(value) { preferences.edit { putBoolean(MICROPHONE_REQUESTED, value) } }

    var contactsRequested: Boolean
        get() = preferences.getBoolean(CONTACTS_REQUESTED, false)
        set(value) { preferences.edit { putBoolean(CONTACTS_REQUESTED, value) } }

    var callPhoneRequested: Boolean
        get() = preferences.getBoolean(CALL_PHONE_REQUESTED, false)
        set(value) { preferences.edit { putBoolean(CALL_PHONE_REQUESTED, value) } }

    var locationRequested: Boolean
        get() = preferences.getBoolean(LOCATION_REQUESTED, false)
        set(value) { preferences.edit { putBoolean(LOCATION_REQUESTED, value) } }

    var backgroundChat: Boolean
        get() = preferences.getBoolean(BACKGROUND_CHAT, false)
        set(value) { preferences.edit { putBoolean(BACKGROUND_CHAT, value) } }

    var overlayStyle: OverlayStyle
        get() = OverlayStyle.entries.firstOrNull { it.name == preferences.getString(OVERLAY_STYLE, null) } ?: OverlayStyle.DETAILED
        set(value) { preferences.edit { putString(OVERLAY_STYLE, value.name) } }

    var controlsOnLeft: Boolean
        get() = preferences.getBoolean(CONTROLS_ON_LEFT, false)
        set(value) { preferences.edit { putBoolean(CONTROLS_ON_LEFT, value) } }

    // 保留既有文件名与键，升级后继续读取用户已保存的设置。
    private companion object {
        const val FILE_NAME = "assistant"
        const val ASSISTANT_NAME = "assistant_name"
        const val APPEARANCE = "appearance"
        const val CAPTIONS = "captions"
        const val SPEED = "speed"
        const val REPLY_STYLE = "reply_style"
        const val AI_CONTROL_ENABLED = "ai_control_enabled"
        const val DEBUG_MODE = "debug_mode"
        const val MICROPHONE_REQUESTED = "microphone_requested"
        const val CONTACTS_REQUESTED = "contacts_requested"
        const val CALL_PHONE_REQUESTED = "call_phone_requested"
        const val LOCATION_REQUESTED = "location_requested"
        const val BACKGROUND_CHAT = "background_chat"
        const val OVERLAY_STYLE = "overlay_style"
        const val CONTROLS_ON_LEFT = "controls_on_left"
        const val ANSWER_MODE = "answer_mode"
    }
}
