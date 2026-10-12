package com.spake.brony.doubaoassistant.data.model

data class KwsSnapshot(
    val status: ListeningStatus = ListeningStatus.STOPPED,
    val mode: AssistantMode = AssistantMode.WAITING,
    val keyword: String = "",
    val command: KwsCommand? = null,
    val detectionCount: Int = 0,
    val echoCancellation: Boolean = false,
    val error: String? = null,
    val asrRequested: Boolean = false,
    val chatPaused: Boolean = false,
)
