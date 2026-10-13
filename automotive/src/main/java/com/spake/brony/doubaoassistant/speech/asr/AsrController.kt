package com.spake.brony.doubaoassistant.speech.asr

import com.spake.brony.doubaoassistant.data.model.AsrCommand

import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.KwsCommand

// 纯业务启停规则；实际识别由 DoubaoAsrRecognizer 消费共用 PCM。
class AsrController {
    var mode = AssistantMode.WAITING
        private set

    private var listening = false
    private var uiVisible = false
    private var backgroundChat = false
    val asrRequested: Boolean get() = listening && mode == AssistantMode.AWAKE

    fun start(uiVisible: Boolean) {
        listening = true
        this.uiVisible = uiVisible
        if (mode != AssistantMode.MUTED) {
            mode = if (uiVisible || backgroundChat) AssistantMode.AWAKE else AssistantMode.WAITING
        }
    }

    fun stop() {
        listening = false
        backgroundChat = false
        mode = AssistantMode.WAITING
    }

    fun onUiVisibilityChanged(visible: Boolean, backgroundChat: Boolean = false) {
        if (uiVisible == visible && this.backgroundChat == backgroundChat) return
        uiVisible = visible
        this.backgroundChat = backgroundChat
        if (listening && mode != AssistantMode.MUTED) {
            mode = if (visible || backgroundChat) AssistantMode.AWAKE else AssistantMode.WAITING
        }
    }

    fun onCommand(command: KwsCommand): Boolean {
        if (!listening) return false
        mode = command.controlCommand?.let(mode::after) ?: AssistantMode.AWAKE
        return true
    }

    fun onCommand(command: AsrCommand, keepChatOnReturnHome: Boolean = false): Boolean {
        if (!listening) return false
        if (command == AsrCommand.RETURN_HOME && keepChatOnReturnHome) return true
        mode = mode.after(command)
        return true
    }
}
