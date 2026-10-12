package com.spake.brony.doubaoassistant.data.model

data class ConversationSnapshot(
    val content: ConversationContent = ConversationContent(),
    val error: String? = null,
    val ttsError: String? = null,
    val historyError: String? = null,
)
