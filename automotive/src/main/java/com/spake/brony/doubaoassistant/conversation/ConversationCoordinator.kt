package com.spake.brony.doubaoassistant.conversation

import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.model.ConversationSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import com.spake.brony.doubaoassistant.data.model.TtsProgress
import com.spake.brony.doubaoassistant.data.model.AsrCommand
import com.spake.brony.doubaoassistant.speech.asr.AsrCommands
import com.spake.brony.doubaoassistant.speech.asr.AsrIntentPrompt
import com.spake.brony.doubaoassistant.data.model.AssistantName
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.speech.tts.StreamingTts
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// 只由服务主线程调用；请求属于服务 scope，页面关闭/重建不会重发问题。
internal class ConversationCoordinator(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val speech: StreamingTts? = null,
    private val saveMessage: (ConversationMessage) -> Unit = {},
    private val speed: () -> Int = { 1 },
    private val createTopic: (ChatTopic) -> Unit = {},
    private val onIdle: () -> Unit = {},
    private val assistantName: () -> String = { AssistantName.DEFAULT },
    private val onReplyFinished: () -> Unit = {},
    private val onAsrCommand: (AsrCommand) -> Unit = {},
    private val lastResponseId: suspend (String) -> String? = { null },
    private val replyInstruction: () -> String = { "" },
    private val answerMode: () -> AnswerMode = { AnswerMode.CHAT },
    private val onSingleAnswerFinished: () -> Unit = {},
    private val executeOperation: suspend (AiOperation) -> Boolean = AiOperationExecutor()::execute,
    private val responses: (String, String?) -> Flow<ResponseProgress>,
) {
    private var enabled = false
    private var listeningEnabled = false
    private var confirmedVersion = 0L
    private val pending = StringBuilder()
    private var replyJob: Job? = null
    private var requestVersion = 0L
    private var speechActive = false
    private var replaying = false
    private var responseCompleted = false
    private var pauseAfterAnswer = false
    private var replyMessage: ConversationMessage? = null
    private var replyDirty = false
    private var lastSaveNanos = 0L
    private var spokenText = ""
    private var idleJob: Job? = null
    private var pendingAcknowledgement: ConversationMessage? = null
    val hasActiveReply: Boolean get() = replyJob?.isActive == true || speechActive || pendingAcknowledgement != null

    private fun waitForSpeech() {
        idleJob?.cancel()
        if (replyJob?.isActive != true && !speechActive) {
            pendingAcknowledgement?.let { message ->
                pendingAcknowledgement = null
                val pauseAfterAcknowledgement = shouldPauseAfterAnswer()
                playText(message.text)
                pauseAfterAnswer = pauseAfterAcknowledgement
                return
            }
        }
        if (!hasActiveReply) {
            val pause = shouldPauseAfterAnswer()
            pauseAfterAnswer = false // 清除后再通知，避免服务更新状态时重复触发。
            if (pause) onSingleAnswerFinished()
            onReplyFinished()
        }
        if (!enabled || !listeningEnabled || hasActiveReply) return
        idleJob = scope.launch {
            delay(70_000)
            if (enabled && listeningEnabled && !hasActiveReply) onIdle()
        }
    }

    private fun shouldPauseAfterAnswer(): Boolean {
        val snapshot = ConversationRepository.snapshot.value
        return pauseAfterAnswer && responseCompleted && snapshot.error == null && snapshot.ttsError == null
    }

    fun startSession() {
        cancelReply()
        pending.setLength(0)
        confirmedVersion = 0
        enabled = false
        listeningEnabled = false
        val previous = ConversationRepository.snapshot.value
        ConversationRepository.publish(previous.copy(error = null, ttsError = null,
            content = previous.content.copy(phase = ConversationPhase.READY, liveText = "")))
    }

    fun setEnabled(value: Boolean, listening: Boolean = value) {
        val inputChanged = listeningEnabled != listening
        listeningEnabled = listening
        if (enabled != value) {
            enabled = value
            restartListening()
        } else if (inputChanged) {
            // 只切换输入，不取消当前 AI 请求、TTS 或模型上下文。
            pending.setLength(0)
            confirmedVersion = 0
            val previous = ConversationRepository.snapshot.value
            ConversationRepository.publish(previous.copy(content = previous.content.copy(
                liveText = "", phase = if (hasActiveReply) previous.content.phase else ConversationPhase.READY)))
            waitForSpeech()
        }
    }

    fun restartListening() {
        cancelReply()
        pending.setLength(0)
        val previous = ConversationRepository.snapshot.value
        ConversationRepository.publish(previous.copy(error = null, ttsError = null,
            content = previous.content.copy(phase = ConversationPhase.READY, liveText = "")))
        waitForSpeech()
    }

    fun onAsrResult(snapshot: AsrSnapshot) {
        if (!enabled || !listeningEnabled) return
        if (snapshot.status == AsrStatus.ERROR) { restartListening(); return }
        if (snapshot.confirmedVersion > confirmedVersion) {
            confirmedVersion = snapshot.confirmedVersion
            pending.append(snapshot.confirmedText)
        }
        val draft = snapshot.conversation.liveText
        if (pending.length + draft.length > 8192) {
            restartListening()
            val previous = ConversationRepository.snapshot.value
            ConversationRepository.publish(previous.copy(error = getString(R.string.chat_question_too_long)))
            return
        }
        // 确定句与手动输入共用指令判断，必须先于打断，避免关麦取消当前回答。
        if (draft.isEmpty() && pending.isNotEmpty()) {
            val text = pending.toString()
            pending.setLength(0)
            onTextSubmitted(text)
            return
        }
        if (draft.isNotEmpty() || pending.isNotEmpty()) {
            val deferInterruption = draft.isNotEmpty() &&
                AsrCommands.isMicrophoneCommandCandidate(pending.toString() + draft, assistantName())
            if (!deferInterruption && (replyJob?.isActive == true || speechActive)) cancelReply()
            waitForSpeech()
            val previous = ConversationRepository.snapshot.value
            ConversationRepository.publish(previous.copy(error = null,
                content = previous.content.copy(
                    phase = if (deferInterruption && hasActiveReply) previous.content.phase else ConversationPhase.LISTENING,
                    liveText = pending.toString() + draft)))
        }
    }

    // ASR definite（1.5 秒 VAD 判停）和键盘发送的共同入口，不将 partial 当作问题。
    fun onTextSubmitted(input: String) {
        if (!enabled) return
        val text = input.trim()
        if (text.isEmpty()) return
        pending.setLength(0)
        if (text.length > 8192) {
            val previous = ConversationRepository.snapshot.value
            ConversationRepository.publish(previous.copy(error = getString(R.string.chat_question_too_long)))
            return
        }
        AsrCommands.match(text, assistantName())?.let { rule ->
            recordUserMessage(text)
            onAsrCommand(rule.command)
            return
        }
        submit(text)
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        cancelReply()
        ensureTopic()
        val version = requestVersion
        val topicId = checkNotNull(ConversationRepository.activeTopic).id
        recordUserMessage(text)
        // 仅模型请求追加提示词，本地消息和话题标题保留用户原文。
        val requestText = listOf(text, replyInstruction(), AsrIntentPrompt.instructionFor(text))
            .filter { it.isNotBlank() }.joinToString("\n")
        val previous = ConversationRepository.snapshot.value
        ConversationRepository.publish(previous.copy(error = null, ttsError = null, content = previous.content.copy(
            phase = ConversationPhase.THINKING,
            liveText = "",
        )))
        speechActive = speech != null
        responseCompleted = false
        // 本轮使用提问时的设置；修改设置不打断正在进行的回答。
        pauseAfterAnswer = answerMode() == AnswerMode.SINGLE
        spokenText = ""
        val replySpeed = speed()
        replyJob = scope.launch(start = CoroutineStart.LAZY) {
            var responseText = ""
            var operationCandidate: Boolean? = null
            var operationReply = ""
            var responseTerminated = false
            var speechStarted = false
            try {
                val previousId = try { lastResponseId(topicId) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (version == requestVersion) {
                        speech?.cancel()
                        speechActive = false
                        onHistoryError()
                        val current = ConversationRepository.snapshot.value
                        ConversationRepository.publish(current.copy(content = current.content.copy(phase = ConversationPhase.READY)))
                    }
                    return@launch // 不能在数据库读取失败时擅自丢上下文发起付费请求。
                }
                if (!enabled || version != requestVersion || ConversationRepository.activeTopic?.id != topicId) return@launch
                responses(requestText, previousId).collect { update ->
                    if (!enabled || version != requestVersion || responseTerminated) return@collect
                    if (update.text.isNotEmpty()) responseText = update.text
                    if (operationCandidate == null) operationCandidate = AiOperation.isJsonCandidate(responseText)
                    if (update.error != null) responseTerminated = true
                    if (update.completedId != null && update.error == null) {
                        responseTerminated = true
                        if (operationCandidate == true) {
                            val operation = AiOperation.parse(responseText)
                            operationReply = if (operation == null) getString(R.string.voice_operation_invalid_reply) else {
                                val succeeded = if (operation.intent == "unknown") false else try {
                                    executeOperation(operation)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    false
                                }
                                if (succeeded) operation.successReply else operation.failureReply
                            }
                            if (!enabled || version != requestVersion) return@collect
                        } else if (operationCandidate == null) operationCandidate = false
                    }
                    val displayText = when {
                        operationCandidate == true -> operationReply
                        operationCandidate == null -> ""
                        update.completedId != null -> responseText
                        else -> update.text
                    }
                    val current = ConversationRepository.snapshot.value
                    val messages = current.content.messages.toMutableList()
                    if (displayText.isNotEmpty()) {
                        val message = replyMessage?.copy(text = displayText) ?: newMessage(displayText,
                            if (operationCandidate == true) ConversationMessage.SYSTEM else ConversationMessage.ASSISTANT)
                        replyMessage = message
                        replyDirty = true
                        val replyIndex = messages.indexOfLast { it.id == message.id }
                        if (replyIndex < 0) {
                            messages += message
                            if (messages.size > 40) messages.removeAt(0)
                        } else messages[replyIndex] = message
                    }
                    var ttsError = current.ttsError
                    if (speechActive && update.error == null && displayText.isNotEmpty()) {
                        if (displayText.startsWith(spokenText)) {
                            if (!speechStarted) {
                                speech?.start(version, replySpeed)
                                speechStarted = true
                            }
                            speech?.append(displayText.substring(spokenText.length))
                            spokenText = displayText
                        } else {
                            speech?.cancel()
                            speechActive = false
                            ttsError = getString(R.string.chat_reply_revised)
                        }
                    }
                    if (update.completedId != null && update.error == null) {
                        responseCompleted = true
                        replyMessage?.let { message ->
                            val completed = message.copy(previousResponseId =
                                if (operationCandidate == true) null else update.completedId)
                            replyMessage = completed
                            replyDirty = true
                            val index = messages.indexOfLast { it.id == completed.id }
                            if (index >= 0) messages[index] = completed
                        }
                        if (speechActive && speechStarted) speech?.finish()
                        else speechActive = false
                    }
                    if (update.error != null) {
                        speech?.cancel()
                        speechActive = false
                    }
                    saveReply(force = update.completedId != null || update.error != null)
                    ConversationRepository.publish(current.copy(error = update.error, ttsError = ttsError, content = current.content.copy(
                        messages = messages,
                        phase = when {
                            update.error != null -> ConversationPhase.ERROR
                            update.completedId != null && !speechActive -> ConversationPhase.READY
                            displayText.isEmpty() -> ConversationPhase.THINKING
                            else -> ConversationPhase.SPEAKING
                        },
                    )))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (enabled && version == requestVersion) {
                    speech?.cancel()
                    speechActive = false
                    saveReply(force = true)
                    val current = ConversationRepository.snapshot.value
                    ConversationRepository.publish(current.copy(error = getString(R.string.chat_request_failed),
                        content = current.content.copy(phase = ConversationPhase.ERROR)))
                }
            } finally {
                if (version == requestVersion) {
                    replyJob = null
                    waitForSpeech()
                }
            }
        }
        replyJob?.start()
    }

    private fun cancelReply() {
        pauseAfterAnswer = false
        pendingAcknowledgement = null
        idleJob?.cancel()
        idleJob = null
        ++requestVersion
        replyJob?.cancel()
        replyJob = null
        speech?.cancel()
        speechActive = false
        replaying = false
        saveReply(force = true)
        replyMessage = null
        replyDirty = false
        lastSaveNanos = 0L
    }

    fun replay(messageId: String) {
        val current = ConversationRepository.snapshot.value
        val message = current.content.messages.firstOrNull {
            it.id == messageId && it.isAssistant && it.topicId == ConversationRepository.activeTopic?.id
        } ?: return
        if (message.text.isBlank() || speech == null) return
        if (message.text.length > 32768) {
            ConversationRepository.publish(current.copy(ttsError = getString(R.string.tts_text_too_long)))
            return
        }
        playText(message.text)
    }

    fun queueAcknowledgement(text: String) {
        ensureTopic()
        val message = newMessage(text, ConversationMessage.SYSTEM)
        saveMessage(message)
        val current = ConversationRepository.snapshot.value
        ConversationRepository.publish(current.copy(error = null, ttsError = null, content = current.content.copy(
            messages = (current.content.messages + message).takeLast(40))))
        if (speech == null) return
        if (replyJob?.isActive == true || speechActive) pendingAcknowledgement = message
        else playText(text)
    }

    private fun playText(text: String) {
        val current = ConversationRepository.snapshot.value
        cancelReply()
        replaying = true
        speechActive = true
        responseCompleted = true
        ConversationRepository.publish(current.copy(ttsError = null, content = current.content.copy(phase = ConversationPhase.SPEAKING)))
        speech?.start(requestVersion, speed())
        speech?.append(text)
        speech?.finish()
    }

    fun onTtsUpdate(version: Long, update: TtsProgress) {
        if ((!enabled && !replaying) || version != requestVersion || !speechActive) return
        if (update.completed || update.error != null) {
            speechActive = false
            replaying = false
            val current = ConversationRepository.snapshot.value
            ConversationRepository.publish(current.copy(ttsError = update.error,
                content = if (responseCompleted && current.error == null)
                    current.content.copy(phase = ConversationPhase.READY) else current.content))
            waitForSpeech()
        }
    }

    fun restoreHistory(topic: ChatTopic, history: List<ConversationMessage>) {
        val active = ConversationRepository.activeTopic
        if (active != null && active.id != topic.id) return
        ConversationRepository.setTopic(topic)
        val current = ConversationRepository.snapshot.value
        val ids = current.content.messages.map { it.id }.toSet()
        val messages = (history.filter { it.topicId == topic.id && it.id !in ids } + current.content.messages).takeLast(40)
        ConversationRepository.publish(current.copy(content = current.content.copy(messages = messages)))
    }

    fun newTopic() {
        enabled = false
        listeningEnabled = false
        cancelReply() // 先把旧回复保存到旧话题，再切换 ID。
        pending.setLength(0)
        confirmedVersion = 0
        val previousTime = ConversationRepository.activeTopic?.timeMillis ?: 0
        val topic = ChatTopic(timeMillis = maxOf(System.currentTimeMillis(), previousTime + 1))
        ConversationRepository.setTopic(topic)
        ConversationRepository.publish(ConversationSnapshot())
        createTopic(topic)
    }

    fun onHistoryError() {
        val current = ConversationRepository.snapshot.value
        ConversationRepository.publish(current.copy(historyError = getString(R.string.chat_history_failed)))
    }

    private fun ensureTopic() {
        if (ConversationRepository.activeTopic != null) return
        val topic = ChatTopic()
        ConversationRepository.setTopic(topic)
        createTopic(topic)
    }

    private fun recordUserMessage(text: String) {
        ensureTopic()
        val message = newMessage(text, ConversationMessage.USER)
        saveMessage(message)
        val current = ConversationRepository.snapshot.value
        ConversationRepository.publish(current.copy(content = current.content.copy(
            messages = (current.content.messages + message).takeLast(40), liveText = "")))
    }

    private fun newMessage(text: String, role: Int) = ConversationMessage(
        text, role, UUID.randomUUID().toString(), System.currentTimeMillis().coerceAtLeast(1),
        topicId = checkNotNull(ConversationRepository.activeTopic).id)

    private fun saveReply(force: Boolean) {
        val message = replyMessage ?: return
        if (message.text.isBlank()) return
        val now = System.nanoTime()
        if (replyDirty && (force || lastSaveNanos == 0L || now - lastSaveNanos >= 500_000_000)) {
            saveMessage(message)
            replyDirty = false
            lastSaveNanos = now
        }
    }

    fun stopSession() {
        enabled = false
        listeningEnabled = false
        restartListening()
    }
}
