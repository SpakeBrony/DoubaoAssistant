package com.spake.brony.doubaoassistant.conversation

import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import com.spake.brony.doubaoassistant.data.model.TtsProgress
import com.spake.brony.doubaoassistant.data.model.AsrCommand
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.speech.tts.StreamingTts
import com.spake.brony.doubaoassistant.speech.asr.AsrIntentPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCoordinatorTest {
    private fun lastReplyId(): String? = ConversationRepository.snapshot.value.content.messages
        .lastOrNull { it.isAssistant && it.previousResponseId != null }?.previousResponseId

    private val getString: (Int) -> String = { "localized:$it" }
    @Before fun setUp() { ConversationRepository.reset() }
    @After fun tearDown() { ConversationRepository.reset() }

    private fun speech(version: Long, text: String) = AsrSnapshot(
        status = AsrStatus.LISTENING, confirmedVersion = version, confirmedText = text)

    private class FakeTts : StreamingTts {
        var version = 0L
        var speed = 1
        val chunks = mutableListOf<String>()
        var finished = 0
        var cancelled = 0
        override fun start(version: Long, speed: Int) { this.version = version; this.speed = speed }
        override fun append(delta: String) { if (delta.isNotEmpty()) chunks += delta }
        override fun finish() { finished++ }
        override fun cancel() { cancelled++ }
    }

    @Test fun singleAnswerWaitsForBothStreamAndPlaybackThenPausesOnceAndCanResume() = runTest {
        val tts = FakeTts()
        val updates = MutableSharedFlow<ResponseProgress>()
        var pauses = 0
        lateinit var coordinator: ConversationCoordinator
        coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            answerMode = { AnswerMode.SINGLE }, onSingleAnswerFinished = {
                pauses++
                coordinator.setEnabled(false)
            }) { _, _ -> updates.take(2) }
        coordinator.startSession()
        coordinator.setEnabled(true)
        assertEquals(0, pauses) // 开始监听或无回复的空闲回调不能触发暂停。
        coordinator.onTextSubmitted("问题")
        runCurrent()
        updates.emit(ResponseProgress("回答"))
        updates.emit(ResponseProgress("回答", "response_1"))
        runCurrent()
        assertEquals(0, pauses)
        assertTrue(coordinator.hasActiveReply)
        val version = tts.version
        coordinator.onTtsUpdate(version, TtsProgress(completed = true))
        assertEquals(1, pauses)
        assertFalse(coordinator.hasActiveReply)
        coordinator.onTtsUpdate(version, TtsProgress(completed = true))
        assertEquals(1, pauses)
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("继续聊")
        runCurrent()
        assertEquals(1, updates.subscriptionCount.value)
        // 播放先结束时，也必须等待 AI 流结束。
        updates.emit(ResponseProgress("第二轮"))
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(1, pauses)
        updates.emit(ResponseProgress("第二轮", "response_2"))
        runCurrent()
        assertEquals(2, pauses)
    }

    @Test fun defaultChatKeepsListeningAndModeChangesApplyToNextQuestion() = runTest {
        var mode = AnswerMode.CHAT
        var pauses = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString,
            answerMode = { mode }, onSingleAnswerFinished = { pauses++ }) { _, _ -> flow {
            emit(ResponseProgress("回答", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("聊天模式问题")
        mode = AnswerMode.SINGLE
        runCurrent()
        assertEquals(0, pauses)
        coordinator.onTextSubmitted("单次模式问题")
        mode = AnswerMode.CHAT
        runCurrent()
        assertEquals(1, pauses)
        coordinator.onTextSubmitted("再次聊天")
        runCurrent()
        assertEquals(1, pauses)
    }

    @Test fun singleAnswerDoesNotAutoPauseOnCancelledFailedOrSystemReplies() = runTest {
        val tts = FakeTts()
        var pauses = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            answerMode = { AnswerMode.SINGLE }, onSingleAnswerFinished = { pauses++ }) { text, _ -> flow {
            when (text) {
                "等待" -> awaitCancellation()
                "失败" -> emit(ResponseProgress(error = "测试网络错误"))
                else -> emit(ResponseProgress("正常回复", "response_1"))
            }
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("等待")
        runCurrent()
        coordinator.onTextSubmitted("失败")
        runCurrent()
        coordinator.onTextSubmitted("播报失败")
        runCurrent()
        coordinator.onTtsUpdate(tts.version, TtsProgress(error = "测试播报错误"))
        coordinator.queueAcknowledgement("好的")
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        val message = ConversationRepository.snapshot.value.content.messages.first { it.isAssistant }
        coordinator.replay(message.id)
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(0, pauses)
    }

    @Test fun singleAnswerWaitsForQueuedAcknowledgementWithoutCountingItAsAnotherAnswer() = runTest {
        val tts = FakeTts()
        var pauses = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            answerMode = { AnswerMode.SINGLE }, onSingleAnswerFinished = { pauses++ }) { _, _ -> flow {
            emit(ResponseProgress("回答", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("问题")
        runCurrent()
        coordinator.queueAcknowledgement("我退下了")
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(0, pauses)
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(1, pauses)
        coordinator.queueAcknowledgement("好的")
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(1, pauses)
    }

    @Test fun intentPromptReachesModelForSpeechAndTypedTextAndKeepsOriginalHistory() = runTest {
        val requests = mutableListOf<String>()
        val commands = mutableListOf<AsrCommand>()
        val tts = FakeTts()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            onAsrCommand = { commands += it }, replyInstruction = { "请简短回答" }) { text, _ -> flow {
            requests += text
            emit(ResponseProgress("模型生成的回答", "response_${requests.size}"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        val airConditioner = AsrIntentPrompt.configs.first()
        val seat = AsrIntentPrompt.configs[1]
        val voiceText = "豆包，${airConditioner.keywords.first()}"
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = voiceText)))
        runCurrent()
        assertTrue(requests.isEmpty())
        coordinator.onAsrResult(speech(1, voiceText))
        runCurrent()
        val typedText = seat.keywords.first()
        coordinator.onTextSubmitted(typedText)
        runCurrent()
        coordinator.onTextSubmitted("今天天气如何")
        runCurrent()
        assertEquals(listOf("$voiceText\n请简短回答\n${airConditioner.prompt}",
            "$typedText\n请简短回答\n${seat.prompt}", "今天天气如何\n请简短回答"), requests)
        assertEquals(listOf(voiceText, typedText, "今天天气如何"),
            ConversationRepository.snapshot.value.content.messages.filter { it.role == ConversationMessage.USER }.map { it.text })
        assertEquals(List(3) { "模型生成的回答" }, tts.chunks)
        coordinator.onTextSubmitted("豆包，关闭麦克风，${airConditioner.keywords.first()}")
        runCurrent()
        assertEquals(listOf(AsrCommand.CLOSE_MICROPHONE), commands)
        assertEquals(3, requests.size)
    }

    @Test fun replyStyleIsAddedOnlyToAiRequestAfterRulesAndCanChangeOnEveryTurn() = runTest {
        var style = ReplyStyle.SIMPLE
        var instructionReads = 0
        val requests = mutableListOf<String>()
        val saved = mutableListOf<ConversationMessage>()
        val commands = mutableListOf<AsrCommand>()
        val coordinator = ConversationCoordinator(backgroundScope, getString,
            saveMessage = { saved += it }, onAsrCommand = { commands += it },
            replyInstruction = { instructionReads++; style.instruction }) { text, _ -> flow {
                requests += text
                emit(ResponseProgress("回答", "response_${requests.size}"))
            } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        for ((index, option) in listOf(ReplyStyle.SIMPLE, ReplyStyle.MINIMAL, ReplyStyle.DETAILED).withIndex()) {
            style = option
            coordinator.onAsrResult(speech(index + 1L, "原始问题$index"))
            runCurrent()
            assertEquals("原始问题$index\n${option.instruction}", requests.last())
            assertEquals("原始问题$index", saved.last { it.role == ConversationMessage.USER }.text)
            assertEquals("原始问题$index", ConversationRepository.snapshot.value.content.messages.last { !it.isAssistant }.text)
        }
        assertEquals(3, saved.count { it.role == ConversationMessage.USER })
        coordinator.onAsrResult(speech(4, "闭嘴，豆包"))
        coordinator.onAsrResult(speech(5, "退下豆包"))
        coordinator.onAsrResult(speech(6, "关闭麦克风豆包"))
        runCurrent()
        assertEquals(listOf(AsrCommand.STOP_REPLY, AsrCommand.RETURN_HOME, AsrCommand.CLOSE_MICROPHONE), commands)
        assertEquals(3, requests.size)
        assertEquals(3, instructionReads)
        assertEquals("关闭麦克风豆包", saved.last().text)
    }

    @Test fun typedTextWhileInputIsClosedUsesRulesContextAndTtsAndInterruptsPreviousReply() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val saved = linkedMapOf<String, ConversationMessage>()
        val commands = mutableListOf<AsrCommand>()
        val tts = FakeTts()
        var cancelled = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            saveMessage = { saved[it.id] = it }, assistantName = { "小白" },
            lastResponseId = { saved.values.lastOrNull { it.previousResponseId != null }?.previousResponseId },
            onAsrCommand = { commands += it }, replyInstruction = { ReplyStyle.SIMPLE.instruction }) { text, id -> flow {
            requests += text to id
            if (text.startsWith("旧问题")) {
                try { emit(ResponseProgress("旧回复")); awaitCancellation() } finally { cancelled++ }
            } else emit(ResponseProgress("新回复", "response_new"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true, listening = false)
        coordinator.onAsrResult(speech(1, "录音不应生效"))
        coordinator.onTextSubmitted("   ")
        coordinator.onTextSubmitted("旧问题")
        runCurrent()
        coordinator.onTextSubmitted("新问题")
        runCurrent()
        assertEquals(1, cancelled)
        assertEquals(2, requests.size)
        assertEquals(listOf("旧回复", "新回复"), tts.chunks)
        assertEquals(1, tts.finished)
        coordinator.onTextSubmitted("继续")
        runCurrent()
        assertEquals("response_new", requests.last().second)
        coordinator.onTextSubmitted("关闭麦克风，小白")
        assertEquals(listOf(AsrCommand.CLOSE_MICROPHONE), commands)
        assertTrue(coordinator.hasActiveReply) // 关麦指令本身不取消输出。
        assertEquals(listOf("旧问题", "新问题", "继续", "关闭麦克风，小白"),
            saved.values.filter { it.role == ConversationMessage.USER }.map { it.text })
        val savedCount = saved.size
        coordinator.onTextSubmitted("字".repeat(8193))
        assertEquals(savedCount, saved.size)
        assertEquals("localized:${R.string.chat_question_too_long}", ConversationRepository.snapshot.value.error)
    }

    @Test fun directAcknowledgementSpeaksExactlySavesHistoryAndNeverRequestsAi() = runTest {
        val tts = FakeTts()
        val saved = mutableListOf<ConversationMessage>()
        var requests = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            saveMessage = { saved += it }) { _, _ -> requests++; flow { } }
        coordinator.startSession()
        coordinator.setEnabled(false)
        for (text in listOf("好的", "我退下了")) {
            coordinator.queueAcknowledgement(text)
            assertTrue(coordinator.hasActiveReply)
            assertEquals(text, tts.chunks.last())
            assertEquals(text, saved.last().text)
            assertEquals(ConversationPhase.SPEAKING, ConversationRepository.snapshot.value.content.phase)
            coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
            assertFalse(coordinator.hasActiveReply)
        }
        runCurrent()
        assertEquals(0, requests)
        assertNull(lastReplyId())
        assertEquals(listOf("好的", "我退下了"), saved.map { it.text })
        assertTrue(saved.all { it.role == ConversationMessage.SYSTEM && it.previousResponseId == null })
    }

    @Test fun queuedAcknowledgementWaitsForCurrentReplyAndErrorOrStopCleansItUp() = runTest {
        val tts = FakeTts()
        var finished = 0
        var requests = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            onReplyFinished = { finished++ }) { _, _ -> requests++; flow {
            emit(ResponseProgress("当前回答", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        val currentVersion = tts.version
        val cancellations = tts.cancelled
        coordinator.queueAcknowledgement("我退下了")
        coordinator.setEnabled(true, listening = false)
        assertEquals(listOf("当前回答"), tts.chunks)
        assertEquals(cancellations, tts.cancelled)
        val finishesBefore = finished
        coordinator.onTtsUpdate(currentVersion, TtsProgress(completed = true))
        assertEquals(listOf("当前回答", "我退下了"), tts.chunks)
        assertTrue(coordinator.hasActiveReply)
        assertEquals(finishesBefore, finished)
        coordinator.onTtsUpdate(tts.version, TtsProgress(error = "模拟播报失败"))
        assertFalse(coordinator.hasActiveReply)
        assertEquals(finishesBefore + 1, finished)
        assertEquals("response_1", lastReplyId())
        assertEquals(1, requests)
        coordinator.queueAcknowledgement("好的")
        val oldVersion = tts.version
        coordinator.queueAcknowledgement("稍后再试")
        coordinator.stopSession()
        coordinator.onTtsUpdate(oldVersion, TtsProgress(completed = true))
        assertFalse(coordinator.hasActiveReply)
        assertFalse(tts.chunks.contains("稍后再试"))
    }

    @Test fun disablingInputKeepsThinkingStreamingAndPlaybackButDisablingChatCancelsThem() = runTest {
        val tts = FakeTts()
        val updates = MutableSharedFlow<ResponseProgress>()
        var finished = 0
        var idle = 0
        val questions = mutableListOf<String>()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            onIdle = { idle++ }, onReplyFinished = { finished++ }) { text, _ ->
            questions += text
            updates.take(2)
        }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(7, "讲一个故事"))
        runCurrent()
        val cancellations = tts.cancelled
        coordinator.setEnabled(true, listening = false)
        coordinator.onAsrResult(speech(8, "关闭后不能继续识别"))
        advanceTimeBy(140_000)
        runCurrent()
        assertTrue(coordinator.hasActiveReply)
        assertEquals(ConversationPhase.THINKING, ConversationRepository.snapshot.value.content.phase)
        assertEquals(0, idle)
        updates.emit(ResponseProgress("从前"))
        updates.emit(ResponseProgress("从前有座山", "response_1"))
        runCurrent()
        assertEquals(listOf("从前", "有座山"), tts.chunks)
        assertEquals(cancellations, tts.cancelled)
        assertEquals("response_1", lastReplyId())
        val beforeCompletion = finished
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertFalse(coordinator.hasActiveReply)
        assertEquals(beforeCompletion + 1, finished)
        assertEquals(ConversationPhase.READY, ConversationRepository.snapshot.value.content.phase)
        advanceTimeBy(140_000)
        runCurrent()
        assertEquals(0, idle)
        coordinator.setEnabled(true, listening = true)
        coordinator.onAsrResult(speech(1, "重新开麦后的第一句话"))
        runCurrent()
        assertEquals(listOf("讲一个故事", "重新开麦后的第一句话"), questions)
        coordinator.setEnabled(true, listening = false)
        val beforePause = tts.cancelled
        coordinator.setEnabled(false)
        runCurrent()
        assertEquals(beforePause + 1, tts.cancelled)
        assertFalse(coordinator.hasActiveReply)
        assertEquals(0, updates.subscriptionCount.value)
    }

    @Test fun reopeningInputDuringPlaybackDoesNotCancelTheReplyOrResetContext() = runTest {
        val tts = FakeTts()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts) { _, _ -> flow {
            emit(ResponseProgress("还在播放的回复", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        val version = tts.version
        val cancellations = tts.cancelled
        coordinator.setEnabled(true, listening = false)
        coordinator.setEnabled(true, listening = true)
        assertEquals(cancellations, tts.cancelled)
        assertEquals(version, tts.version)
        assertEquals("response_1", lastReplyId())
        assertTrue(coordinator.hasActiveReply)
        coordinator.onTtsUpdate(version, TtsProgress(completed = true))
        assertFalse(coordinator.hasActiveReply)
    }

    @Test fun silenceCountsFromLatestSpeechAndRestartsOnlyAfterReplyFinishes() = runTest {
        val tts = FakeTts()
        var idle = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts, onIdle = { idle++ }) { _, _ -> flow {
            emit(ResponseProgress("长回复", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        runCurrent()
        advanceTimeBy(60_000)
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "我正在说话")))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, idle)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        advanceTimeBy(140_000) // 播报超过 70 秒仍不能判为无人说话。
        runCurrent()
        assertEquals(0, idle)
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        runCurrent()
        advanceTimeBy(69_999)
        runCurrent()
        assertEquals(0, idle)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, idle)
    }

    @Test fun confirmedAsrCommandsSaveUserSentenceOnceButAreNotSentToModel() = runTest {
        var requests = 0
        var saves = 0
        val commands = mutableListOf<AsrCommand>()
        var name = "小白"
        val coordinator = ConversationCoordinator(backgroundScope, getString, saveMessage = { saves++ },
            assistantName = { name }, onAsrCommand = { commands += it }) { _, _ ->
            requests++
            flow<ResponseProgress> {}
        }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "闭嘴，小白")))
        assertTrue(commands.isEmpty())
        listOf("闭嘴，小白。", "小白，退下吧", "关闭麦克风，小白", "小白 关闭麦克风")
            .forEachIndexed { index, text -> coordinator.onAsrResult(speech(index.toLong() + 1, text)); runCurrent() }
        coordinator.onAsrResult(speech(4, "小白关闭麦克风"))
        assertEquals(listOf(AsrCommand.STOP_REPLY, AsrCommand.RETURN_HOME,
            AsrCommand.CLOSE_MICROPHONE, AsrCommand.CLOSE_MICROPHONE), commands)
        name = "豆包"
        coordinator.onAsrResult(speech(5, "豆包，闭嘴"))
        assertEquals(AsrCommand.STOP_REPLY, commands.last())
        assertEquals(0, requests)
        assertEquals(5, saves)
        assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER && it.previousResponseId == null })
    }

    @Test fun wakeAndResumePhrasesAreNotAsrControlCommands() = runTest {
        val questions = mutableListOf<String>()
        val commands = mutableListOf<AsrCommand>()
        val coordinator = ConversationCoordinator(backgroundScope, getString,
            onAsrCommand = { commands += it }) { text, _ -> questions += text; flow { } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        val texts = listOf("你好，豆包", "可以说话了豆包", "说话豆包", "豆包说话", "闭嘴", "退下", "关闭麦克风")
        texts.forEachIndexed { index, text -> coordinator.onAsrResult(speech(index.toLong() + 1, text)); runCurrent() }
        assertTrue(commands.isEmpty())
        assertEquals(texts, questions)
    }

    @Test fun asrMicrophoneCommandDoesNotInterruptAnExistingReplyBeforeItIsExecuted() = runTest {
        val tts = FakeTts()
        val commands = mutableListOf<AsrCommand>()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            onAsrCommand = { commands += it }) { _, _ -> flow {
            emit(ResponseProgress("继续播报", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        val cancellations = tts.cancelled
        coordinator.onAsrResult(speech(2, "关闭麦克风，豆包"))
        assertEquals(listOf(AsrCommand.CLOSE_MICROPHONE), commands)
        assertEquals(cancellations, tts.cancelled)
        assertTrue(coordinator.hasActiveReply)
        assertEquals("response_1", lastReplyId())
    }

    @Test fun microphonePartialsAndFinalKeepThinkingAndPlaybackWithoutSendingAnExtraQuestion() = runTest {
        for (name in listOf("豆包", "小白")) for (nameFirst in listOf(false, true))
            for (alreadySpeaking in listOf(false, true)) {
                ConversationRepository.reset()
                val tts = FakeTts()
                val updates = MutableSharedFlow<ResponseProgress>()
                val saved = mutableListOf<ConversationMessage>()
                val commands = mutableListOf<AsrCommand>()
                var requests = 0
                lateinit var coordinator: ConversationCoordinator
                coordinator = ConversationCoordinator(backgroundScope, getString, tts,
                    assistantName = { name }, saveMessage = { saved += it }, onAsrCommand = {
                        commands += it
                        coordinator.setEnabled(true, listening = false)
                    }) { _, _ -> requests++; updates.take(2) }
                coordinator.startSession()
                coordinator.setEnabled(true)
                coordinator.onAsrResult(speech(1, "讲一个故事"))
                runCurrent()
                if (alreadySpeaking) updates.emit(ResponseProgress("当前"))
                runCurrent()
                val phase = if (alreadySpeaking) ConversationPhase.SPEAKING else ConversationPhase.THINKING
                val version = tts.version
                val cancellations = tts.cancelled
                val text = if (nameFirst) "${name}关闭麦克风" else "关闭麦克风$name"
                for (length in 1..text.length) {
                    coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = text.take(length))))
                    assertEquals(cancellations, tts.cancelled)
                    assertEquals(phase, ConversationRepository.snapshot.value.content.phase)
                    assertEquals(1, updates.subscriptionCount.value)
                    assertTrue(commands.isEmpty())
                }
                coordinator.onAsrResult(speech(2, text))
                assertEquals(listOf(AsrCommand.CLOSE_MICROPHONE), commands)
                assertEquals(cancellations, tts.cancelled)
                assertEquals(version, tts.version)
                assertEquals(1, requests)
                assertEquals(1, updates.subscriptionCount.value)
                assertEquals(listOf("讲一个故事", text), saved.filter { it.role == ConversationMessage.USER }.map { it.text })
                assertTrue(saved.none { it.isSystem })
                if (!alreadySpeaking) updates.emit(ResponseProgress("当前"))
                updates.emit(ResponseProgress("当前回答", "response_1"))
                runCurrent()
                assertEquals(listOf("当前", "回答"), tts.chunks)
                assertEquals(cancellations, tts.cancelled)
                assertEquals("response_1", saved.last().previousResponseId)
                // TTS 等首段可播报文字到达后才启动，思考阶段尚无播放版本。
                coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
                assertFalse(coordinator.hasActiveReply)
                coordinator.stopSession()
            }
    }

    @Test fun microphoneCandidateRevisedToOrdinarySpeechStillInterruptsPlayback() = runTest {
        val tts = FakeTts()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts) { _, _ -> flow {
            emit(ResponseProgress("当前回答", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "讲一个故事"))
        runCurrent()
        val cancellations = tts.cancelled
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "关闭")))
        assertEquals(cancellations, tts.cancelled)
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "关闭空调豆包")))
        assertEquals(cancellations + 1, tts.cancelled)
        assertFalse(coordinator.hasActiveReply)
    }

    @Test fun finalMicrophonePhraseWithoutAssistantNameStillInterruptsAndRequestsAi() = runTest {
        val tts = FakeTts()
        val questions = mutableListOf<String>()
        val commands = mutableListOf<AsrCommand>()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            onAsrCommand = { commands += it }) { text, _ ->
            questions += text
            flow { emit(ResponseProgress("当前回答", "response_1")) }
        }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "讲一个故事"))
        runCurrent()
        val cancellations = tts.cancelled
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "关闭麦克风")))
        assertEquals(cancellations, tts.cancelled)
        coordinator.onAsrResult(speech(2, "关闭麦克风"))
        assertTrue(tts.cancelled > cancellations)
        runCurrent()
        assertTrue(commands.isEmpty())
        assertEquals(listOf("讲一个故事", "关闭麦克风"), questions)
    }

    @Test fun requestRotationDoesNotResetSilenceDeadlineAndManualPauseCancelsIt() = runTest {
        var idle = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, onIdle = { idle++ }) { _, _ -> flow<ResponseProgress> {} }
        coordinator.startSession()
        coordinator.setEnabled(true)
        runCurrent()
        advanceTimeBy(60_000)
        coordinator.onAsrResult(AsrSnapshot(status = AsrStatus.REQUEST_ENDED))
        coordinator.onAsrResult(AsrSnapshot(status = AsrStatus.STARTING))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, idle)
        coordinator.restartListening()
        runCurrent()
        coordinator.setEnabled(false)
        advanceTimeBy(140_000)
        runCurrent()
        assertEquals(1, idle)
    }

    @Test fun waitingForModelAlsoSuspendsSilenceTimeoutAcrossAsrRequests() = runTest {
        var idle = 0
        var cancelled = false
        val coordinator = ConversationCoordinator(backgroundScope, getString, onIdle = { idle++ }) { _, _ -> flow<ResponseProgress> {
            try { awaitCancellation() } finally { cancelled = true }
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        coordinator.onAsrResult(AsrSnapshot(status = AsrStatus.REQUEST_ENDED))
        coordinator.onAsrResult(AsrSnapshot(status = AsrStatus.RECEIVE_TIMEOUT))
        advanceTimeBy(140_000)
        runCurrent()
        assertEquals(0, idle)
        assertFalse(cancelled)
        coordinator.stopSession()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test fun accumulatedTextFeedsOnlyNewCharactersAndInterruptionFlushesHistory() = runTest {
        val tts = FakeTts()
        val saved = mutableListOf<ConversationMessage>()
        val updates = MutableSharedFlow<ResponseProgress>()
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString, speech = tts,
            saveMessage = { saved += it }, speed = { 2 }) { _, _ -> updates }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "讲个故事"))
        runCurrent()
        updates.emit(ResponseProgress("从前"))
        runCurrent()
        updates.emit(ResponseProgress("从前有座山"))
        runCurrent()
        assertEquals(listOf("从前", "有座山"), tts.chunks)
        assertEquals(2, tts.speed)
        val oldVersion = tts.version
        val cancellations = tts.cancelled
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "换个话题")))
        runCurrent()
        assertEquals(cancellations + 1, tts.cancelled)
        assertEquals(0, updates.subscriptionCount.value)
        val finalRecords = saved.associateBy { it.id }.values.toList()
        assertEquals(listOf("讲个故事", "从前有座山"), finalRecords.map { it.text })
        assertEquals(listOf(false, true), finalRecords.map { it.isAssistant })
        assertTrue(finalRecords.all { it.timeMillis > 0 && it.id.isNotBlank() })
        coordinator.onTtsUpdate(oldVersion, TtsProgress(error = "旧请求失败"))
        assertNull(ConversationRepository.snapshot.value.ttsError)
    }

    @Test fun audioCanBeInterruptedAfterLanguageModelAlreadyCompleted() = runTest {
        val tts = FakeTts()
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString, speech = tts) { _, _ -> flow {
            emit(ResponseProgress("这是回答", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        assertEquals(1, tts.finished)
        assertEquals(ConversationPhase.SPEAKING, ConversationRepository.snapshot.value.content.phase)
        val cancellations = tts.cancelled
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "新问题")))
        assertEquals(cancellations + 1, tts.cancelled)
        assertEquals(ConversationPhase.LISTENING, ConversationRepository.snapshot.value.content.phase)
    }

    @Test fun playbackCompletionReturnsToReadyWithoutClearingMessagesOrModelContext() = runTest {
        val tts = FakeTts()
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString, speech = tts) { _, _ -> flow {
            emit(ResponseProgress("这是完整的回答", "response_1"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        assertEquals(ConversationPhase.SPEAKING, ConversationRepository.snapshot.value.content.phase)
        val messages = ConversationRepository.snapshot.value.content.messages
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(ConversationPhase.READY, ConversationRepository.snapshot.value.content.phase)
        assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
        assertEquals("response_1", lastReplyId())
        coordinator.onAsrResult(AsrSnapshot(status = AsrStatus.LISTENING, confirmedVersion = 1))
        assertEquals(ConversationPhase.READY, ConversationRepository.snapshot.value.content.phase)
    }

    @Test fun historyIsMergedWithoutReplacingCurrentStreamingTextOrRestoringResponseId() = runTest {
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString) { _, _ -> flow<ResponseProgress> {} }
        val topic = ChatTopic("history", timeMillis = 1)
        val old = ConversationMessage("过去的问题", false, "old", 1, topic.id)
        val partial = ConversationMessage("部分", true, "same", 2, topic.id)
        ConversationRepository.publish(com.spake.brony.doubaoassistant.data.model.ConversationSnapshot(
            ConversationContent(messages = listOf(partial.copy(text = "部分回复")))))
        coordinator.restoreHistory(topic, listOf(old, partial))
        assertEquals(listOf("过去的问题", "部分回复"), ConversationRepository.snapshot.value.content.messages.map { it.text })
        coordinator.startSession()
        assertEquals(2, ConversationRepository.snapshot.value.content.messages.size)
        assertNull(lastReplyId())
    }

    @Test fun endingTopicCancelsReplyClearsUiAndStartsNewContextWithoutMovingOldMessages() = runTest {
        val saved = mutableListOf<ConversationMessage>()
        val topics = mutableListOf<ChatTopic>()
        val requests = mutableListOf<Pair<String, String?>>()
        val tts = FakeTts()
        var cancelled = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString, speech = tts,
            saveMessage = { saved += it }, createTopic = { topics += it }) { text, id -> flow {
            requests += text to id
            try {
                emit(ResponseProgress("一段回复"))
                awaitCancellation()
            } finally { cancelled++ }
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "旧话题"))
        runCurrent()
        val oldTopic = checkNotNull(ConversationRepository.activeTopic)
        val oldTtsVersion = tts.version
        coordinator.newTopic()
        runCurrent()
        val newTopic = checkNotNull(ConversationRepository.activeTopic)
        assertNotEquals(oldTopic.id, newTopic.id)
        assertEquals(listOf(oldTopic, newTopic), topics)
        assertEquals(1, cancelled)
        assertNull(lastReplyId())
        assertTrue(ConversationRepository.snapshot.value.content.messages.isEmpty())
        assertEquals("", ConversationRepository.snapshot.value.content.liveText)
        assertTrue(saved.all { it.topicId == oldTopic.id })
        assertEquals(listOf("旧话题", "一段回复"), saved.associateBy { it.id }.values.map { it.text })
        coordinator.restoreHistory(oldTopic, saved)
        coordinator.onTtsUpdate(oldTtsVersion, TtsProgress(error = "旧播报"))
        coordinator.onAsrResult(speech(2, "结束后的旧结果"))
        assertTrue(ConversationRepository.snapshot.value.content.messages.isEmpty())
        assertNull(ConversationRepository.snapshot.value.ttsError)
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "新话题"))
        runCurrent()
        assertEquals(listOf("旧话题" to null, "新话题" to null), requests)
        assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.topicId == newTopic.id })
        assertEquals(2, topics.size)
    }

    @Test fun onlyConfirmedSpeechSubmitsOnceAndNextTurnUsesCompletedId() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val stored = linkedMapOf<String, ConversationMessage>()
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString,
            saveMessage = { stored[it.id] = it }, lastResponseId = { topicId ->
                stored.values.lastOrNull { it.topicId == topicId && it.isAssistant && it.previousResponseId != null }?.previousResponseId
            }) { text, id -> flow {
            requests += text to id
            emit(ResponseProgress("你", null))
            emit(ResponseProgress("你好", "response_${requests.size}"))
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "天气")))
        runCurrent()
        assertTrue(requests.isEmpty())
        coordinator.onAsrResult(speech(1, "天气怎么样"))
        runCurrent()
        coordinator.onAsrResult(speech(1, "天气怎么样"))
        runCurrent()
        assertEquals(listOf("天气怎么样" to null), requests)
        assertEquals(listOf(false, true), ConversationRepository.snapshot.value.content.messages.map { it.isAssistant })
        assertEquals("你好", ConversationRepository.snapshot.value.content.messages.last().text)
        coordinator.onAsrResult(speech(2, "天气怎么样"))
        runCurrent()
        assertEquals("response_1", requests.last().second)
        coordinator.stopSession()
        assertEquals("response_2", lastReplyId())
    }

    @Test fun newSpeechCancelsOldReplyAndPauseCancelsPendingRequest() = runTest {
        var cancelled = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString) { _, _ -> flow {
            try {
                emit(ResponseProgress("尚未说完"))
                awaitCancellation()
            } finally { cancelled++ }
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "第一个问题"))
        runCurrent()
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "新问题")))
        runCurrent()
        assertEquals(1, cancelled)
        assertNull(lastReplyId())
        coordinator.onAsrResult(speech(2, "新问题"))
        runCurrent()
        coordinator.setEnabled(false)
        runCurrent()
        assertEquals(2, cancelled)
        assertEquals("", ConversationRepository.snapshot.value.content.liveText)
    }

    @Test fun requestFailureAppearsInUiInsteadOfCrashingService() = runTest {
        val coordinator = ConversationCoordinator(backgroundScope, getString = getString) { _, _ -> flow<ResponseProgress> {
            throw IllegalStateException("private transport failure")
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        assertEquals(getString(R.string.chat_request_failed), ConversationRepository.snapshot.value.error)
        assertNull(lastReplyId())
    }

    @Test fun replayWhilePausedUsesOnlySavedReplyAndKeepsHistoryAndResponseId() = runTest {
        val tts = FakeTts()
        val saved = mutableListOf<ConversationMessage>()
        var requests = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            saveMessage = { saved += it }, speed = { 2 }) { _, _ -> flow<ResponseProgress> { requests++ } }
        val topic = ChatTopic("topic", timeMillis = 1)
        val messages = listOf(ConversationMessage("用户问题", false, "user", 1, topic.id),
            ConversationMessage("豆包回复", true, "reply", 2, topic.id, previousResponseId = "keep_response_id"))
        coordinator.restoreHistory(topic, messages)
        coordinator.replay("user")
        coordinator.replay("missing")
        assertTrue(tts.chunks.isEmpty())
        coordinator.replay("reply")
        assertEquals(listOf("豆包回复"), tts.chunks)
        assertEquals(2, tts.speed)
        assertEquals(1, tts.finished)
        assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
        assertEquals("keep_response_id", lastReplyId())
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(ConversationPhase.READY, ConversationRepository.snapshot.value.content.phase)
        assertTrue(saved.isEmpty())
        assertEquals(0, requests)
    }

    @Test fun replayCancelsStreamingModelAndRemainsInterruptibleByNewSpeech() = runTest {
        val tts = FakeTts()
        var cancelled = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts) { _, _ -> flow {
            try {
                emit(ResponseProgress("部分回复"))
                awaitCancellation()
            } finally { cancelled++ }
        } }
        coordinator.startSession()
        coordinator.setEnabled(true)
        coordinator.onAsrResult(speech(1, "问题"))
        runCurrent()
        val id = ConversationRepository.snapshot.value.content.messages.last().id
        val oldVersion = tts.version
        coordinator.replay(id)
        runCurrent()
        assertEquals(1, cancelled)
        assertTrue(tts.version > oldVersion)
        assertEquals(listOf("部分回复", "部分回复"), tts.chunks)
        coordinator.onTtsUpdate(oldVersion, TtsProgress(error = "旧错误"))
        assertNull(ConversationRepository.snapshot.value.ttsError)
        val cancellations = tts.cancelled
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "新问题")))
        assertEquals(cancellations + 1, tts.cancelled)
        assertEquals(ConversationPhase.LISTENING, ConversationRepository.snapshot.value.content.phase)
    }
}
