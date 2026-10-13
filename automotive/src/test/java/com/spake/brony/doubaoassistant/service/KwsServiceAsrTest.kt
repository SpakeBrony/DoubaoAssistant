package com.spake.brony.doubaoassistant.service

import com.spake.brony.doubaoassistant.data.model.AsrCommand

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.SoundPool
import android.os.Process
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import com.spake.brony.doubaoassistant.data.repository.TestCredentialKeys
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.conversation.ConversationCoordinator
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import com.spake.brony.doubaoassistant.data.model.TtsProgress
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.repository.AsrRepository
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.data.repository.ChatHistoryRepository
import com.spake.brony.doubaoassistant.data.local.ChatHistoryDatabase
import com.spake.brony.doubaoassistant.data.model.KwsCommand
import com.spake.brony.doubaoassistant.speech.asr.AsrController
import com.spake.brony.doubaoassistant.speech.audio.VoiceCuePlayer
import com.spake.brony.doubaoassistant.speech.audio.MicrophoneRecorder
import com.spake.brony.doubaoassistant.speech.tts.StreamingTts
import com.spake.brony.doubaoassistant.ui.overlay.ChatOverlay
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KwsServiceAsrTest {
    @org.junit.Before fun configureCredentials() {
        TestCredentialKeys.install()
        check(CredentialStore(org.robolectric.RuntimeEnvironment.getApplication()).save(
            ServiceCredentials(apiKey = "test-speech-key", arkApiKey = "test-ark-key")))
    }
    private fun lastReplyId(): String? = ConversationRepository.snapshot.value.content.messages
        .lastOrNull { it.isAssistant && it.previousResponseId != null }?.previousResponseId

    @Test fun singleAnswerStopsAsrAtQuestionKeepsReplyAndKwsThenWaitsForNextWake() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val stop = service.javaClass.getDeclaredMethod("stopSingleAnswerRecognition").apply { isAccessible = true }
        val recognition = service.javaClass.getDeclaredMethod("onRecognition", AsrSnapshot::class.java).apply { isAccessible = true }
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val updates = MutableSharedFlow<ResponseProgress>()
        val questions = mutableListOf<String>()
        val chunks = mutableListOf<String>()
        var cancellations = 0
        var version = 0L
        val tts = object : StreamingTts {
            override fun start(value: Long, speed: Int) { version = value }
            override fun append(delta: String) { chunks += delta }
            override fun finish() = Unit
            override fun cancel() { cancellations++ }
        }
        val coordinator = ConversationCoordinator(backgroundScope, service::getString, tts,
            answerMode = { com.spake.brony.doubaoassistant.data.model.AnswerMode.SINGLE },
            onSingleQuestionRecognized = { stop.invoke(service) }, judgeOperation = { true }) { text, _ ->
            questions += text
            updates.take(2)
        }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(coordinator))
        try {
            field("activeRun").setLong(service, 1L)
            field("historyReady").setBoolean(service, true)
            (field("asr").get(service) as AsrController).start(true)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            recognition.invoke(service, AsrSnapshot(conversation = ConversationContent(liveText = "问题还没说完")))
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            recognition.invoke(service, AsrSnapshot(status = AsrStatus.REQUEST_ENDED,
                confirmedVersion = 1, confirmedText = "第一个问题"))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertTrue(field("singleAnswerWaiting").getBoolean(service))
            assertEquals(AsrStatus.IDLE, AsrRepository.snapshot.value.status)
            assertEquals(ListeningStatus.LISTENING, KwsRepository.snapshot.value.status)
            assertEquals(1L, field("activeRun").getLong(service))
            runCurrent()
            val before = cancellations
            coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "后续说话")))
            coordinator.onAsrResult(AsrSnapshot(confirmedVersion = 2, confirmedText = "不能追加问题"))
            KwsRepository.setUiVisible(false)
            publish.invoke(service, KwsRepository.snapshot.value)
            KwsRepository.setUiVisible(true)
            publish.invoke(service, KwsRepository.snapshot.value)
            updates.emit(ResponseProgress("完整"))
            updates.emit(ResponseProgress("完整回答", "response_single"))
            runCurrent()
            assertEquals(listOf("第一个问题"), questions)
            assertEquals(listOf("完整", "回答"), chunks)
            assertEquals(before, cancellations)
            assertTrue(coordinator.hasActiveReply)
            coordinator.onTtsUpdate(version, TtsProgress(completed = true))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals("response_single", lastReplyId())
            service.javaClass.getDeclaredMethod("onKeyword", Long::class.javaPrimitiveType,
                String::class.java, String::class.java).apply { isAccessible = true }
                .invoke(service, 1L, "你好豆包", "豆包")
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            assertFalse(field("singleAnswerWaiting").getBoolean(service))
            recognition.invoke(service, AsrSnapshot(confirmedVersion = 3, confirmedText = "第二个问题"))
            runCurrent()
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals(listOf("第一个问题", "第二个问题"), questions)
            assertTrue(coordinator.hasActiveReply)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_PAUSE), 0, 1)
            assertFalse(coordinator.hasActiveReply)
            assertFalse(field("singleAnswerWaiting").getBoolean(service))
        } finally {
            controller.destroy()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun typedMessageWaitsForHistoryUsesSamePipelineAndNeverStartsMicrophone() = runTest {
        ConversationRepository.reset()
        KwsRepository.publish(KwsSnapshot())
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val saved = mutableListOf<ConversationMessage>()
        val requests = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var version = 0L
        val tts = object : StreamingTts {
            override fun start(value: Long, speed: Int) { version = value }
            override fun append(delta: String) { spoken += delta }
            override fun finish() = Unit
            override fun cancel() = Unit
        }
        val execute = service.javaClass.getDeclaredMethod("executeVoiceCommand", AsrCommand::class.java, String::class.java).apply { isAccessible = true }
        val finished = service.javaClass.getDeclaredMethod("finishClosedMicrophone").apply { isAccessible = true }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, tts,
            saveMessage = { saved += it }, onAsrCommand = { execute.invoke(service, it, null) },
            onReplyFinished = { finished.invoke(service) }, judgeOperation = { true }) { text, _ -> flow {
                requests += text
                emit(ResponseProgress("正常回复", "response_1"))
            } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        field("scope").set(service, backgroundScope)
        @Suppress("UNCHECKED_CAST")
        val loaded = field("historyLoaded").get(service) as CompletableDeferred<Unit>
        fun send(text: String) = service.onStartCommand(Intent(service, KwsService::class.java)
            .setAction(KwsService.ACTION_SEND_TEXT).putExtra(KwsService.EXTRA_TEXT, text), 0, 1)
        try {
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_PAUSE), 0, 1)
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            assertFalse(shadowOf(service).isStoppedBySelf)
            send("输入的问题")
            runCurrent()
            assertTrue(saved.isEmpty())
            field("historyReady").setBoolean(service, true)
            loaded.complete(Unit)
            runCurrent()
            assertTrue(requests.isEmpty())
            assertTrue(saved.isEmpty())
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_RESUME), 0, 1)
            send("输入的问题")
            runCurrent()
            assertEquals(listOf("输入的问题"), requests)
            assertEquals(listOf("正常回复"), spoken)
            assertEquals(0L, field("activeRun").getLong(service))
            assertNull(field("microphone").get(service))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals(ListeningStatus.STOPPED, KwsRepository.snapshot.value.status)
            assertFalse(KwsRepository.snapshot.value.chatPaused)
            send("关闭麦克风，豆包")
            runCurrent()
            assertTrue(conversation.hasActiveReply)
            assertEquals(1, requests.size)
            assertEquals(listOf("正常回复"), spoken)
            send("闭嘴，豆包")
            runCurrent()
            assertEquals(listOf("正常回复"), spoken)
            assertEquals(listOf(0, 1, 0, 2, 0, 2), saved.map { it.role })
            assertEquals(1, requests.size)
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            send("暂停时不应发送")
            runCurrent()
            assertEquals(listOf(0, 1, 0, 2, 0, 2), saved.map { it.role })
            assertEquals(1, requests.size)
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_RESUME), 0, 1)
            send("退下豆包")
            runCurrent()
            assertEquals(listOf("正常回复"), spoken)
            assertEquals(listOf(0, 1, 0, 2, 0, 2, 0, 2), saved.map { it.role })
            assertTrue(Intent.CATEGORY_HOME in shadowOf(service).nextStartedActivity.categories)
            assertEquals(1, requests.size)
            conversation.onTtsUpdate(version, TtsProgress(completed = true))
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNull(field("microphone").get(service))
        } finally {
            controller.destroy()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun failedActionStillSavesUserSentenceAndSystemFailureWithoutAiContext() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val spoken = mutableListOf<String>()
        val saved = mutableListOf<ConversationMessage>()
        var requests = 0
        val tts = object : StreamingTts {
            override fun start(version: Long, speed: Int) = Unit
            override fun append(delta: String) { spoken += delta }
            override fun finish() = Unit
            override fun cancel() = Unit
        }
        val execute = service.javaClass.getDeclaredMethod("executeVoiceCommand", AsrCommand::class.java, String::class.java)
            .apply { isAccessible = true }
        val coordinator = ConversationCoordinator(backgroundScope, service::getString, tts,
            saveMessage = { saved += it }, onAsrCommand = { execute.invoke(service, it, null) }, judgeOperation = { true }) { _, _ -> requests++; flow { } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(coordinator))
        try {
            coordinator.startSession()
            coordinator.setEnabled(true)
            // 控制器已停止，操作无法执行；保存错误文字，不调用云端 TTS。
            service.javaClass.getDeclaredField("historyReady").apply { isAccessible = true }.setBoolean(service, true)
            coordinator.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "闭嘴，豆包"))
            assertEquals(listOf(0, 2), saved.map { it.role })
            assertEquals(listOf("闭嘴，豆包", service.getString(R.string.voice_command_stop_failed)), saved.map { it.text })
            assertTrue(saved.all { it.previousResponseId == null })
            assertTrue(spoken.isEmpty())
            assertEquals(0, requests)
        } finally {
            controller.destroy()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun asrActionsPlayLocalHaodeWithoutCallingAiOrTts() = runTest {
        for (name in listOf("豆包", "小白")) for (action in listOf(
            "闭嘴", "别说话", "退下", "退出", "下去吧", "下去", "关闭麦克风")) {
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.setUiVisible(true)
            val controller = Robolectric.buildService(KwsService::class.java)
            val service = controller.get()
            val preferences = AssistantPreferences(service)
            preferences.assistantName = name
            val spoken = mutableListOf<String>()
            var ttsStarts = 0
            var requests = 0
            val tts = object : StreamingTts {
                override fun start(value: Long, speed: Int) { ttsStarts++ }
                override fun append(delta: String) { spoken += delta }
                override fun finish() = Unit
                override fun cancel() = Unit
            }
            val execute = service.javaClass.getDeclaredMethod("executeVoiceCommand", AsrCommand::class.java, String::class.java)
                .apply { isAccessible = true }
            val finished = service.javaClass.getDeclaredMethod("finishClosedMicrophone").apply { isAccessible = true }
            val conversation = ConversationCoordinator(backgroundScope, service::getString, tts,
                assistantName = { preferences.assistantName }, onAsrCommand = { execute.invoke(service, it, null) },
                onReplyFinished = { finished.invoke(service) }, judgeOperation = { true }) { _, _ -> requests++; flow { } }
            service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
                .set(service, lazyOf(conversation))
            fun field(key: String) = service.javaClass.getDeclaredField(key).apply { isAccessible = true }
            val cues = (field("voiceCues").get(service) as Lazy<*>).value as VoiceCuePlayer
            val pool = cues.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(cues) as SoundPool
            val sound = shadowOf(pool)
            sound.notifyResourceLoaded(R.raw.haode, true)
            val recorder = MicrophoneRecorder(service::getString, 16000)
            try {
                recorder.start()
                field("microphone").set(service, recorder)
                field("activeRun").setLong(service, 1L)
                field("historyReady").setBoolean(service, true)
                (field("asr").get(service) as AsrController).start(true)
                service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
                    .invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
                service.javaClass.getDeclaredMethod("onRecognition", AsrSnapshot::class.java).apply { isAccessible = true }
                    .invoke(service, AsrSnapshot(status = AsrStatus.RECEIVE_TIMEOUT,
                        confirmedVersion = 1, confirmedText = "$action，$name"))
                assertEquals(0, requests)
                assertTrue(spoken.isEmpty())
                assertEquals(0, ttsStarts)
                assertEquals(1, sound.getResourcePlaybacks(R.raw.haode).size)
                assertEquals(listOf("$action，$name", "好的"), ConversationRepository.snapshot.value.content.messages.map { it.text })
                assertEquals(listOf(ConversationMessage.USER, ConversationMessage.SYSTEM), ConversationRepository.snapshot.value.content.messages.map { it.role })
                assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.previousResponseId == null })
                assertFalse(KwsRepository.snapshot.value.asrRequested)
                assertNotEquals(AsrStatus.STARTING, AsrRepository.snapshot.value.status)
                assertFalse(shadowOf(service).isStoppedBySelf)
                if (action == "关闭麦克风") {
                    assertFalse(recorder.isRecording)
                    assertEquals(0L, field("activeRun").getLong(service))
                    assertEquals(ListeningStatus.STOPPED, KwsRepository.snapshot.value.status)
                } else {
                    assertEquals(1L, field("activeRun").getLong(service))
                    assertTrue(recorder.isRecording)
                    if (action == "闭嘴" || action == "别说话") assertEquals(AssistantMode.MUTED, KwsRepository.snapshot.value.mode)
                    else assertTrue(Intent.CATEGORY_HOME in shadowOf(service).nextStartedActivity.categories)
                }
                assertFalse(conversation.hasActiveReply)
                assertFalse(shadowOf(service).isStoppedBySelf)
                assertFalse(KwsRepository.snapshot.value.asrRequested)
            } finally {
                controller.destroy()
                recorder.close()
                preferences.assistantName = "豆包"
                ConversationRepository.reset()
                AsrRepository.clear()
                KwsRepository.publish(KwsSnapshot())
                KwsRepository.setUiVisible(false)
            }
        }
    }

    @Test @Config(sdk = [35]) fun microphonePartialsThenFinalStopCaptureButKeepAiAndTtsWithLocalAcknowledgement() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val updates = MutableSharedFlow<ResponseProgress>()
        val saved = mutableListOf<ConversationMessage>()
        val chunks = mutableListOf<String>()
        var cancellations = 0
        var ttsVersion = 0L
        var requests = 0
        val tts = object : StreamingTts {
            override fun start(version: Long, speed: Int) { ttsVersion = version }
            override fun append(delta: String) { chunks += delta }
            override fun finish() = Unit
            override fun cancel() { cancellations++ }
        }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val execute = service.javaClass.getDeclaredMethod("executeVoiceCommand", AsrCommand::class.java, String::class.java)
            .apply { isAccessible = true }
        val finished = service.javaClass.getDeclaredMethod("finishClosedMicrophone").apply { isAccessible = true }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, tts,
            saveMessage = { saved += it }, onAsrCommand = { execute.invoke(service, it, null) },
            onReplyFinished = { finished.invoke(service) }, judgeOperation = { true }) { _, _ -> requests++; updates.take(2) }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        val recognition = service.javaClass.getDeclaredMethod("onRecognition", AsrSnapshot::class.java)
            .apply { isAccessible = true }
        val recorder = MicrophoneRecorder(service::getString, 16000)
        try {
            recorder.start()
            field("microphone").set(service, recorder)
            field("activeRun").setLong(service, 1L)
            field("historyReady").setBoolean(service, true)
            (field("asr").get(service) as AsrController).start(true)
            conversation.startSession()
            service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
                .invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            recognition.invoke(service, AsrSnapshot(confirmedVersion = 1, confirmedText = "讲一个故事"))
            runCurrent()
            val beforeClose = cancellations
            for (text in listOf("关", "关闭", "关闭麦克风", "关闭麦克风，豆包")) {
                recognition.invoke(service, AsrSnapshot(status = AsrStatus.LISTENING,
                    conversation = ConversationContent(liveText = text)))
                assertEquals(beforeClose, cancellations)
                assertEquals(1, updates.subscriptionCount.value)
                assertTrue(recorder.isRecording)
            }
            recognition.invoke(service, AsrSnapshot(confirmedVersion = 2, confirmedText = "关闭麦克风，豆包"))
            assertFalse(recorder.isRecording)
            assertEquals(0L, field("activeRun").getLong(service))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals(AsrStatus.IDLE, AsrRepository.snapshot.value.status)
            assertEquals(1, updates.subscriptionCount.value)
            assertEquals(beforeClose, cancellations)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(listOf("讲一个故事", "关闭麦克风，豆包", "好的"), saved.map { it.text })
            assertEquals(listOf(ConversationMessage.USER, ConversationMessage.USER, ConversationMessage.SYSTEM), saved.map { it.role })
            assertTrue(chunks.isEmpty())
            updates.emit(ResponseProgress("继续"))
            updates.emit(ResponseProgress("继续讲故事", "response_1"))
            runCurrent()
            assertEquals(listOf("继续", "讲故事"), chunks)
            assertEquals(1, requests)
            assertEquals("response_1", saved.last().previousResponseId)
            assertEquals(listOf("好的"), saved.filter { it.isSystem }.map { it.text })
            assertEquals(beforeClose, cancellations)
            assertFalse(shadowOf(service).isStoppedBySelf)
            conversation.onTtsUpdate(ttsVersion, TtsProgress(completed = true))
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            recorder.close()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test @Config(sdk = [35]) fun closingMicrophonePreservesReplyAndPauseCanStillStopPlayback() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val updates = MutableSharedFlow<ResponseProgress>()
        var cancellations = 0
        var ttsVersion = 0L
        val chunks = mutableListOf<String>()
        val tts = object : StreamingTts {
            override fun start(version: Long, speed: Int) { ttsVersion = version }
            override fun append(delta: String) { if (delta.isNotEmpty()) chunks += delta }
            override fun finish() = Unit
            override fun cancel() { cancellations++ }
        }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, tts, judgeOperation = { true }) { _, _ -> updates }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        try {
            field("activeRun").setLong(service, 1L)
            field("historyReady").setBoolean(service, true)
            (field("asr").get(service) as AsrController).start(true)
            conversation.startSession()
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            conversation.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "继续讲故事"))
            runCurrent()
            val beforeClose = cancellations
            service.javaClass.getDeclaredMethod("executeVoiceCommand", AsrCommand::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(service, AsrCommand.CLOSE_MICROPHONE, null)
            assertEquals(0L, field("activeRun").getLong(service))
            assertEquals(ListeningStatus.STOPPED, KwsRepository.snapshot.value.status)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
            assertFalse(shadowOf(service).isStoppedBySelf)
            updates.emit(ResponseProgress("关麦之后继续输出"))
            runCurrent()
            assertEquals(listOf("关麦之后继续输出"), chunks)
            assertEquals(beforeClose, cancellations)
            // 模拟重新开麦从 STARTING 到 LISTENING，不能因初始化间隙取消旧回答。
            field("microphoneClosed").setBoolean(service, false)
            field("activeRun").setLong(service, 2L)
            (field("asr").get(service) as AsrController).start(true)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.STARTING))
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            assertEquals(beforeClose, cancellations)
            updates.emit(ResponseProgress("关麦之后继续输出完整回答", "response_1"))
            runCurrent()
            assertEquals("response_1", lastReplyId())
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_STOP), 0, 2)
            assertTrue(conversation.hasActiveReply)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_PAUSE), 0, 3)
            runCurrent()
            assertFalse(conversation.hasActiveReply)
            assertEquals(beforeClose + 1, cancellations)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            publish.invoke(service, KwsRepository.snapshot.value)
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_RESUME), 0, 4)
            assertFalse(KwsRepository.snapshot.value.chatPaused)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals(0L, field("activeRun").getLong(service))
            conversation.onTextSubmitted("关麦后继续输入")
            runCurrent()
            assertTrue(conversation.hasActiveReply)
            conversation.onTtsUpdate(ttsVersion, TtsProgress(completed = true))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
        } finally {
            controller.destroy()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun chatPauseKeepsCaptureButMicrophoneStopClosesItWithoutDeletingHistory() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val recorder = MicrophoneRecorder(service::getString, 16000)
        val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow { } }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        try {
            val topic = ChatTopic()
            ConversationRepository.setTopic(topic)
            val messages = listOf(ConversationMessage("保留当前聊天", false, topicId = topic.id),
                ConversationMessage("已有回答", true, topicId = topic.id, previousResponseId = "keep-response"))
            ConversationRepository.publish(com.spake.brony.doubaoassistant.data.model.ConversationSnapshot(
                com.spake.brony.doubaoassistant.data.model.ConversationContent(messages = messages)))
            recorder.start()
            field("microphone").set(service, recorder)
            field("activeRun").setLong(service, 1L)
            field("historyReady").setBoolean(service, true)
            (field("asr").get(service) as AsrController).start(true)
            service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
                .invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_PAUSE), 0, 1)
            assertTrue(recorder.isRecording)
            assertEquals(1L, field("activeRun").getLong(service))
            assertEquals(AssistantMode.MUTED, KwsRepository.snapshot.value.mode)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertFalse(shadowOf(service).isStoppedBySelf)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_RESUME), 0, 2)
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_STOP), 0, 3)
            assertFalse(recorder.isRecording)
            assertEquals(0L, field("activeRun").getLong(service))
            assertEquals(ListeningStatus.STOPPED, KwsRepository.snapshot.value.status)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(topic, ConversationRepository.activeTopic)
            assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
            assertEquals("keep-response", lastReplyId())
        } finally {
            controller.destroy()
            recorder.close()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun exitWaitsForRecordingCleanupAndPendingSqlWritesBeforeKillingProcess() = runTest {
        ShadowProcess.setPid(12345)
        ShadowProcess.clearKilledProcesses()
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val history = ChatHistoryRepository(service) { fail("History write failed") }
        val conversation = ConversationCoordinator(backgroundScope, service::getString,
            saveMessage = history::save, createTopic = history::createTopic, judgeOperation = { true }) { _, _ -> flow { } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        service.javaClass.declaredFields.first { it.name.startsWith("history$") }.apply { isAccessible = true }
            .set(service, lazyOf(history))
        val worker = service.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(service) as ExecutorService
        val releaseCapture = CountDownLatch(1)
        val captureRunning = CountDownLatch(1)
        var destroyed = false
        try {
            worker.execute { captureRunning.countDown(); releaseCapture.await(5, TimeUnit.SECONDS) }
            assertTrue(captureRunning.await(5, TimeUnit.SECONDS))
            val topic = ChatTopic()
            val message = ConversationMessage("退出时仍需保存", false, "exit-user", 1L, topic.id)
            ConversationRepository.setTopic(topic)
            history.createTopic(topic)
            history.save(message)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_EXIT), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            controller.destroy()
            destroyed = true
            assertFalse(ShadowProcess.wasKilled(Process.myPid()))
            releaseCapture.countDown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(ShadowProcess.wasKilled(Process.myPid()))
            withContext(Dispatchers.IO) {
                val database = ChatHistoryDatabase.create(service)
                try {
                    assertEquals(listOf(message), database.historyDao().latest(topic.id))
                } finally { database.close() }
            }
        } finally {
            releaseCapture.countDown()
            if (!destroyed) controller.destroy()
            history.awaitClosed()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
            ShadowProcess.clearKilledProcesses()
        }
    }

    @Test @Config(sdk = [28, 35]) fun floatingMicrophoneClosesCaptureKeepsNotificationAndWindowAndCanReopen() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(false)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val preferences = AssistantPreferences(service)
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow { } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val recorder = MicrophoneRecorder(service::getString, 16000)
        val releaseWorker = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)
        val worker = field("worker").get(service) as ExecutorService
        worker.execute { workerStarted.countDown(); releaseWorker.await(5, TimeUnit.SECONDS) }
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        try {
            service.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("local_kws", "test", NotificationManager.IMPORTANCE_LOW))
            preferences.backgroundChat = true
            ShadowSettings.setCanDrawOverlays(true)
            recorder.start()
            field("microphone").set(service, recorder)
            field("activeRun").setLong(service, 1L)
            field("nextRun").setLong(service, 1L)
            field("historyReady").setBoolean(service, true)
            (field("asr").get(service) as AsrController).start(true)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            val overlay = (field("overlay").get(service) as Lazy<*>).value as ChatOverlay
            val binding = overlay.javaClass.getDeclaredField("binding").apply { isAccessible = true }
                .get(overlay) as com.spake.brony.doubaoassistant.databinding.OverlayChatBinding
            binding.overlayMicrophone.performClick()
            assertFalse(recorder.isRecording)
            assertEquals(0L, field("activeRun").getLong(service))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertFalse(shadowOf(service).isForegroundStopped)
            assertTrue(overlay.isShowing)
            assertEquals("开启麦克风", binding.overlayMicrophone.contentDescription)
            assertFalse(KwsRepository.snapshot.value.chatPaused)
            assertEquals("暂停聊天", binding.overlayToggleChat.contentDescription)
            assertTrue(binding.overlayToggleChat.isEnabled)
            binding.overlayToggleChat.performClick()
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            assertEquals("恢复聊天", binding.overlayToggleChat.contentDescription)
            binding.overlayToggleChat.performClick()
            assertFalse(KwsRepository.snapshot.value.chatPaused)
            assertEquals("暂停聊天", binding.overlayToggleChat.contentDescription)
            assertEquals(0L, field("activeRun").getLong(service))
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertFalse(recorder.isRecording)
            assertEquals(service.getString(R.string.kws_session_without_mic),
                shadowOf(service).lastForegroundNotification.extras.getString(android.app.Notification.EXTRA_TEXT))
            if (android.os.Build.VERSION.SDK_INT >= 34) assertEquals(
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_REFRESH_BACKGROUND), 0, 1)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(overlay.isShowing)
            binding.overlayMicrophone.performClick()
            assertEquals(ListeningStatus.STARTING, KwsRepository.snapshot.value.status)
            assertEquals(2L, field("activeRun").getLong(service))
            assertEquals("关闭麦克风和本地唤醒", binding.overlayMicrophone.contentDescription)
            assertTrue(overlay.isShowing)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_STOP), 0, 2)
            assertTrue(overlay.isShowing)
            binding.overlayToggleChat.performClick()
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            publish.invoke(service, KwsRepository.snapshot.value)
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            binding.overlayMicrophone.performClick()
            publish.invoke(service, KwsRepository.snapshot.value.copy(status = ListeningStatus.LISTENING))
            assertTrue(KwsRepository.snapshot.value.chatPaused)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals("恢复聊天", binding.overlayToggleChat.contentDescription)
            binding.overlayToggleChat.performClick()
            assertFalse(KwsRepository.snapshot.value.chatPaused)
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            KwsRepository.setUiVisible(true)
            publish.invoke(service, KwsRepository.snapshot.value)
            assertFalse(overlay.isShowing)
            assertFalse(shadowOf(service).isStoppedBySelf)
            KwsRepository.setUiVisible(false)
            publish.invoke(service, KwsRepository.snapshot.value)
            assertTrue(overlay.isShowing)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_EXIT), 0, 3)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(shadowOf(service).isForegroundStopped)
            assertFalse(overlay.isShowing)
        } finally {
            controller.destroy()
            releaseWorker.countDown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            recorder.close()
            preferences.backgroundChat = false
            ShadowSettings.setCanDrawOverlays(false)
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun floatingPauseButtonControlsExistingAsrAndKeepsCaptureRunning() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val preferences = AssistantPreferences(service)
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow { } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        try {
            field("historyReady").setBoolean(service, true)
            field("activeRun").setLong(service, 1L)
            (field("asr").get(service) as AsrController).start(true)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            preferences.backgroundChat = true
            ShadowSettings.setCanDrawOverlays(true)
            KwsRepository.setUiVisible(false)
            publish.invoke(service, KwsRepository.snapshot.value)
            val overlay = (field("overlay").get(service) as Lazy<*>).value as ChatOverlay
            val binding = overlay.javaClass.getDeclaredField("binding").apply { isAccessible = true }
                .get(overlay) as com.spake.brony.doubaoassistant.databinding.OverlayChatBinding
            assertEquals("暂停聊天", binding.overlayToggleChat.contentDescription)
            binding.overlayToggleChat.performClick()
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertEquals(AssistantMode.MUTED, KwsRepository.snapshot.value.mode)
            assertEquals("恢复聊天", binding.overlayToggleChat.contentDescription)
            assertTrue(overlay.isShowing)
            assertEquals(1L, field("activeRun").getLong(service))
            binding.overlayToggleChat.performClick()
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            assertEquals("暂停聊天", binding.overlayToggleChat.contentDescription)
            assertTrue(overlay.isShowing)
            assertEquals(1L, field("activeRun").getLong(service))
        } finally {
            controller.destroy()
            preferences.backgroundChat = false
            ShadowSettings.setCanDrawOverlays(false)
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun floatingChatPreservesInFlightReplyAndOnlyEnablesBackgroundAsrWithPermission() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val preferences = AssistantPreferences(service)
        var cancelled = false
        val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow {
            try { emit(ResponseProgress("继续回复")); awaitCancellation() }
            finally { cancelled = true }
        } }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val listening = KwsSnapshot(status = ListeningStatus.LISTENING)
        val asr = field("asr").get(service) as AsrController
        try {
            field("historyReady").setBoolean(service, true)
            field("activeRun").setLong(service, 1L)
            asr.start(true)
            publish.invoke(service, listening)
            preferences.backgroundChat = true
            ShadowSettings.setCanDrawOverlays(true)
            conversation.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "继续聊天"))
            runCurrent()
            KwsRepository.setUiVisible(false)
            publish.invoke(service, listening)
            runCurrent()
            val overlay = (field("overlay").get(service) as Lazy<*>).value as ChatOverlay
            assertTrue(overlay.isShowing)
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            assertFalse(cancelled)
            KwsRepository.setUiVisible(true)
            publish.invoke(service, listening)
            assertFalse(overlay.isShowing)
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            assertFalse(cancelled)
            KwsRepository.setUiVisible(false)
            publish.invoke(service, listening)
            ShadowSettings.setCanDrawOverlays(false)
            publish.invoke(service, listening)
            runCurrent()
            assertFalse(overlay.isShowing)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
            assertTrue(cancelled)
            preferences.backgroundChat = false
            ShadowSettings.setCanDrawOverlays(true)
            publish.invoke(service, listening)
            assertFalse(overlay.isShowing)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
        } finally {
            controller.destroy()
            preferences.backgroundChat = false
            ShadowSettings.setCanDrawOverlays(false)
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun clearHistoryCancelsOldReplyDeletesAllTopicsAndKeepsListening() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        var errors = 0
        var cancelled = false
        val history = ChatHistoryRepository(service) { errors++ }
        val archived = ChatTopic("archived", timeMillis = 1)
        history.createTopic(archived)
        history.save(ConversationMessage("更早的聊天", false, "archived_user", 2, archived.id))
        val conversation = ConversationCoordinator(backgroundScope, service::getString,
            saveMessage = history::save, createTopic = history::createTopic, judgeOperation = { true }) { _, _ -> flow {
            try { emit(ResponseProgress("尚未结束的旧回复")); awaitCancellation() }
            finally { cancelled = true }
        } }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        service.javaClass.declaredFields.first { it.name.startsWith("history$") }.apply { isAccessible = true }
            .set(service, lazyOf(history))
        try {
            field("historyReady").setBoolean(service, true)
            field("activeRun").setLong(service, 1L)
            (field("asr").get(service) as AsrController).start(true)
            service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
                .invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            conversation.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "旧问题"))
            runCurrent()
            val old = checkNotNull(ConversationRepository.activeTopic)
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_CLEAR_HISTORY), 0, 1)
            runCurrent()
            val next = checkNotNull(ConversationRepository.activeTopic)
            assertNotEquals(old.id, next.id)
            assertTrue(cancelled)
            assertTrue(ConversationRepository.snapshot.value.content.messages.isEmpty())
            assertNull(lastReplyId())
            assertEquals(1L, field("activeRun").getLong(service))
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            assertFalse(shadowOf(service).isStoppedBySelf)
            history.close()
            history.awaitClosed()
            assertEquals(0, errors)
            withContext(Dispatchers.IO) {
                val database = ChatHistoryDatabase.create(service)
                try {
                    val dao = database.historyDao()
                    assertEquals(next, dao.currentTopic())
                    assertTrue(dao.latest(archived.id).isEmpty())
                    assertTrue(dao.latest(old.id).isEmpty())
                    database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM topics").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(1, it.getInt(0))
                    }
                    database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
                } finally { database.close() }
            }
        } finally {
            controller.destroy()
            history.awaitClosed()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun endingTopicKeepsTheSameListenerAndImmediatelyAcceptsSpeechForTheNewTopic() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        val topics = mutableListOf<ChatTopic>()
        val saved = mutableListOf<ConversationMessage>()
        val requests = mutableListOf<Pair<String, String?>>()
        var cancelled = 0
        var ttsCancelled = 0
        val tts = object : StreamingTts {
            override fun start(version: Long, speed: Int) = Unit
            override fun append(delta: String) = Unit
            override fun finish() = Unit
            override fun cancel() { ttsCancelled++ }
        }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, speech = tts,
            saveMessage = { saved += it }, createTopic = { topics += it }, judgeOperation = { true }) { text, id -> flow {
            requests += text to id
            try { emit(ResponseProgress("旧回复")); awaitCancellation() }
            finally { cancelled++ }
        } }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        val asr = field("asr").get(service) as AsrController
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val receive = service.javaClass.getDeclaredMethod("onRecognition", AsrSnapshot::class.java).apply { isAccessible = true }
        try {
            field("historyReady").setBoolean(service, true)
            field("activeRun").setLong(service, 1L)
            asr.start(true)
            conversation.startSession()
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            receive.invoke(service, AsrSnapshot(confirmedVersion = 1, confirmedText = "旧话题"))
            runCurrent()
            val oldTopic = checkNotNull(ConversationRepository.activeTopic)
            val oldRequest = field("asrRequest").get(service)
            val previousTtsCancellations = ttsCancelled
            service.onStartCommand(Intent(service, KwsService::class.java).setAction(KwsService.ACTION_END_TOPIC), 0, 1)
            runCurrent()
            val topic = checkNotNull(ConversationRepository.activeTopic)
            assertNotEquals(oldTopic.id, topic.id)
            assertEquals(listOf(oldTopic, topic), topics)
            assertTrue(ConversationRepository.snapshot.value.content.messages.isEmpty())
            assertEquals(ConversationPhase.READY, ConversationRepository.snapshot.value.content.phase)
            assertNull(lastReplyId())
            assertEquals(1, cancelled)
            assertTrue(ttsCancelled > previousTtsCancellations)
            assertTrue(saved.all { it.topicId == oldTopic.id })
            assertEquals(1L, field("activeRun").getLong(service))
            assertEquals(ListeningStatus.LISTENING, KwsRepository.snapshot.value.status)
            assertTrue(KwsRepository.snapshot.value.asrRequested)
            assertNotEquals(oldRequest, field("asrRequest").get(service))
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertFalse((field("worker").get(service) as ExecutorService).isShutdown)
            assertEquals(AsrSnapshot(), AsrRepository.snapshot.value)
            receive.invoke(service, AsrSnapshot(confirmedVersion = 1, confirmedText = "新话题"))
            runCurrent()
            assertEquals(listOf("旧话题" to null, "新话题" to null), requests)
            assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.topicId == topic.id })
        } finally {
            controller.destroy()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun kwsCloseMicrophoneWorksWhileAsrIsPausedAndKeepsAiAndTts() = runTest {
        for (name in listOf("豆包", "小白")) for (nameFirst in listOf(true, false)) {
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.setUiVisible(true)
            val controller = Robolectric.buildService(KwsService::class.java)
            val service = controller.get()
            val preferences = AssistantPreferences(service)
            preferences.assistantName = name
            val updates = MutableSharedFlow<ResponseProgress>()
            val chunks = mutableListOf<String>()
            var version = 0L
            var ttsCancelled = 0
            var requests = 0
            val tts = object : StreamingTts {
                override fun start(value: Long, speed: Int) { version = value }
                override fun append(delta: String) { chunks += delta }
                override fun finish() = Unit
                override fun cancel() { ttsCancelled++ }
            }
            val conversation = ConversationCoordinator(backgroundScope, service::getString, tts, judgeOperation = { true }) { _, _ ->
                requests++
                updates.take(2)
            }
            service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
                .set(service, lazyOf(conversation))
            fun field(key: String) = service.javaClass.getDeclaredField(key).apply { isAccessible = true }
            val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
            val keyword = service.javaClass.getDeclaredMethod("onKeyword", Long::class.javaPrimitiveType,
                String::class.java, String::class.java).apply { isAccessible = true }
            val cues = (field("voiceCues").get(service) as Lazy<*>).value as VoiceCuePlayer
            val sound = shadowOf(cues.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(cues) as SoundPool)
            sound.notifyResourceLoaded(R.raw.haode, true)
            val recorder = MicrophoneRecorder(service::getString, 16000)
            try {
                recorder.start()
                field("microphone").set(service, recorder)
                field("activeRun").setLong(service, 1L)
                field("historyReady").setBoolean(service, true)
                val asr = field("asr").get(service) as AsrController
                asr.start(true)
                publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
                conversation.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "讲个故事"))
                runCurrent()
                updates.emit(ResponseProgress("继续"))
                if (nameFirst) {
                    field("singleAnswerWaiting").setBoolean(service, true)
                    asr.onCommand(AsrCommand.STOP_REPLY)
                    publish.invoke(service, KwsRepository.snapshot.value)
                    assertFalse(KwsRepository.snapshot.value.asrRequested)
                }
                val previousCancels = ttsCancelled
                val phrase = if (nameFirst) "${name}关闭麦克风" else "关闭麦克风$name"
                keyword.invoke(service, 1L, phrase, name)
                runCurrent()
                assertEquals(previousCancels, ttsCancelled)
                assertTrue(conversation.hasActiveReply)
                assertEquals(1, updates.subscriptionCount.value)
                assertFalse(recorder.isRecording)
                assertEquals(0L, field("activeRun").getLong(service))
                assertEquals(KwsCommand.CLOSE_MICROPHONE, KwsRepository.snapshot.value.command)
                assertFalse(KwsRepository.snapshot.value.asrRequested)
                assertEquals(1, sound.getResourcePlaybacks(R.raw.haode).size)
                updates.emit(ResponseProgress("继续回答", "response_after_close"))
                runCurrent()
                assertEquals(listOf("继续", "回答"), chunks)
                assertEquals(1, requests)
                conversation.onTtsUpdate(version, TtsProgress(completed = true))
                assertEquals("response_after_close", lastReplyId())
                keyword.invoke(service, 1L, "你好$name", name)
                assertEquals(0L, field("activeRun").getLong(service))
                assertFalse(KwsRepository.snapshot.value.asrRequested)
            } finally {
                controller.destroy()
                recorder.close()
                preferences.assistantName = "豆包"
                ConversationRepository.reset()
                AsrRepository.clear()
                KwsRepository.publish(KwsSnapshot())
                KwsRepository.setUiVisible(false)
            }
        }
    }

    @Test fun kwsReturnHomeKeepsCaptureAndUsesExistingBackgroundChatPolicy() = runTest {
        for (name in listOf("豆包", "小白")) for (keepChat in listOf(false, true)) {
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.setUiVisible(true)
            ShadowSettings.setCanDrawOverlays(keepChat)
            val controller = Robolectric.buildService(KwsService::class.java)
            val service = controller.get()
            val preferences = AssistantPreferences(service)
            preferences.assistantName = name
            preferences.backgroundChat = keepChat
            var cancelled = 0
            val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow {
                try { emit(ResponseProgress("正在回答")); awaitCancellation() }
                finally { cancelled++ }
            } }
            service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
                .set(service, lazyOf(conversation))
            fun field(key: String) = service.javaClass.getDeclaredField(key).apply { isAccessible = true }
            val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
            val keyword = service.javaClass.getDeclaredMethod("onKeyword", Long::class.javaPrimitiveType,
                String::class.java, String::class.java).apply { isAccessible = true }
            val cues = (field("voiceCues").get(service) as Lazy<*>).value as VoiceCuePlayer
            val sound = shadowOf(cues.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(cues) as SoundPool)
            sound.notifyResourceLoaded(R.raw.haode, true)
            val recorder = MicrophoneRecorder(service::getString, 16000)
            try {
                recorder.start()
                field("microphone").set(service, recorder)
                field("activeRun").setLong(service, 1L)
                field("historyReady").setBoolean(service, true)
                (field("asr").get(service) as AsrController).start(true)
                publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
                conversation.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "旧问题"))
                runCurrent()
                val beforeRequest = field("asrRequest").get(service)
                val topic = ConversationRepository.activeTopic
                keyword.invoke(service, 1L, if (keepChat) "${name}退出" else "退出吧$name", name)
                runCurrent()
                val home = shadowOf(service).nextStartedActivity
                assertEquals(Intent.ACTION_MAIN, home.action)
                assertTrue(Intent.CATEGORY_HOME in home.categories)
                assertNotEquals(beforeRequest, field("asrRequest").get(service))
                assertEquals(KwsCommand.RETURN_HOME, KwsRepository.snapshot.value.command)
                assertEquals(keepChat, KwsRepository.snapshot.value.asrRequested)
                assertEquals(if (keepChat) 0 else 1, cancelled)
                assertTrue(recorder.isRecording)
                assertEquals(1L, field("activeRun").getLong(service))
                assertEquals(topic, ConversationRepository.activeTopic)
                assertEquals(1, sound.getResourcePlaybacks(R.raw.haode).size)
                (field("asr").get(service) as AsrController).onCommand(AsrCommand.STOP_REPLY)
                KwsRepository.setUiVisible(false)
                publish.invoke(service, KwsRepository.snapshot.value)
                keyword.invoke(service, 1L, "退下$name", name)
                assertEquals(Intent.ACTION_MAIN, shadowOf(service).nextStartedActivity.action)
                assertFalse(KwsRepository.snapshot.value.asrRequested)
                assertTrue(recorder.isRecording)
                assertEquals(2, sound.getResourcePlaybacks(R.raw.haode).size)
            } finally {
                controller.destroy()
                recorder.close()
                preferences.assistantName = "豆包"
                preferences.backgroundChat = false
                ShadowSettings.setCanDrawOverlays(false)
                ConversationRepository.reset()
                AsrRepository.clear()
                KwsRepository.publish(KwsSnapshot())
                KwsRepository.setUiVisible(false)
            }
        }
    }

    @Test fun kwsCommandsSaveUserAndSystemToRoomAndUiWithoutCallingAiOrCloudTts() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("local_kws", "test", NotificationManager.IMPORTANCE_LOW))
        val preferences = AssistantPreferences(service)
        preferences.assistantName = "小白"
        preferences.backgroundChat = false
        var requests = 0
        var cloudSpeech = 0
        var historyErrors = 0
        val history = ChatHistoryRepository(service) { historyErrors++ }
        val tts = object : StreamingTts {
            override fun start(version: Long, speed: Int) { cloudSpeech++ }
            override fun append(delta: String) = Unit
            override fun finish() = Unit
            override fun cancel() = Unit
        }
        val conversation = ConversationCoordinator(backgroundScope, service::getString, tts,
            saveMessage = history::save, createTopic = history::createTopic, judgeOperation = { true }) { _, _ -> requests++; flow { } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        service.javaClass.declaredFields.first { it.name.startsWith("history$") }.apply { isAccessible = true }
            .set(service, lazyOf(history))
        fun field(key: String) = service.javaClass.getDeclaredField(key).apply { isAccessible = true }
        field("scope").set(service, backgroundScope)
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val keyword = service.javaClass.getDeclaredMethod("onKeyword", Long::class.javaPrimitiveType,
            String::class.java, String::class.java).apply { isAccessible = true }
        val topic = ChatTopic("existing-topic", timeMillis = 1)
        try {
            history.clearAll(topic)
            history.save(ConversationMessage("已完成回答", ConversationMessage.ASSISTANT,
                "existing-ai", 2, topic.id, "response-kept"))
            assertEquals("response-kept", history.lastResponseId(topic.id))
            field("activeRun").setLong(service, 1L)
            (field("asr").get(service) as AsrController).start(true)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))

            // 历史未加载时先执行闭嘴，加载后再把口令写回原话题，不能创建空话题。
            keyword.invoke(service, 1L, "闭嘴小白", "小白")
            runCurrent()
            assertEquals(AssistantMode.MUTED, KwsRepository.snapshot.value.mode)
            assertNull(ConversationRepository.activeTopic)
            assertTrue(ConversationRepository.snapshot.value.content.messages.isEmpty())
            conversation.restoreHistory(topic, history.latest(topic.id))
            field("historyReady").setBoolean(service, true)
            @Suppress("UNCHECKED_CAST")
            val loaded = field("historyLoaded").get(service) as CompletableDeferred<Unit>
            loaded.complete(Unit)
            runCurrent()
            assertEquals(listOf("闭嘴小白", "好的"), ConversationRepository.snapshot.value.content.messages.takeLast(2).map { it.text })

            for ((phrase, reply) in listOf("说话小白" to "好的", "小白你好" to "我在", "小白小白" to "我在",
                "退下小白" to "好的", "小白说话" to "好的", "关闭麦克风小白" to "好的")) {
                keyword.invoke(service, 1L, phrase, "小白")
                runCurrent()
                val pair = ConversationRepository.snapshot.value.content.messages.takeLast(2)
                assertEquals(listOf(phrase, reply), pair.map { it.text })
                assertEquals(listOf(ConversationMessage.USER, ConversationMessage.SYSTEM), pair.map { it.role })
                assertTrue(pair.all { it.previousResponseId == null && it.topicId == topic.id })
            }
            assertEquals(0, requests)
            assertEquals(0, cloudSpeech)
            assertEquals(0L, field("activeRun").getLong(service))
            assertEquals("response-kept", history.lastResponseId(topic.id)) // 排空写入再读 Room。
            val messages = ConversationRepository.snapshot.value.content.messages
            assertEquals(15, messages.size)
            assertEquals(messages, history.latest(topic.id))
            keyword.invoke(service, 1L, "你好小白", "小白") // 关麦后不能继续记录旧 KWS 回调。
            assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
            history.close()
            history.awaitClosed()
            withContext(Dispatchers.IO) {
                val database = ChatHistoryDatabase.create(service)
                try {
                    assertEquals(messages, database.historyDao().latest(topic.id))
                    assertEquals(topic.id, database.historyDao().currentTopic().id)
                    assertEquals("response-kept", database.historyDao().lastResponseId(topic.id))
                } finally { database.close() }
            }
            assertEquals(0, historyErrors)
        } finally {
            controller.destroy()
            history.close()
            history.awaitClosed()
            preferences.assistantName = "豆包"
            preferences.backgroundChat = false
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun kwsStopInterruptsAiAndTtsImmediatelyAndLateAsrDoesNotAcknowledgeAgain() = runTest {
        for (name in listOf("豆包", "小白")) for (visible in listOf(true, false)) {
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.setUiVisible(visible)
            val controller = Robolectric.buildService(KwsService::class.java)
            val service = controller.get()
            val preferences = AssistantPreferences(service)
            preferences.assistantName = name
            var cancelled = 0
            var ttsCancelled = 0
            var requests = 0
            var confirmedVersion = 0L
            val tts = object : StreamingTts {
                override fun start(version: Long, speed: Int) = Unit
                override fun append(delta: String) = Unit
                override fun finish() = Unit
                override fun cancel() { ttsCancelled++ }
            }
            val conversation = ConversationCoordinator(backgroundScope, service::getString, tts, judgeOperation = { true }) { _, _ -> flow {
                requests++
                try { emit(ResponseProgress("正在回答")); awaitCancellation() }
                finally { cancelled++ }
            } }
            service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
                .set(service, lazyOf(conversation))
            fun field(key: String) = service.javaClass.getDeclaredField(key).apply { isAccessible = true }
            val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
            val keyword = service.javaClass.getDeclaredMethod("onKeyword", Long::class.javaPrimitiveType,
                String::class.java, String::class.java).apply { isAccessible = true }
            val receive = service.javaClass.getDeclaredMethod("onRecognition", AsrSnapshot::class.java).apply { isAccessible = true }
            val cues = (field("voiceCues").get(service) as Lazy<*>).value as VoiceCuePlayer
            val pool = cues.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(cues) as SoundPool
            val sound = shadowOf(pool)
            sound.notifyResourceLoaded(R.raw.haode, true)
            val recorder = MicrophoneRecorder(service::getString, 16000)
            try {
                recorder.start()
                field("microphone").set(service, recorder)
                field("activeRun").setLong(service, 1L)
                field("historyReady").setBoolean(service, true)
                val asr = field("asr").get(service) as AsrController
                asr.start(visible)
                for ((index, phrase) in listOf("闭嘴$name", "${name}闭嘴", "别说话$name", "${name}别说话").withIndex()) {
                    asr.onCommand(KwsCommand.RESUME)
                    publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
                    conversation.onAsrResult(AsrSnapshot(confirmedVersion = ++confirmedVersion, confirmedText = "问题"))
                    runCurrent()
                    val beforeTts = ttsCancelled
                    val beforeAsr = field("asrRequest").get(service)
                    keyword.invoke(service, 1L, phrase, name)
                    runCurrent()
                    assertEquals(index + 1, cancelled)
                    assertTrue(ttsCancelled > beforeTts)
                    assertFalse(conversation.hasActiveReply)
                    assertFalse(KwsRepository.snapshot.value.asrRequested)
                    assertEquals(AssistantMode.MUTED, KwsRepository.snapshot.value.mode)
                    assertEquals(KwsCommand.STOP_REPLY, KwsRepository.snapshot.value.command)
                    assertNotEquals(beforeAsr, field("asrRequest").get(service))
                    assertTrue(recorder.isRecording)
                    assertEquals(1L, field("activeRun").getLong(service))
                    assertEquals(index + 1, sound.getResourcePlaybacks(R.raw.haode).size)
                    assertNull(shadowOf(service).nextStartedActivity)
                    val afterAsr = field("asrRequest").get(service)
                    val systemCount = ConversationRepository.snapshot.value.content.messages.count { it.role == ConversationMessage.SYSTEM }
                    receive.invoke(service, AsrSnapshot(status = AsrStatus.RECEIVE_TIMEOUT,
                        confirmedVersion = ++confirmedVersion, confirmedText = phrase))
                    runCurrent()
                    assertEquals(afterAsr, field("asrRequest").get(service))
                    assertEquals(systemCount, ConversationRepository.snapshot.value.content.messages.count { it.role == ConversationMessage.SYSTEM })
                    assertEquals(index + 1, sound.getResourcePlaybacks(R.raw.haode).size)
                    assertEquals(index + 1, requests)
                }
                ConversationRepository.reset()
                field("historyReady").setBoolean(service, false)
                keyword.invoke(service, 1L, "闭嘴$name", name)
                assertNull(ConversationRepository.activeTopic)
                assertTrue(ConversationRepository.snapshot.value.content.messages.isEmpty())
                assertEquals(5, sound.getResourcePlaybacks(R.raw.haode).size)
            } finally {
                controller.destroy()
                recorder.close()
                preferences.assistantName = "豆包"
                ConversationRepository.reset()
                AsrRepository.clear()
                KwsRepository.publish(KwsSnapshot())
                KwsRepository.setUiVisible(false)
            }
        }
    }

    @Test fun allEightKwsCommandsResumeSpeechWithoutOpeningAppAndKeepTheMicrophone() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.setUiVisible(true)
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("local_kws", "test", NotificationManager.IMPORTANCE_LOW))
        val preferences = AssistantPreferences(service)
        var cancelled = 0
        var confirmedVersion = 0L
        val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow {
            try { emit(ResponseProgress("正在回答")); awaitCancellation() }
            finally { cancelled++ }
        } }
        service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
            .set(service, lazyOf(conversation))
        fun field(key: String) = service.javaClass.getDeclaredField(key).apply { isAccessible = true }
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val keyword = service.javaClass.getDeclaredMethod("onKeyword", Long::class.javaPrimitiveType,
            String::class.java, String::class.java).apply { isAccessible = true }
        val cues = (field("voiceCues").get(service) as Lazy<*>).value as VoiceCuePlayer
        val sound = shadowOf(cues.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(cues) as SoundPool)
        sound.notifyResourceLoaded(R.raw.haode, true)
        sound.notifyResourceLoaded(R.raw.wozai, true)
        fun assertCue(phrase: String, name: String) {
            val greeting = phrase in listOf("你好$name", "$name$name", "${name}你好", "在吗$name", "${name}在吗")
            assertEquals(1, sound.getResourcePlaybacks(if (greeting) R.raw.wozai else R.raw.haode).size)
            assertFalse(sound.wasResourcePlayed(if (greeting) R.raw.haode else R.raw.wozai))
        }
        try {
            field("activeRun").setLong(service, 1L)
            field("historyReady").setBoolean(service, true)
            val asr = field("asr").get(service) as AsrController
            for (name in listOf("豆包", "小白")) {
                preferences.assistantName = name
                KwsRepository.setUiVisible(true)
                asr.start(true)
                publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
                for (phrase in listOf("你好$name", "$name$name", "${name}你好", "在吗$name", "${name}在吗",
                    "可以说话了$name", "说话$name", "${name}说话")) {
                    conversation.onAsrResult(AsrSnapshot(confirmedVersion = ++confirmedVersion, confirmedText = "问题"))
                    runCurrent()
                    val before = cancelled
                    val oldRequest = field("asrRequest").get(service)
                    sound.clearPlayed()
                    keyword.invoke(service, 1L, phrase, name)
                    runCurrent()
                    assertCue(phrase, name)
                    assertEquals(before + 1, cancelled)
                    assertNotEquals(oldRequest, field("asrRequest").get(service))
                    assertTrue(KwsRepository.snapshot.value.asrRequested)
                    assertEquals(1L, field("activeRun").getLong(service))
                }
                for (phrase in listOf("打开麦克风$name", "回到主页$name", "回到首页$name")) {
                    val oldRequest = field("asrRequest").get(service)
                    keyword.invoke(service, 1L, phrase, name)
                    assertEquals(oldRequest, field("asrRequest").get(service))
                    assertEquals(1L, field("activeRun").getLong(service))
                }
                KwsRepository.setUiVisible(false)
                asr.onUiVisibilityChanged(false)
                publish.invoke(service, KwsRepository.snapshot.value)
                assertFalse(KwsRepository.snapshot.value.asrRequested)
                for (phrase in listOf("你好$name", "$name$name", "${name}你好", "在吗$name", "${name}在吗",
                    "可以说话了$name", "说话$name", "${name}说话")) {
                    asr.onCommand(AsrCommand.STOP_REPLY)
                    publish.invoke(service, KwsRepository.snapshot.value)
                    assertFalse(KwsRepository.snapshot.value.asrRequested)
                    sound.clearPlayed()
                    keyword.invoke(service, 1L, phrase, name)
                    assertCue(phrase, name)
                    assertTrue(KwsRepository.snapshot.value.asrRequested)
                    assertEquals(KwsCommand.RESUME, KwsRepository.snapshot.value.command)
                    assertFalse(KwsRepository.isUiVisible.value)
                    assertNull(shadowOf(service).nextStartedActivity)
                    assertEquals(1L, field("activeRun").getLong(service))
                    conversation.onAsrResult(AsrSnapshot(confirmedVersion = ++confirmedVersion, confirmedText = "后台问题"))
                    runCurrent()
                    assertTrue(conversation.hasActiveReply)
                    assertNull(shadowOf(service).nextStartedActivity)
                }
            }
        } finally {
            controller.destroy()
            preferences.assistantName = "豆包"
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }

    @Test fun requestEndAndReceiveTimeoutContinueAsrWithoutCancellingTheReply() = runTest {
        ConversationRepository.reset()
        AsrRepository.clear()
        val controller = Robolectric.buildService(KwsService::class.java)
        val service = controller.get()
        var cancelled = false
        val conversation = ConversationCoordinator(backgroundScope, service::getString, judgeOperation = { true }) { _, _ -> flow {
            try {
                emit(ResponseProgress("回复正在生成"))
                awaitCancellation()
            } finally { cancelled = true }
        } }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val delegate = service.javaClass.declaredFields.first { it.name.startsWith("conversation") }.apply { isAccessible = true }
        delegate.set(service, lazyOf(conversation))
        val asr = field("asr").get(service) as AsrController
        val publish = service.javaClass.getDeclaredMethod("publish", KwsSnapshot::class.java).apply { isAccessible = true }
        val receive = service.javaClass.getDeclaredMethod("onRecognition", AsrSnapshot::class.java).apply { isAccessible = true }
        try {
            field("historyReady").setBoolean(service, true)
            KwsRepository.setUiVisible(true)
            asr.start(true)
            publish.invoke(service, KwsSnapshot(status = ListeningStatus.LISTENING))
            val final = AsrSnapshot(status = AsrStatus.REQUEST_ENDED, confirmedVersion = 1, confirmedText = "问题")
            receive.invoke(service, final)
            runCurrent()
            val messages = ConversationRepository.snapshot.value.content.messages
            for (status in listOf(AsrStatus.REQUEST_ENDED, AsrStatus.RECEIVE_TIMEOUT, AsrStatus.RECEIVE_TIMEOUT)) {
                val requestBefore = field("asrRequest").get(service)
                receive.invoke(service, final.copy(status = status))
                runCurrent()
                assertNotEquals(requestBefore, field("asrRequest").get(service))
                assertEquals(AssistantMode.AWAKE, asr.mode)
                assertTrue(KwsRepository.snapshot.value.asrRequested)
                assertEquals(AsrStatus.STARTING, AsrRepository.snapshot.value.status)
                assertNull(AsrRepository.snapshot.value.error)
                assertFalse(cancelled)
                assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
            }
            val failure = service.getString(R.string.asr_request_failed)
            receive.invoke(service, AsrSnapshot(status = AsrStatus.ERROR, error = failure))
            runCurrent()
            assertTrue(cancelled)
            assertFalse(asr.asrRequested)
            assertEquals(failure, AsrRepository.snapshot.value.error)
            assertNotEquals(service.getString(R.string.asr_failed), failure)
        } finally {
            controller.destroy()
            ConversationRepository.reset()
            AsrRepository.clear()
            KwsRepository.publish(KwsSnapshot())
            KwsRepository.setUiVisible(false)
        }
    }
}
