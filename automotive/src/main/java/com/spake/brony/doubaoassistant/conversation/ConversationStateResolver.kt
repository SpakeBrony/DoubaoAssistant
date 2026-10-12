package com.spake.brony.doubaoassistant.conversation

import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus

// 页面和悬浮窗共用优先级；麦克风开关与聊天暂停独立。
internal object ConversationStateResolver {
    fun resolve(
        kws: KwsSnapshot,
        asr: AsrSnapshot,
        phase: ConversationPhase,
        chatError: String? = null,
        uiError: String? = null,
        ended: Boolean = false,
    ): ConversationPhase = when {
        ended -> ConversationPhase.ENDED
        uiError != null || kws.status == ListeningStatus.ERROR || asr.error != null ||
            chatError != null || phase == ConversationPhase.ERROR -> ConversationPhase.ERROR
        phase == ConversationPhase.THINKING || phase == ConversationPhase.SPEAKING -> phase
        kws.chatPaused || kws.mode == AssistantMode.MUTED ||
            kws.status == ListeningStatus.LISTENING && !kws.asrRequested -> ConversationPhase.PAUSED
        else -> phase
    }
}
