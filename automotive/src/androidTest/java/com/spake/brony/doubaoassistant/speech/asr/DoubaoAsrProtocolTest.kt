package com.spake.brony.doubaoassistant.speech.asr

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines as Sdk
import com.bytedance.speech.speechengine.net.ws.IWsClient
import com.bytedance.speech.speechengine.net.ws.IWsListener
import com.bytedance.speech.speechengine.net.ws.WsConnectionConfig
import com.spake.brony.doubaoassistant.speech.SpeechSdkEnvironment
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoubaoAsrProtocolTest {
    @Test fun seedAsrFirstRequestUsesVersionOneBinaryHeader() {
        verifyPackets(false)
    }

    @Test fun fullConfigurationAndLegacyAuthUseBinaryPackets() {
        verifyPackets(true)
    }

    private fun verifyPackets(fullConfiguration: Boolean) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val outbound = LinkedBlockingQueue<ByteArray>(8)
        val handler = Handler(Looper.getMainLooper())
        val transport = object : IWsClient {
            private lateinit var listener: IWsListener
            override fun init(listener: IWsListener): Boolean { this.listener = listener; return true }
            override fun startConnection(config: WsConnectionConfig): Boolean {
                handler.post { listener.onConnected("local protocol test") }
                return true
            }
            override fun send(data: ByteArray): Boolean = outbound.offer(data.copyOf())
            override fun stopConnection(): Boolean { handler.removeCallbacksAndMessages(null); return true }
        }
        SpeechSdkEnvironment.prepare(application)
        val engine = SpeechEngineGenerator.getInstance()
        try {
            SpeechSdkEnvironment.checkCreatedEngine(engine.createEngine())
            engine.setContext(application)
            engine.setWsClient(transport) // 本地截获 SDK 数据；不联网，不打开麦克风，不使用真实凭证。
            engine.setOptionString(Sdk.PARAMS_KEY_ENGINE_NAME_STRING, Sdk.ASR_ENGINE)
            engine.setOptionString(Sdk.PARAMS_KEY_LOG_LEVEL_STRING, Sdk.LOG_LEVEL_WARN)
            engine.setOptionString(Sdk.PARAMS_KEY_DEBUG_PATH_STRING, "")
            if (fullConfiguration) {
                engine.setOptionString(Sdk.PARAMS_KEY_APP_ID_STRING, "123456789")
                engine.setOptionString(Sdk.PARAMS_KEY_APP_TOKEN_STRING, "local-protocol-test")
                engine.setOptionInt(Sdk.PARAMS_KEY_UP_CHANNEL_NUM_INT, 1)
                engine.setOptionInt(Sdk.PARAMS_KEY_VAD_MAX_SPEECH_DURATION_INT, 60000)
                engine.setOptionBoolean(Sdk.PARAMS_KEY_ASR_SHOW_UTTER_BOOL, true)
                engine.setOptionString(Sdk.PARAMS_KEY_ASR_REQ_PARAMS_STRING,
                    "{\"model_name\":\"bigmodel\",\"enable_itn\":true,\"enable_punc\":true,\"show_utterances\":true,\"result_type\":\"full\",\"end_window_size\":1200,\"force_to_speech_time\":1}")
            } else engine.setOptionString(Sdk.PARAMS_KEY_API_KEY_STRING, "local-protocol-test")
            engine.setOptionString(Sdk.PARAMS_KEY_RESOURCE_ID_STRING, "volc.seedasr.sauc.duration")
            engine.setOptionString(Sdk.PARAMS_KEY_ASR_ADDRESS_STRING, "wss://openspeech.bytedance.com")
            engine.setOptionString(Sdk.PARAMS_KEY_ASR_URI_STRING, "/api/v3/sauc/bigmodel_async")
            engine.setOptionInt(Sdk.PARAMS_KEY_PROTOCOL_TYPE_INT, Sdk.PROTOCOL_TYPE_SEED)
            engine.setOptionString(Sdk.PARAMS_KEY_RECORDER_TYPE_STRING, Sdk.RECORDER_TYPE_STREAM)
            engine.setOptionInt(Sdk.PARAMS_KEY_SAMPLE_RATE_INT, 16000)
            engine.setOptionInt(Sdk.PARAMS_KEY_CHANNEL_NUM_INT, 1)
            SpeechSdkEnvironment.checkCode(engine.initEngine(), "initEngine()")
            SpeechSdkEnvironment.checkCode(engine.sendDirective(Sdk.DIRECTIVE_SYNC_STOP_ENGINE, ""), "SYNC_STOP_ENGINE")
            engine.setListener(object : SpeechEngine.SpeechListener {
                override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
                    if (type == Sdk.MESSAGE_TYPE_ENGINE_ERROR && data != null && len in 0..data.size)
                        Log.e("DoubaoProtocolTest", "local SDK test error: ${String(data, 0, len, Charsets.UTF_8)}")
                }
            })
            SpeechSdkEnvironment.checkCode(engine.sendDirective(Sdk.DIRECTIVE_START_ENGINE, ""), "START_ENGINE")
            SpeechSdkEnvironment.checkCode(engine.feedAudio(ByteArray(3200), 3200), "feedAudio()")
            val first = outbound.poll(5, TimeUnit.SECONDS)
            assertNotNull("SDK did not send an initial request", first)
            val header = first!!.take(4).joinToString(" ") { "%02x".format(it.toInt() and 255) }
            Log.i("DoubaoProtocolTest", "sdk=${engine.getVersion()} firstHeader=$header")
            assertEquals("ASR request must start with binary version=1, headerSize=1; header=$header",
                0x11, first[0].toInt() and 255)
            repeat(5) {
                SpeechSdkEnvironment.checkCode(engine.feedAudio(ByteArray(3200), 3200), "feedAudio()")
                Thread.sleep(100)
            }
            val audio = outbound.poll(5, TimeUnit.SECONDS)
            assertNotNull("SDK did not send audio", audio)
            val audioHeader = audio!!.take(4).joinToString(" ") { "%02x".format(it.toInt() and 255) }
            Log.i("DoubaoProtocolTest", "fullConfig=$fullConfiguration audioHeader=$audioHeader")
            assertEquals("Audio must also use binary version=1, headerSize=1; header=$audioHeader",
                0x11, audio[0].toInt() and 255)
        } finally {
            handler.removeCallbacksAndMessages(null)
            engine.destroyEngine()
        }
    }
}
