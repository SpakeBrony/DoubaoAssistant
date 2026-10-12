package com.spake.brony.doubaoassistant.speech.tts

import android.app.Application
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import com.spake.brony.doubaoassistant.data.repository.TestCredentialKeys
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines as Sdk
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoubaoTtsPlayerTest {
    @Test fun cleanupErrorsRemainDiagnosableWithoutLoggingCredentials() {
        TestCredentialKeys.install()
        val runtime = ServiceCredentials(appId = "test-app-id", accessToken = "test-runtime-token", arkApiKey = "test-runtime-ark")
        check(CredentialStore(RuntimeEnvironment.getApplication()).save(runtime))
        val keys = listOf(runtime.appId, runtime.accessToken, runtime.arkApiKey)
        val engine = Proxy.newProxyInstance(SpeechEngine::class.java.classLoader,
            arrayOf(SpeechEngine::class.java)) { _, method, _ ->
            throw IllegalStateException(method.name + ": " + keys.joinToString(" "))
        } as SpeechEngine
        val player = DoubaoTtsPlayer(RuntimeEnvironment.getApplication()) { _, _ -> }
        try {
            val inboxClass = player.javaClass.declaredClasses.first { it.simpleName == "Inbox" }
            val inbox = inboxClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val runClass = player.javaClass.declaredClasses.first { it.simpleName == "Run" }
            val run = runClass.getDeclaredConstructor(Long::class.javaPrimitiveType, SpeechEngine::class.java, inboxClass)
                .apply { isAccessible = true }.newInstance(1L, engine, inbox)
            player.javaClass.getDeclaredField("run").apply { isAccessible = true }.set(player, run)
            ShadowLog.clear()
            player.javaClass.getDeclaredMethod("dispose").apply { isAccessible = true }.invoke(player)
            val logs = ShadowLog.getLogsForTag("DoubaoTts").filter { it.type == android.util.Log.WARN }
            assertEquals(2, logs.size)
            assertTrue(logs.any { it.msg.contains("Stop playback") })
            assertTrue(logs.any { it.msg.contains("destroyEngine()") })
            assertTrue(logs.all { it.msg.contains("IllegalStateException") })
            assertTrue(logs.all { log -> keys.none { log.msg.contains(it) } })
        } finally { player.close() }
    }

    @Test fun textIsSubmittedInOrderWithoutWaitingForConnectionOrSessionCallbacks() {
        val application: Application = RuntimeEnvironment.getApplication()
        val directives = CopyOnWriteArrayList<Pair<Int, String>>()
        val finished = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val engine = Proxy.newProxyInstance(SpeechEngine::class.java.classLoader, arrayOf(SpeechEngine::class.java)) { _, method, args ->
            when (method.name) {
                "sendDirective" -> {
                    val type = args!![0] as Int
                    directives += type to args[1] as String
                    if (type == Sdk.DIRECTIVE_EVENT_FINISH_SESSION) finished.countDown()
                    0
                }
                "setListener" -> null
                "destroyEngine" -> { destroyed.countDown(); null }
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as SpeechEngine
        val player = DoubaoTtsPlayer(application) { _, _ -> }
        try {
            val inboxClass = player.javaClass.declaredClasses.first { it.simpleName == "Inbox" }
            val inbox = inboxClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val runClass = player.javaClass.declaredClasses.first { it.simpleName == "Run" }
            val run = runClass.getDeclaredConstructor(Long::class.javaPrimitiveType, SpeechEngine::class.java, inboxClass)
                .apply { isAccessible = true }.newInstance(1L, engine, inbox)
            player.javaClass.getDeclaredField("run").apply { isAccessible = true }.set(player, run)
            // 注入已初始化的假引擎，不启动原生 SDK、不联网；故意不发送任何成功回调。
            player.start(1, 1)
            player.append("测试回复。")
            player.finish()
            assertTrue("StartSession must not wait forever for a callback", finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Sdk.DIRECTIVE_EVENT_START_SESSION, Sdk.DIRECTIVE_EVENT_TASK_REQUEST,
                Sdk.DIRECTIVE_EVENT_FINISH_SESSION), directives.map { it.first })
            assertEquals("测试回复。", JSONObject(directives[1].second).getJSONObject("req_params").getString("text"))
        } finally {
            player.close()
            assertTrue(destroyed.await(5, TimeUnit.SECONDS))
        }
    }
}
