package com.spake.brony.doubaoassistant.speech.asr

import android.app.Application
import com.bytedance.speech.speechengine.SpeechEngine
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import com.spake.brony.doubaoassistant.data.repository.TestCredentialKeys
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoubaoAsrRecognizerTest {
    @Test fun cleanupErrorsRemainDiagnosableWithoutLoggingCredentials() {
        TestCredentialKeys.install()
        val runtime = ServiceCredentials(appId = "test-app-id", accessToken = "test-runtime-token", arkApiKey = "test-runtime-ark")
        check(CredentialStore(RuntimeEnvironment.getApplication()).save(runtime))
        val keys = listOf(runtime.appId, runtime.accessToken, runtime.arkApiKey)
        val engine = Proxy.newProxyInstance(SpeechEngine::class.java.classLoader,
            arrayOf(SpeechEngine::class.java)) { _, method, _ ->
            throw IllegalStateException(method.name + ": " + keys.joinToString(" "))
        } as SpeechEngine
        val recognizer = DoubaoAsrRecognizer(RuntimeEnvironment.getApplication(), ConversationContent()) { _, _ -> }
        recognizer.javaClass.getDeclaredField("engine").apply { isAccessible = true }.set(recognizer, engine)
        ShadowLog.clear()
        recognizer.close()
        val logs = ShadowLog.getLogsForTag("DoubaoAsr")
        assertEquals(2, logs.size)
        assertTrue(logs.any { it.msg.contains("setListener(null)") })
        assertTrue(logs.any { it.msg.contains("destroyEngine()") })
        assertTrue(logs.all { it.msg.contains("IllegalStateException") })
        assertTrue(logs.all { log -> keys.none { log.msg.contains(it) } })
    }

    @Test fun newTopicDiscardsOldTranscriptAndInboxButNormalRotationKeepsNewConfirmedSpeech() {
        val application: Application = RuntimeEnvironment.getApplication()
        val updates = mutableListOf<AsrSnapshot>()
        val recognizer = DoubaoAsrRecognizer(application, ConversationContent()) { _, snapshot -> updates += snapshot }
        fun field(name: String) = recognizer.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val pcm = ShortArray(1600)
        try {
            recognizer.process(pcm, pcm.size, wanted = false, generation = 1)
            val old = field("transcript").get(recognizer) as AsrTranscript
            old.accept("""{"result":{"utterances":[{"text":"旧话题","end_time":1500,"definite":true}]}}""", false)
            assertEquals(1L, old.confirmedVersion)
            val oldInbox = field("inbox").get(recognizer)
            recognizer.process(pcm, pcm.size, wanted = false, generation = 2, topicGeneration = 1)
            val fresh = field("transcript").get(recognizer) as AsrTranscript
            assertNotSame(old, fresh)
            assertNotSame(oldInbox, field("inbox").get(recognizer))
            assertEquals(0L, fresh.confirmedVersion)
            assertEquals("", fresh.confirmedText)
            assertTrue(updates.last().conversation.messages.isEmpty())
            assertEquals(0L, updates.last().confirmedVersion)
            fresh.accept("""{"result":{"utterances":[{"text":"新话题","end_time":1500,"definite":true}]}}""", false)
            recognizer.process(pcm, pcm.size, wanted = false, generation = 3, topicGeneration = 1)
            assertSame(fresh, field("transcript").get(recognizer))
            assertEquals(1L, updates.last().confirmedVersion)
            assertEquals("新话题", updates.last().confirmedText)
        } finally { recognizer.close() }
    }

    @Test fun disablingAsrReleasesTheEngineAndDoesNotUploadAnyMoreAudio() {
        val application: Application = RuntimeEnvironment.getApplication()
        val updates = mutableListOf<AsrSnapshot>()
        val recognizer = DoubaoAsrRecognizer(application, ConversationContent()) { _, snapshot -> updates += snapshot }
        var destroyed = 0
        var fed = 0
        val engine = Proxy.newProxyInstance(SpeechEngine::class.java.classLoader,
            arrayOf(SpeechEngine::class.java)) { _, method, _ ->
            when (method.name) {
                "setListener" -> null
                "destroyEngine" -> { destroyed++; null }
                "feedAudio" -> { fed++; 0 }
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as SpeechEngine
        val engineField = recognizer.javaClass.getDeclaredField("engine").apply { isAccessible = true }
        val pcm = ShortArray(1600)
        try {
            recognizer.process(pcm, pcm.size, wanted = false, generation = 1)
            engineField.set(recognizer, engine)
            repeat(3) { recognizer.process(pcm, pcm.size, wanted = false, generation = 1) }
            assertEquals(1, destroyed)
            assertEquals(0, fed)
            assertNull(engineField.get(recognizer))
            assertEquals(AsrStatus.IDLE, updates.last().status)
        } finally { recognizer.close() }
    }

    @Test fun actualSdkFailureWaitsForExplicitResume() {
        verifyEnd(AsrStatus.ERROR)
    }

    @Test fun completedRequestRequestsContinuationInsteadOfReportingSilence() {
        verifyEnd(AsrStatus.REQUEST_ENDED)
    }

    @Test fun receiveTimeoutIsDistinctFromRecognitionFailure() {
        verifyEnd(AsrStatus.RECEIVE_TIMEOUT)
        assertTrue(DoubaoAsrRecognizer.isReceiveTimeout("""{"err_code":4001,"err_msg":"Recv timeout."}"""))
        assertFalse(DoubaoAsrRecognizer.isReceiveTimeout("""{"err_code":45000000}"""))
        assertFalse(DoubaoAsrRecognizer.isReceiveTimeout("invalid"))
    }

    @Test fun confirmedUtteranceIsDeliveredBeforeRequestRotation() {
        verifyEnd(AsrStatus.REQUEST_ENDED, confirmed = true)
    }

    private fun verifyEnd(status: AsrStatus, confirmed: Boolean = false) {
        val application: Application = RuntimeEnvironment.getApplication()
        val updates = mutableListOf<AsrSnapshot>()
        val recognizer = DoubaoAsrRecognizer(application, ConversationContent()) { _, snapshot -> updates += snapshot }
        var destroyed = 0
        var fed = 0
        fun fakeEngine() = Proxy.newProxyInstance(SpeechEngine::class.java.classLoader,
            arrayOf(SpeechEngine::class.java)) { _, method, _ ->
            when (method.name) {
                "setListener" -> null
                "destroyEngine" -> { destroyed++; null }
                "feedAudio" -> { fed++; 0 }
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as SpeechEngine
        fun field(name: String) = recognizer.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val pcm = ShortArray(1600)
        try {
            recognizer.process(pcm, pcm.size, wanted = false, generation = 1)
            field("engine").set(recognizer, fakeEngine()) // 无原生 SDK、网络或麦克风。
            val inbox = field("inbox").get(recognizer)
            if (confirmed) {
                val resultClass = recognizer.javaClass.declaredClasses.first { it.simpleName == "Result" }
                val json = """{"result":{"utterances":[{"text":"确认的问题","end_time":1500,"definite":true}]}}"""
                val result = resultClass.getDeclaredConstructor(String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(json, false)
                @Suppress("UNCHECKED_CAST")
                val pending = inbox.javaClass.getDeclaredField("result").apply { isAccessible = true }.get(inbox) as AtomicReference<Any?>
                pending.set(result)
            } else if (status != AsrStatus.ERROR) {
                val flag = if (status == AsrStatus.RECEIVE_TIMEOUT) "receiveTimeout" else "ended"
                (inbox.javaClass.getDeclaredField(flag).apply { isAccessible = true }.get(inbox) as AtomicBoolean).set(true)
            } else {
                @Suppress("UNCHECKED_CAST")
                val failure = inbox.javaClass.getDeclaredField("failure").apply { isAccessible = true }.get(inbox) as AtomicReference<String?>
                failure.set(application.getString(R.string.asr_request_failed))
            }
            recognizer.process(pcm, pcm.size, wanted = true, generation = 1)
            assertEquals(1, destroyed)
            assertNull(field("engine").get(recognizer))
            assertNotSame(inbox, field("inbox").get(recognizer))
            assertTrue(field("failed").getBoolean(recognizer))
            assertEquals(status, updates.last().status)
            if (status == AsrStatus.ERROR) assertEquals(application.getString(R.string.asr_request_failed), updates.last().error)
            else assertNull(updates.last().error)
            if (confirmed) {
                assertEquals(1L, updates.last().confirmedVersion)
                assertEquals("确认的问题", updates.last().confirmedText)
            }
            val updatesAfterFailure = updates.size
            repeat(3) { recognizer.process(pcm, pcm.size, wanted = true, generation = 1) }
            assertEquals(0, fed)
            assertEquals(1, destroyed)
            assertNull(field("engine").get(recognizer))
            assertEquals(updatesAfterFailure, updates.size)
            recognizer.process(pcm, pcm.size, wanted = false, generation = 2)
            assertFalse(field("failed").getBoolean(recognizer))
            field("engine").set(recognizer, fakeEngine()) // 不调用真实 SDK 或云端。
            recognizer.process(pcm, pcm.size, wanted = true, generation = 2)
            assertEquals(1, fed)
        } finally { recognizer.close() }
    }
}
