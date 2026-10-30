package com.spake.brony.doubaoassistant.viewmodel

import androidx.lifecycle.viewModelScope
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.conversation.ConversationStateResolver
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.model.AssistantName
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import com.spake.brony.doubaoassistant.data.model.OverlayStyle
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import com.spake.brony.doubaoassistant.data.repository.AsrRepository
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.viewmodel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KwsUiState(
    val snapshot: KwsSnapshot = KwsSnapshot(),
    val errorMessage: String? = null,
    val captions: Boolean = true,
    val settingsOpen: Boolean = false,
    val appearance: Appearance = Appearance.SYSTEM,
    val speed: Int = 1,
    val replyStyle: ReplyStyle = ReplyStyle.SIMPLE,
    val aiControlEnabled: Boolean = true,
    val debugMode: Boolean = false,
    val answerMode: AnswerMode = AnswerMode.CHAT,
    val assistantName: String = AssistantName.DEFAULT,
    val backgroundChat: Boolean = false,
    val overlayStyle: OverlayStyle = OverlayStyle.DETAILED,
    val controlsOnLeft: Boolean = false,
    val ended: Boolean = false,
    val conversation: ConversationContent = ConversationContent(),
    val asr: AsrSnapshot = AsrSnapshot(),
    val chatError: String? = null,
    val ttsError: String? = null,
    val historyError: String? = null,
    val audioLevel: Float = 0f,
) {
    val isListening: Boolean
        get() = snapshot.status == ListeningStatus.LISTENING || snapshot.status == ListeningStatus.STARTING

    val phase: ConversationPhase get() = ConversationStateResolver.resolve(
        snapshot, asr, conversation.phase, chatError, errorMessage, ended)

    val isPaused: Boolean get() = phase == ConversationPhase.PAUSED || phase == ConversationPhase.ENDED || phase == ConversationPhase.ERROR
    val isReplying: Boolean get() = conversation.phase == ConversationPhase.THINKING ||
        conversation.phase == ConversationPhase.EXECUTING || conversation.phase == ConversationPhase.SPEAKING
    val canControlChat: Boolean get() = !ended && (isListening || isReplying || snapshot.status == ListeningStatus.STOPPED)
    val waveformLevel: Float get() = if (isListening && snapshot.asrRequested && !isPaused &&
        asr.conversation.liveText.isNotEmpty()) audioLevel else 0f
}

class KwsViewModel(
    snapshots: StateFlow<KwsSnapshot> = KwsRepository.snapshot,
    asrSnapshots: StateFlow<AsrSnapshot> = AsrRepository.snapshot,
    conversations: StateFlow<ConversationSnapshot> = ConversationRepository.snapshot,
    levels: StateFlow<Float> = KwsRepository.audioLevel,
) : BaseViewModel() {
    private val mutableUiState = MutableStateFlow(KwsUiState(snapshots.value,
        asr = asrSnapshots.value, conversation = conversations.value.content,
        chatError = conversations.value.error, ttsError = conversations.value.ttsError,
        historyError = conversations.value.historyError, audioLevel = levels.value))
    val uiState: StateFlow<KwsUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            snapshots.collect { mutableUiState.value = mutableUiState.value.copy(snapshot = it, errorMessage = null) }
        }
        viewModelScope.launch {
            asrSnapshots.collect { mutableUiState.value = mutableUiState.value.copy(asr = it) }
        }
        viewModelScope.launch {
            conversations.collect { mutableUiState.value = mutableUiState.value.copy(conversation = it.content,
                chatError = it.error, ttsError = it.ttsError, historyError = it.historyError) }
        }
        viewModelScope.launch {
            levels.collect { mutableUiState.value = mutableUiState.value.copy(audioLevel = it) }
        }
    }

    fun onListeningFailed(message: String) {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = message)
    }

    fun onPlaybackFailed(message: String) {
        mutableUiState.value = mutableUiState.value.copy(ttsError = message)
    }

    fun loadPreferences(appearance: Appearance, captions: Boolean, speed: Int, assistantName: String = uiState.value.assistantName) {
        mutableUiState.value = mutableUiState.value.copy(appearance = appearance, captions = captions,
            speed = speed.coerceIn(0, 2), assistantName = assistantName)
    }

    fun setAssistantName(name: String) {
        require(AssistantName.isValid(name))
        mutableUiState.value = mutableUiState.value.copy(assistantName = name)
    }

    fun setBackgroundChat(enabled: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(backgroundChat = enabled)
    }

    fun setOverlayStyle(style: OverlayStyle) {
        mutableUiState.value = mutableUiState.value.copy(overlayStyle = style)
    }

    fun setControlsOnLeft(left: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(controlsOnLeft = left)
    }

    fun toggleCaptions() {
        mutableUiState.value = mutableUiState.value.copy(captions = !mutableUiState.value.captions)
    }

    fun setSpeed(speed: Int) {
        mutableUiState.value = mutableUiState.value.copy(speed = speed.coerceIn(0, 2))
    }

    fun setReplyStyle(style: ReplyStyle) {
        mutableUiState.value = mutableUiState.value.copy(replyStyle = style)
    }

    fun setAiControlEnabled(enabled: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(aiControlEnabled = enabled)
    }

    fun setDebugMode(enabled: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(debugMode = enabled)
    }

    fun setAnswerMode(mode: AnswerMode) {
        mutableUiState.value = mutableUiState.value.copy(answerMode = mode)
    }

    fun openSettings() {
        if (uiState.value.settingsOpen) return
        mutableUiState.value = mutableUiState.value.copy(settingsOpen = true)
    }

    fun closeSettings() {
        mutableUiState.value = mutableUiState.value.copy(settingsOpen = false)
    }

    fun onSessionStarted() {
        mutableUiState.value = mutableUiState.value.copy(ended = false, errorMessage = null)
    }

    fun onSessionEnded() {
        mutableUiState.value = mutableUiState.value.copy(ended = true)
    }

    // UI 接收真实对话数据；生产环境不填充设计稿里的演示回复。
    fun onConversationChanged(content: ConversationContent) {
        mutableUiState.value = mutableUiState.value.copy(conversation = content)
    }
}
