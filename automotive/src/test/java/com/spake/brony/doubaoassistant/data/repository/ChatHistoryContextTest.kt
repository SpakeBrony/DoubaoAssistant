package com.spake.brony.doubaoassistant.data.repository

import com.spake.brony.doubaoassistant.conversation.ConversationCoordinator
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ChatHistoryContextTest {
    @Test fun jsonOperationSavesOnlySystemResultToSqlAndKeepsPriorChatResponseId() = runTest {
        ConversationRepository.reset()
        val context = RuntimeEnvironment.getApplication()
        val history = ChatHistoryRepository(context) { fail("Database write failed") }
        val requests = Channel<Pair<String, String?>>(Channel.UNLIMITED)
        val json = """{"intent":"set_temperature","slots":{"temperature":28},"success_reply":"空调已调到28度","failure_reply":"空调调节失败"}"""
        val coordinator = ConversationCoordinator(backgroundScope, context::getString,
            saveMessage = history::save, createTopic = history::createTopic,
            lastResponseId = history::lastResponseId, judgeOperation = { true }) { text, id, _ -> flow {
            if (text.startsWith("空调")) {
                emit(ResponseProgress("{"))
                emit(ResponseProgress(json, "response_json"))
            } else emit(ResponseProgress("普通回答", "response_chat"))
            requests.send(text to id)
        } }
        try {
            coordinator.setEnabled(true)
            coordinator.onTextSubmitted("聊聊天")
            assertNull(requests.receive().second)
            val topic = checkNotNull(ConversationRepository.activeTopic)
            coordinator.onTextSubmitted("空调调到28度")
            assertNull(requests.receive().second)
            val rows = history.latest(topic.id)
            val result = rows.last()
            assertEquals("空调调节失败", result.text)
            assertTrue(result.isSystem)
            assertNull(result.previousResponseId)
            assertTrue(rows.none { it.text.startsWith("{") || it.previousResponseId == "response_json" })
            assertEquals("response_chat", history.lastResponseId(topic.id))
            coordinator.onTextSubmitted("继续聊天")
            assertEquals("response_chat", requests.receive().second)
        } finally {
            coordinator.stopSession()
            history.close()
            history.awaitClosed()
            requests.close()
            ConversationRepository.reset()
        }
    }

    @Test fun replyPreferencePersistsButInstructionsNeverEnterRoomUserRowsOrTopicTitle() = runTest {
        ConversationRepository.reset()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("assistant", 0).edit().clear().commit()
        val preferences = AssistantPreferences(context)
        assertEquals(ReplyStyle.SIMPLE, preferences.replyStyle)
        val history = ChatHistoryRepository(context) { fail("Database write failed") }
        val requests = Channel<String>(Channel.UNLIMITED)
        val coordinator = ConversationCoordinator(backgroundScope, context::getString,
            saveMessage = history::save, createTopic = history::createTopic, lastResponseId = history::lastResponseId,
            replyInstruction = { preferences.replyStyle.instruction }, judgeOperation = { true }) { text, _, _ -> flow {
                emit(ResponseProgress("正常回答", "response_${text.length}"))
                requests.send(text)
            } }
        try {
            coordinator.startSession()
            coordinator.setEnabled(true, listening = false)
            for ((index, style) in ReplyStyle.entries.withIndex()) {
                preferences.replyStyle = style
                assertEquals(style, AssistantPreferences(context).replyStyle)
                coordinator.onTextSubmitted("输入问题$index")
                assertEquals("输入问题$index\n${style.instruction}", requests.receive())
            }
            val topic = history.currentTopic()
            assertEquals("输入问题0", topic.firstMessage)
            val rows = history.latest(topic.id)
            assertEquals(listOf("输入问题0", "输入问题1", "输入问题2"),
                rows.filter { it.role == ConversationMessage.USER }.map { it.text })
            assertTrue(rows.none { message -> ReplyStyle.entries.any { message.text.contains(it.instruction) } })
            context.getSharedPreferences("assistant", 0).edit().putString("reply_style", "invalid").commit()
            assertEquals(ReplyStyle.SIMPLE, preferences.replyStyle)
        } finally {
            coordinator.stopSession()
            history.close()
            history.awaitClosed()
            requests.close()
            ConversationRepository.reset()
            context.getSharedPreferences("assistant", 0).edit().clear().commit()
        }
    }

    @Test fun restartReadsSavedResponseIdFromSqlAndSystemRowsDoNotChangeIt() = runTest {
        ConversationRepository.reset()
        val context = RuntimeEnvironment.getApplication()
        val repository = ChatHistoryRepository(context) { fail("Database write failed") }
        val requests = Channel<Pair<String, String?>>(Channel.UNLIMITED)
        var nextResponse = 0
        fun coordinator(history: ChatHistoryRepository) = ConversationCoordinator(backgroundScope, context::getString,
            saveMessage = history::save, createTopic = history::createTopic, lastResponseId = history::lastResponseId, judgeOperation = { true }) { text, id, _ -> flow {
            val responseId = "response_${++nextResponse}"
            emit(ResponseProgress("回复$text"))
            emit(ResponseProgress("回复$text", responseId))
            requests.send(text to id)
        } }
        try {
            val first = coordinator(repository)
            first.startSession()
            first.setEnabled(true)
            first.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "第一轮"))
            assertEquals("第一轮" to null, requests.receive())
            val topic = checkNotNull(ConversationRepository.activeTopic)
            first.queueAcknowledgement("我退下了")
            assertEquals("response_1", repository.lastResponseId(topic.id))
            val rows = repository.latest(topic.id)
            assertEquals(listOf(0, 1, 2), rows.map { it.role })
            assertEquals(listOf(null, "response_1", null), rows.map { it.previousResponseId })
            first.stopSession()
            repository.close()
            repository.awaitClosed()

            ConversationRepository.reset() // 模拟进程结束，不能依靠全局内存 ID。
            val reopened = ChatHistoryRepository(context) { fail("Database write failed after restart") }
            try {
                val next = coordinator(reopened)
                next.restoreHistory(reopened.currentTopic(), reopened.latest(topic.id))
                next.startSession()
                next.setEnabled(true)
                assertEquals("response_1", reopened.lastResponseId(topic.id))
                next.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "第二轮"))
                assertEquals("第二轮" to "response_1", requests.receive())
                assertEquals("response_2", reopened.lastResponseId(topic.id))
                next.newTopic()
                next.setEnabled(true)
                next.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "新话题"))
                assertEquals("新话题" to null, requests.receive())
            } finally { reopened.close(); reopened.awaitClosed() }
        } finally {
            repository.close()
            repository.awaitClosed()
            requests.close()
            ConversationRepository.reset()
        }
    }
}
