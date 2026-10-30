package com.spake.brony.doubaoassistant.data.repository

import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.model.ConversationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// UI 的进程内镜像，不保存 Context/网络对象；发送给 AI 的上下文 ID 以 SQLite 查询为准。
object ConversationRepository {
    private val mutableSnapshot = MutableStateFlow(ConversationSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    var activeTopic: ChatTopic? = null
        private set

    internal fun setTopic(topic: ChatTopic) {
        activeTopic = topic
    }

    internal fun publish(snapshot: ConversationSnapshot) {
        mutableSnapshot.value = snapshot
    }

    internal fun reset() {
        activeTopic = null
        publish(ConversationSnapshot())
    }
}
