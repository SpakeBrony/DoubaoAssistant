package com.spake.brony.doubaoassistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.spake.brony.doubaoassistant.data.local.entity.ChatMessageEntity
import com.spake.brony.doubaoassistant.data.local.entity.ChatTopicEntity
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.model.ConversationMessage

// 同步 DAO 仅由 Repository 的 IO 队列调用，Room 默认禁止主线程访问。
@Dao
internal abstract class ChatHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertTopic(topic: ChatTopicEntity)

    @Insert
    protected abstract fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM topics ORDER BY time_millis DESC, _id DESC LIMIT 1")
    protected abstract fun findCurrentTopic(): ChatTopicEntity?

    @Query("SELECT * FROM messages WHERE topic_id = :topicId ORDER BY _id DESC LIMIT 40")
    protected abstract fun findLatestMessages(topicId: String): List<ChatMessageEntity>

    @Query("SELECT time_millis FROM messages ORDER BY _id DESC LIMIT 1")
    abstract fun lastMessageTime(): Long?

    @Query("""UPDATE messages SET content = :content, is_assistant = :role, previous_response_id = :responseId,
        json_message = :jsonMessage
        WHERE message_id = :messageId AND topic_id = :topicId""")
    protected abstract fun updateMessage(messageId: String, topicId: String, content: String, role: Int,
        responseId: String?, jsonMessage: String?): Int

    @Query("UPDATE topics SET first_message = :text WHERE topic_id = :topicId AND first_message = ''")
    protected abstract fun setFirstMessage(topicId: String, text: String)

    @Query("DELETE FROM messages")
    protected abstract fun deleteMessages()

    @Query("DELETE FROM topics")
    protected abstract fun deleteTopics()

    @Query("""SELECT previous_response_id FROM messages
        WHERE topic_id = :topicId AND is_assistant = 1 AND previous_response_id IS NOT NULL
            AND json_message IS NULL
            AND LENGTH(TRIM(previous_response_id)) > 0 ORDER BY _id DESC LIMIT 1""")
    abstract fun lastResponseId(topicId: String): String?

    fun createTopic(topic: ChatTopic) {
        insertTopic(ChatTopicEntity(topic.id, topic.firstMessage, topic.timeMillis))
    }

    @Transaction
    open fun currentTopic(): ChatTopic = findCurrentTopic()?.toTopic() ?: ChatTopic().also(::createTopic)

    fun latest(topicId: String): List<ConversationMessage> =
        findLatestMessages(topicId).asReversed().map(ChatMessageEntity::toMessage)

    @Transaction
    open fun save(message: ConversationMessage) {
        val entity = ChatMessageEntity(message.id, message.topicId, message.timeMillis,
            message.text, message.role, message.previousResponseId,
            jsonMessage = message.jsonMessage)
        // 更新回复与调试信息，不能改变流式消息的流水号、时间或所属话题。
        if (updateMessage(entity.messageId, entity.topicId, entity.content, entity.role,
                entity.previousResponseId, entity.jsonMessage) == 0) {
            insertMessage(entity)
        }
        if (message.role == ConversationMessage.USER) setFirstMessage(message.topicId, message.text)
    }

    @Transaction
    open fun clearAll(topic: ChatTopic) {
        val entity = ChatTopicEntity(topic.id, topic.firstMessage, topic.timeMillis)
        deleteMessages()
        deleteTopics()
        insertTopic(entity)
    }
}
