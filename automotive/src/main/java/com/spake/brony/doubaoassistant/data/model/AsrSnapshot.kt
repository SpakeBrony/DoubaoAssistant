package com.spake.brony.doubaoassistant.data.model

data class AsrSnapshot(
    val status: AsrStatus = AsrStatus.IDLE,
    val conversation: ConversationContent = ConversationContent(),
    val error: String? = null,
    val confirmedVersion: Long = 0,
    val confirmedText: String = "",
)
