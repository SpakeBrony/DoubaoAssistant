package com.spake.brony.doubaoassistant.data.local

import com.spake.brony.doubaoassistant.data.local.dao.ChatHistoryDao
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.repository.ChatHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ChatHistoryDatabaseTest {
    private fun withDatabase(block: (ChatHistoryDao) -> Unit) = runBlocking(Dispatchers.IO) {
        val database = ChatHistoryDatabase.create(RuntimeEnvironment.getApplication())
        try { block(database.historyDao()) } finally { database.close() }
    }

    @Test fun responseIdsSurviveReopenAndOnlyUseCompletedAiInTheSameTopic() {
        val topic = ChatTopic("first", timeMillis = 1)
        val other = ChatTopic("other", timeMillis = 2)
        withDatabase { dao ->
            dao.createTopic(topic)
            dao.createTopic(other)
            val assistant = ConversationMessage("完成的回答", 1, "ai", 3, topic.id, "response_1")
            dao.save(assistant)
            dao.save(ConversationMessage("系统提示", 2, "system", 4, topic.id))
            dao.save(ConversationMessage("未完成回答", 1, "partial", 5, topic.id))
            dao.save(ConversationMessage("其他话题", 1, "other_ai", 6, other.id, "response_other"))
            assertEquals("response_1", dao.lastResponseId(topic.id))
            assertEquals("response_other", dao.lastResponseId(other.id))
            assertNull(dao.lastResponseId("missing"))
            for (role in listOf(0, 2)) assertThrows(IllegalArgumentException::class.java) {
                dao.save(ConversationMessage("不能有上下文 ID", role, "invalid_$role", 7, topic.id, "bad"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                dao.save(ConversationMessage("无效角色", 3, "invalid_role", 7, topic.id))
            }
            dao.save(ConversationMessage("新的完成回答", 1, "new_ai", 8, topic.id, "response_2"))
            dao.save(assistant.copy(text = "更新早期回答"))
            assertEquals("response_2", dao.lastResponseId(topic.id))
        }
        withDatabase { dao ->
            assertEquals("response_2", dao.lastResponseId(topic.id))
            val system = dao.latest(topic.id).single { it.id == "system" }
            assertEquals(2, system.role)
            assertNull(system.previousResponseId)
            dao.clearAll(ChatTopic("new", timeMillis = 10))
            assertNull(dao.lastResponseId(topic.id))
            assertNull(dao.lastResponseId(other.id))
        }
    }

    @Test fun responseLookupDrainsPriorWritesAndIsNotLimitedToFortyUiMessages() = runTest {
        var errors = 0
        val repository = ChatHistoryRepository(RuntimeEnvironment.getApplication()) { errors++ }
        try {
            val topic = ChatTopic("topic", timeMillis = 1)
            repository.createTopic(topic)
            val message = ConversationMessage("回答", 1, "ai", 2, topic.id)
            repository.save(message)
            repository.save(message.copy(previousResponseId = "saved_response"))
            repeat(45) { repository.save(ConversationMessage("系统$it", 2, "system_$it", 3L + it, topic.id)) }
            assertEquals("saved_response", repository.lastResponseId(topic.id))
            assertTrue(repository.latest(topic.id).none { it.id == "ai" })
            assertEquals(0, errors)
        } finally { repository.close(); repository.awaitClosed() }
    }

    @Test fun streamingUpdatesOneRowAndSurvivesReopening() {
        val topic = ChatTopic("topic_1", timeMillis = 99)
        val user = ConversationMessage("第一句", false, "user_1", 100, topic.id)
        val assistant = ConversationMessage("部", true, "assistant_1", 101, topic.id)
        withDatabase { dao ->
            dao.createTopic(topic)
            dao.save(user)
            dao.save(assistant)
            dao.save(ConversationMessage("下一句", false, "user_2", 102, topic.id))
            dao.save(assistant.copy(text = "部分回答"))
        }
        withDatabase { dao ->
            val messages = dao.latest(topic.id)
            assertEquals(listOf("第一句", "部分回答", "下一句"), messages.map { it.text })
            assertEquals(listOf(false, true, false), messages.map { it.isAssistant })
            assertEquals(101L, messages[1].timeMillis)
            assertEquals("assistant_1", messages[1].id)
            assertTrue(messages.all { it.topicId == topic.id })
            assertEquals("第一句", dao.currentTopic().firstMessage)
        }
    }

    @Test fun closeDrainsQueuedWritesAndRestoreIsBounded() = runTest {
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val repository = ChatHistoryRepository(RuntimeEnvironment.getApplication()) { errors.incrementAndGet() }
        val topic = ChatTopic("topic_1", timeMillis = 999)
        repository.createTopic(topic)
        repeat(45) { repository.save(ConversationMessage("第${it}句", false, "id_$it", 1000L + it, topic.id)) }
        val nextTopic = ChatTopic("topic_2", timeMillis = 2000)
        repository.createTopic(nextTopic)
        repository.close()
        repository.awaitClosed()
        assertEquals(0, errors.get())
        withDatabase { dao ->
            val latest = dao.latest(topic.id)
            assertEquals(40, latest.size)
            assertEquals("第5句", latest.first().text)
            assertEquals("第44句", latest.last().text)
            assertEquals(nextTopic, dao.currentTopic())
            assertTrue(dao.latest(nextTopic.id).isEmpty())
        }
    }

    @Test fun newTopicIsEmptyAndMessagesStayWithTheirOriginalTopic() {
        val first = ChatTopic("first", timeMillis = 1)
        val second = ChatTopic("second", timeMillis = 2)
        withDatabase { dao ->
            dao.createTopic(first)
            dao.save(ConversationMessage("旧话题第一句", false, "old_user", 3, first.id))
            val reply = ConversationMessage("未完", true, "old_reply", 4, first.id)
            dao.save(reply)
            dao.createTopic(second)
            dao.createTopic(ChatTopic("delayed_old_topic", timeMillis = 1))
            assertEquals(second, dao.currentTopic())
            assertTrue(dao.latest(second.id).isEmpty())
            dao.save(ConversationMessage("新话题第一句", false, "new_user", 5, second.id))
            dao.save(ConversationMessage("新话题第二句", false, "next_user", 6, second.id))
            dao.save(reply.copy(text = "旧回复的最后一段"))
            assertEquals(listOf("旧话题第一句", "旧回复的最后一段"), dao.latest(first.id).map { it.text })
            assertEquals(listOf("新话题第一句", "新话题第二句"), dao.latest(second.id).map { it.text })
            assertEquals("新话题第一句", dao.currentTopic().firstMessage)
        }
        withDatabase { dao ->
            assertEquals(second.id, dao.currentTopic().id)
            assertEquals(2, dao.latest(second.id).size)
            assertEquals(2, dao.latest(first.id).size)
        }
    }

    @Test fun clearAllIsAtomicAndLeavesOnlyTheNewTopic() {
        val old = ChatTopic("old", timeMillis = 1)
        val next = ChatTopic("new", timeMillis = 2)
        withDatabase { dao ->
            dao.createTopic(old)
            val message = ConversationMessage("旧消息", false, "old_message", 2, old.id)
            dao.save(message)
            assertThrows(IllegalArgumentException::class.java) { dao.clearAll(next.copy(id = "")) }
            assertEquals(listOf(message), dao.latest(old.id))
            dao.clearAll(next)
            assertTrue(dao.latest(old.id).isEmpty())
            assertTrue(dao.latest(next.id).isEmpty())
            assertEquals(next, dao.currentTopic())
        }
        withDatabase { dao ->
            assertEquals(next, dao.currentTopic())
            assertTrue(dao.latest(old.id).isEmpty())
        }
    }

    @Test fun clearAllRunsAfterQueuedOldWritesAndBeforeNewMessages() = runTest {
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val repository = ChatHistoryRepository(RuntimeEnvironment.getApplication()) { errors.incrementAndGet() }
        val old = ChatTopic("old", timeMillis = 1)
        val next = ChatTopic("new", timeMillis = 2)
        repository.createTopic(old)
        repeat(20) { repository.save(ConversationMessage("旧回复$it", true, "old_$it", 3L + it, old.id)) }
        repository.createTopic(next)
        repository.clearAll(next)
        val message = ConversationMessage("重新开始", false, "new_message", 30, next.id)
        repository.save(message)
        repository.close()
        repository.awaitClosed()
        assertEquals(0, errors.get())
        withDatabase { dao ->
            assertTrue(dao.latest(old.id).isEmpty())
            assertEquals(listOf(message), dao.latest(next.id))
        }
    }

    @Test fun missingTopicAndMovingAMessageToAnotherTopicAreRejected() {
        withDatabase { dao ->
            val first = ChatTopic("first", timeMillis = 1)
            val second = ChatTopic("second", timeMillis = 2)
            val message = ConversationMessage("原消息", false, "message", 3, first.id)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { dao.save(message) }
            dao.createTopic(first)
            dao.createTopic(second)
            dao.save(message)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                dao.save(message.copy(text = "不该覆盖", topicId = second.id))
            }
            assertEquals(listOf(message), dao.latest(first.id))
            assertTrue(dao.latest(second.id).isEmpty())
        }
    }

    @Test fun roomRejectsMainThreadQueries() {
        val database = ChatHistoryDatabase.create(RuntimeEnvironment.getApplication())
        try {
            assertThrows(IllegalStateException::class.java) { database.historyDao().currentTopic() }
        } finally { database.close() }
    }

    @Test fun incompatibleTestSchemaIsRebuiltWithoutMigration() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val original = ChatHistoryDatabase.create(context)
        try {
            val dao = original.historyDao()
            val topic = ChatTopic("old_test", timeMillis = 1)
            dao.createTopic(topic)
            dao.save(ConversationMessage("可丢弃的测试记录", 1, "old_ai", 2, topic.id, "old_response"))
            original.openHelper.writableDatabase.version = 99
        } finally { original.close() }
        val reopened = ChatHistoryDatabase.create(context)
        try {
            val dao = reopened.historyDao()
            assertTrue(dao.latest("old_test").isEmpty())
            assertNull(dao.lastResponseId("old_test"))
            assertTrue(dao.currentTopic().firstMessage.isEmpty())
        } finally { reopened.close() }
    }
}
