package com.spake.brony.doubaoassistant.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spake.brony.doubaoassistant.data.model.ConversationMessage

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = ChatTopicEntity::class, parentColumns = ["topic_id"],
        childColumns = ["topic_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["message_id"], unique = true), Index(value = ["topic_id", "_id"]),
        Index(value = ["topic_id", "is_assistant", "_id"])],
)
internal data class ChatMessageEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "time_millis") val timeMillis: Long,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "is_assistant") val role: Int,
    @ColumnInfo(name = "previous_response_id") val previousResponseId: String?,
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "_id") val id: Long = 0,
    @ColumnInfo(name = "json_message") val jsonMessage: String? = null,
) {
    init {
        require(messageId.isNotBlank() && messageId.length <= 64 && timeMillis > 0)
        require(topicId.isNotBlank() && topicId.length <= 64)
        require(content.isNotBlank() && content.length <= 32768)
        require(role in ConversationMessage.USER..ConversationMessage.SYSTEM)
        require(previousResponseId == null ||
            (role == ConversationMessage.ASSISTANT && previousResponseId.isNotBlank()))
    }

    fun toMessage(): ConversationMessage = ConversationMessage(
        content, role, messageId, timeMillis, topicId, previousResponseId,
        jsonMessage)
}
