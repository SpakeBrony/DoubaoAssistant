package com.spake.brony.doubaoassistant.conversation

import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import com.spake.brony.doubaoassistant.data.model.TtsProgress
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.speech.tts.StreamingTts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiOperationTest {
    private val json = """{"intent":"set_temperature","slots":{"temperature":28},"success_reply":"空调已调到28度","failure_reply":"空调调节失败"}"""
    private val getString: (Int) -> String = { "localized:$it" }
    @Before fun setUp() { ConversationRepository.reset() }
    @After fun tearDown() { ConversationRepository.reset() }

    @Test fun temperatureIntentPassesNumbersAndDefaultOrExplicitSeatToController() = runTest {
        val calls = mutableListOf<Pair<Float, String>>()
        val executor = AiOperationExecutor { temperature, seat -> calls += temperature to seat; true }
        assertTrue(executor.execute(checkNotNull(AiOperation.parse(json))))
        assertTrue(executor.execute(checkNotNull(AiOperation.parse(json.replace("\"temperature\":28", "\"temperature\":27.5,\"seat\":\"passenger\"")))))
        assertEquals(listOf(28f to "driver", 27.5f to "passenger"), calls)
    }

    @Test fun invalidTemperatureParametersNeverReachController() = runTest {
        var calls = 0
        val executor = AiOperationExecutor { _, _ -> calls++; true }
        for (slots in listOf("{}", """{"temperature":"28"}""", """{"temperature":null}""",
            """{"temperature":28,"seat":"rear"}""", """{"temperature":28,"seat":null}""")) {
            val operation = checkNotNull(AiOperation.parse(json.replace("{\"temperature\":28}", slots)))
            assertFalse(executor.execute(operation))
        }
        assertEquals(0, calls)
    }

    private class Tts : StreamingTts {
        val chunks = mutableListOf<String>()
        var starts = 0
        var finishes = 0
        var version = 0L
        override fun start(version: Long, speed: Int) { starts++; this.version = version }
        override fun append(delta: String) { if (delta.isNotEmpty()) chunks += delta }
        override fun finish() { finishes++ }
        override fun cancel() = Unit
    }

    @Test fun streamingJsonAndExecutionStayHiddenUntilSuccessThenSaveOnlySystemText() = runTest {
        val tts = Tts()
        val saved = linkedMapOf<String, ConversationMessage>()
        val operations = mutableListOf<AiOperation>()
        val finishResponse = CompletableDeferred<Unit>()
        val finishOperation = CompletableDeferred<Boolean>()
        var pauses = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            saveMessage = { saved[it.id] = it }, answerMode = { AnswerMode.SINGLE },
            onSingleAnswerFinished = { pauses++ }, executeOperation = {
                operations += it
                finishOperation.await()
            }) { _, _ -> flow {
                for (size in 1..json.length) {
                    emit(ResponseProgress(" \n" + json.take(size)))
                    assertTrue(tts.chunks.isEmpty())
                    assertEquals(0, tts.starts)
                    assertTrue(operations.isEmpty())
                    assertTrue(saved.values.all { it.role == ConversationMessage.USER })
                    assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
                }
                finishResponse.await()
                emit(ResponseProgress(json, "response_json"))
                emit(ResponseProgress(json, "response_json")) // 重复完成事件也不能执行两次。
            } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("把空调调到28度")
        runCurrent()
        finishResponse.complete(Unit)
        runCurrent()
        assertEquals(1, operations.size)
        assertEquals(28, operations.single().slots.getInt("temperature"))
        assertTrue(tts.chunks.isEmpty())
        assertEquals(ConversationPhase.THINKING, ConversationRepository.snapshot.value.content.phase)
        finishOperation.complete(true)
        runCurrent()
        assertEquals(listOf("空调已调到28度"), tts.chunks)
        assertEquals(1, tts.finishes)
        assertEquals(1, operations.size)
        val result = saved.values.last()
        assertEquals(ConversationMessage.SYSTEM, result.role)
        assertEquals(tts.chunks.single(), result.text)
        assertNull(result.previousResponseId)
        assertEquals(result, ConversationRepository.snapshot.value.content.messages.last())
        assertEquals(0, pauses)
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(1, pauses)
    }

    @Test fun emptyExecutorAndThrownExecutionBothUseFailureReply() = runTest {
        for (throwError in listOf(false, true)) {
            val tts = Tts()
            val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
                executeOperation = { if (throwError) error("接口失败") else AiOperationExecutor().execute(it) }) { _, _ -> flow {
                emit(ResponseProgress(json, "response_json"))
            } }
            coordinator.setEnabled(true)
            coordinator.onTextSubmitted("把空调调到28度")
            runCurrent()
            assertEquals(listOf("空调调节失败"), tts.chunks)
            assertTrue(ConversationRepository.snapshot.value.content.messages.last().isSystem)
            assertNull(ConversationRepository.snapshot.value.content.messages.last().previousResponseId)
            coordinator.stopSession()
        }
    }

    @Test fun malformedJsonHasFriendlySystemReplyAndNeverExecutesOrSpeaksJson() = runTest {
        for (invalid in listOf("{", "{}", "[]", json + "尾部文字", json.replace("\"slots\":{\"temperature\":28}", "\"slots\":[]"))) {
            val tts = Tts()
            var executions = 0
            val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
                executeOperation = { executions++; true }) { _, _ -> flow { emit(ResponseProgress(invalid, "bad_id")) } }
            coordinator.setEnabled(true)
            coordinator.onTextSubmitted("空调")
            runCurrent()
            assertEquals(0, executions)
            assertEquals(listOf(getString(R.string.voice_operation_invalid_reply)), tts.chunks)
            assertTrue(ConversationRepository.snapshot.value.content.messages.last().isSystem)
            assertNull(ConversationRepository.snapshot.value.content.messages.last().previousResponseId)
            coordinator.stopSession()
        }
    }

    @Test fun interruptedJsonOrFailedModelNeverExecutesAndDoesNotEnterHistory() = runTest {
        val tts = Tts()
        var executions = 0
        val complete = CompletableDeferred<Unit>()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            executeOperation = { executions++; true }) { _, _ -> flow {
            emit(ResponseProgress(json.take(20)))
            complete.await()
            emit(ResponseProgress(json, "id", error = "网络失败"))
        } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("空调")
        runCurrent()
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "换个话题")))
        complete.complete(Unit)
        runCurrent()
        assertEquals(0, executions)
        assertTrue(tts.chunks.isEmpty())
        assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
        coordinator.onTextSubmitted("空调")
        runCurrent()
        assertEquals(0, executions)
        assertTrue(tts.chunks.isEmpty())
        assertEquals("网络失败", ConversationRepository.snapshot.value.error)
    }

    @Test fun stoppingDuringOperationCancelsPendingResultWithoutSpeakingSuccess() = runTest {
        val tts = Tts()
        val completed = CompletableDeferred<Boolean>()
        var executions = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            executeOperation = { executions++; completed.await() }) { _, _ -> flow { emit(ResponseProgress(json, "id")) } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("空调")
        runCurrent()
        assertEquals(1, executions)
        coordinator.stopSession()
        completed.complete(true)
        runCurrent()
        assertTrue(tts.chunks.isEmpty())
        assertFalse(coordinator.hasActiveReply)
        assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
    }

    @Test fun fencedJsonPrefixesStayHiddenAndUnknownDoesNotExecute() = runTest {
        assertNull(AiOperation.isJsonCandidate(" \n"))
        assertNull(AiOperation.isJsonCandidate("`"))
        assertNull(AiOperation.isJsonCandidate("``"))
        assertNull(AiOperation.isJsonCandidate("```j"))
        assertEquals(true, AiOperation.isJsonCandidate("```json\n{"))
        assertEquals(false, AiOperation.isJsonCandidate("你好"))
        assertEquals("set_temperature", AiOperation.parse("```json\n$json\n```")?.intent)
        assertNull(AiOperation.parse(json.replace("\"success_reply\":\"空调已调到28度\",", "")))
        val unknown = """{"intent":"unknown","slots":{},"success_reply":"","failure_reply":"暂不支持这个操作"}"""
        val tts = Tts()
        var executions = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            executeOperation = { executions++; true }) { _, _ -> flow {
            val fenced = "```json\n$unknown\n```"
            for (size in 1..fenced.length) {
                emit(ResponseProgress(fenced.take(size)))
                assertTrue(tts.chunks.isEmpty())
                assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
            }
            emit(ResponseProgress(fenced, "unknown_id"))
        } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("空调")
        runCurrent()
        assertEquals(0, executions)
        assertEquals(listOf("暂不支持这个操作"), tts.chunks)
    }
}
