package com.spake.brony.doubaoassistant.data.model

import java.util.UUID

data class ChatTopic(
    val id: String = UUID.randomUUID().toString(),
    val firstMessage: String = "",
    val timeMillis: Long = System.currentTimeMillis().coerceAtLeast(1),
)
