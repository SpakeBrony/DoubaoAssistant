package com.spake.brony.doubaoassistant.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spake.brony.doubaoassistant.data.model.ChatTopic

@Entity(tableName = "topics", indices = [Index(value = ["topic_id"], unique = true)])
internal data class ChatTopicEntity(
    @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "first_message", defaultValue = "''") val firstMessage: String,
    @ColumnInfo(name = "time_millis") val timeMillis: Long,
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "_id") val id: Long = 0,
) {
    init {
        require(topicId.isNotBlank() && topicId.length <= 64 && timeMillis > 0)
        require(firstMessage.length <= 32768)
    }

    fun toTopic(): ChatTopic = ChatTopic(topicId, firstMessage, timeMillis)
}
