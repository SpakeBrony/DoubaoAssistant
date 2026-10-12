package com.spake.brony.doubaoassistant.data.model

data class ConversationContent(
    val phase: ConversationPhase = ConversationPhase.READY,
    val messages: List<ConversationMessage> = emptyList(),
    val liveText: String = "",
)
