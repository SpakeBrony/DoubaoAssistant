package com.spake.brony.doubaoassistant.data.repository

import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// 只保留有上限的文字状态，不保存 SDK、Context、回调或音频。
object AsrRepository {
    private val mutableSnapshot = MutableStateFlow(AsrSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    internal fun publish(snapshot: AsrSnapshot) { mutableSnapshot.value = snapshot }
    internal fun clear() { mutableSnapshot.value = AsrSnapshot() }
    internal fun stop() {
        val previous = snapshot.value
        publish(previous.copy(status = AsrStatus.IDLE, error = null,
            conversation = previous.conversation.copy(phase = ConversationPhase.READY, liveText = "")))
    }
}
