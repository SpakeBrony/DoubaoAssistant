package com.spake.brony.doubaoassistant.data.model

data class ConversationMessage(
    val text: String,
    val role: Int,
    val id: String = "",
    val timeMillis: Long = 0,
    val topicId: String = "",
    val previousResponseId: String? = null,
) {
    val isAssistant: Boolean get() = role == ASSISTANT
    val isSystem: Boolean get() = role == SYSTEM

    constructor(text: String, isAssistant: Boolean, id: String = "", timeMillis: Long = 0,
        topicId: String = "", previousResponseId: String? = null) :
        this(text, if (isAssistant) ASSISTANT else USER, id, timeMillis, topicId, previousResponseId)

    companion object {
        const val USER = 0
        const val ASSISTANT = 1
        const val SYSTEM = 2
    }
}
