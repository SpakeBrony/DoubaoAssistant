package com.spake.brony.doubaoassistant.speech.asr

import android.app.Application
import android.util.Log
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.BuildConfig
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.speech.SpeechSdkEnvironment
import com.spake.brony.doubaoassistant.speech.network.DoubaoBinaryWsClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.bytedance.speech.speechengine.SpeechEngineDefines as Sdk
import org.json.JSONObject


// 所有引擎操作和 feedAudio 只在现有录音工作线程执行；SDK 回调仅替换有上限的待处理数据。
internal class DoubaoAsrRecognizer(
    private val application: Application,
    initial: ConversationContent,
    private val onUpdate: (Long, AsrSnapshot) -> Unit,
) : AutoCloseable {
    private data class Result(val json: String, val final: Boolean)
    private class Inbox {
        val result = AtomicReference<Result?>()
        val failure = AtomicReference<String?>()
        val ended = AtomicBoolean(false)
        val receiveTimeout = AtomicBoolean(false)
        val connected = AtomicBoolean(false)
    }
    private var inbox = Inbox()
    private var transcript = AsrTranscript(initial)
    private var transcriptGeneration = 0L
    private var engine: SpeechEngine? = null
    private var requestGeneration = -1L
    private var failed = false
    private var lastSnapshot: AsrSnapshot? = null
    @Volatile private var session = 0L
    private val bytes = ByteArray(3200)

    fun process(pcm: ShortArray, count: Int, wanted: Boolean, generation: Long, topicGeneration: Long = 0) {
        if (requestGeneration != generation || transcriptGeneration != topicGeneration) {
            close()
            if (transcriptGeneration != topicGeneration) {
                // 新话题清空旧历史和序号；普通 ASR 轮换保留单调序号。
                transcript = AsrTranscript()
                transcriptGeneration = topicGeneration
            }
            requestGeneration = generation
            failed = false
            lastSnapshot = null
            // 上一句已随旧代次交付，重开只保留单调序号，不能再次带出旧文字。
            transcript.clearPendingText()
        }
        if (!wanted) {
            if (engine != null) close()
            emit(AsrStatus.IDLE)
            return
        }
        if (failed) return
        try {
            if (engine == null) start()
            inbox.failure.getAndSet(null)?.let { fail(it); return }
            val previousVersion = transcript.confirmedVersion
            inbox.result.getAndSet(null)?.let { transcript.accept(it.json, it.final) }
            if (inbox.receiveTimeout.get() || inbox.ended.get() ||
                (transcript.confirmedVersion > previousVersion && transcript.content.liveText.isEmpty())) {
                val status = if (inbox.receiveTimeout.get()) AsrStatus.RECEIVE_TIMEOUT else AsrStatus.REQUEST_ENDED
                close()
                failed = true // 等服务决定接续；不能在每块 PCM 到来时自行重试。
                emit(status) // 先交付最后一句，保留 confirmedVersion，避免接续时丢句或重复提交。
                return
            }
            require(count in 1..1600)
            for (index in 0 until count) {
                val sample = pcm[index].toInt()
                bytes[index * 2] = sample.toByte()
                bytes[index * 2 + 1] = (sample shr 8).toByte()
            }
            SpeechSdkEnvironment.checkCode(engine!!.feedAudio(bytes, count * 2), "feedAudio()")
            emit(if (inbox.connected.get()) AsrStatus.LISTENING else AsrStatus.STARTING)
        } catch (e: Exception) {
            Log.e("DoubaoAsr", redact(e.toString()))
            fail(application.getString(R.string.asr_request_failed))
        } catch (e: LinkageError) {
            Log.e("DoubaoAsr", redact(e.toString()))
            fail(application.getString(R.string.asr_request_failed))
        }
    }

    private val credentialStore = CredentialStore(application)
    private var credentials = credentialStore.load()

    private fun start() {
        credentials = credentialStore.load()
        check(credentials.speechReady) {
            application.getString(R.string.asr_credentials_missing)
        }
        SpeechSdkEnvironment.prepare(application)
        val next = SpeechEngineGenerator.getInstance()
        engine = next
        SpeechSdkEnvironment.checkCreatedEngine(next.createEngine())
        next.setContext(application.applicationContext)
        next.setWsClient(DoubaoBinaryWsClient())
        next.setOptionString(Sdk.PARAMS_KEY_ENGINE_NAME_STRING, Sdk.ASR_ENGINE)
        next.setOptionString(Sdk.PARAMS_KEY_LOG_LEVEL_STRING, Sdk.LOG_LEVEL_WARN)
        next.setOptionString(Sdk.PARAMS_KEY_DEBUG_PATH_STRING, "")
        next.setOptionString(Sdk.PARAMS_KEY_APP_ID_STRING, credentials.appId)
        next.setOptionString(Sdk.PARAMS_KEY_APP_TOKEN_STRING, credentials.accessToken)
        next.setOptionString(Sdk.PARAMS_KEY_RESOURCE_ID_STRING, BuildConfig.DOUBAO_ASR_RESOURCE_ID)
        next.setOptionString(Sdk.PARAMS_KEY_ASR_ADDRESS_STRING, "wss://openspeech.bytedance.com")
        next.setOptionString(Sdk.PARAMS_KEY_ASR_URI_STRING, "/api/v3/sauc/bigmodel_async")
        next.setOptionInt(Sdk.PARAMS_KEY_PROTOCOL_TYPE_INT, Sdk.PROTOCOL_TYPE_SEED)
        next.setOptionString(Sdk.PARAMS_KEY_RECORDER_TYPE_STRING, Sdk.RECORDER_TYPE_STREAM)
        next.setOptionInt(Sdk.PARAMS_KEY_SAMPLE_RATE_INT, 16000)
        next.setOptionInt(Sdk.PARAMS_KEY_CHANNEL_NUM_INT, 1)
        next.setOptionInt(Sdk.PARAMS_KEY_UP_CHANNEL_NUM_INT, 1)
        next.setOptionInt(Sdk.PARAMS_KEY_VAD_MAX_SPEECH_DURATION_INT, 60000)
        // 这是网络接收等待；无人说话的 70 秒暂停由对话层独立计时。
        next.setOptionInt(Sdk.PARAMS_KEY_ASR_RECV_TIMEOUT_INT, 70000)
        next.setOptionBoolean(Sdk.PARAMS_KEY_ASR_SHOW_UTTER_BOOL, true)
        next.setOptionString(Sdk.PARAMS_KEY_ASR_REQ_PARAMS_STRING,
            "{\"model_name\":\"bigmodel\",\"enable_itn\":true,\"enable_punc\":true,\"show_utterances\":true,\"result_type\":\"full\",\"end_window_size\":1200,\"force_to_speech_time\":1}")
        SpeechSdkEnvironment.checkCode(next.initEngine(), "initEngine()")
        SpeechSdkEnvironment.checkCode(next.sendDirective(Sdk.DIRECTIVE_SYNC_STOP_ENGINE, ""), "SYNC_STOP_ENGINE")
        val id = ++session
        val messages = inbox
        next.setListener(object : SpeechEngine.SpeechListener {
            override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
                if (session != id) return
                when (type) {
                    Sdk.MESSAGE_TYPE_CONNECTION_CONNECTED -> messages.connected.set(true)
                    Sdk.MESSAGE_TYPE_ENGINE_STOP -> messages.ended.set(true)
                    Sdk.MESSAGE_TYPE_ENGINE_ERROR -> {
                        val message = decode(data, len, messages)
                        Log.e("DoubaoAsr", "SDK 错误回调：${redact(message)}")
                        if (isReceiveTimeout(message)) messages.receiveTimeout.set(true)
                        else messages.failure.set(application.getString(R.string.asr_request_failed))
                    }
                    Sdk.MESSAGE_TYPE_PARTIAL_RESULT, Sdk.MESSAGE_TYPE_FINAL_RESULT -> {
                        val json = decode(data, len, messages)
                        if (json.isEmpty()) return
                        val incoming = Result(json, type == Sdk.MESSAGE_TYPE_FINAL_RESULT)
                        messages.result.updateAndGet { previous -> if (previous?.final == true) previous else incoming }
                    }
                }
            }
            override fun onSpeechLogid(logid: String?) {
                if (session == id) Log.i("DoubaoAsr", "session=$id logid=$logid")
            }
        })
        transcript.beginRequest()
        emit(AsrStatus.STARTING)
        if (BuildConfig.DEBUG) Log.i("DoubaoAsr",
            "session=$id sdk=${next.getVersion()} protocol=SEED(${Sdk.PROTOCOL_TYPE_SEED}) " +
                "uri=/api/v3/sauc/bigmodel_async resource=${BuildConfig.DOUBAO_ASR_RESOURCE_ID}")
        SpeechSdkEnvironment.checkCode(next.sendDirective(Sdk.DIRECTIVE_START_ENGINE, ""), "START_ENGINE")
    }

    private fun decode(data: ByteArray?, len: Int, messages: Inbox): String {
        if (data == null || len !in 0..data.size || len > 1024 * 1024) {
            Log.e("DoubaoAsr", "Invalid callback length: len=$len size=${data?.size}")
            messages.failure.set(application.getString(R.string.asr_response_invalid))
            return ""
        }
        return String(data, 0, len, Charsets.UTF_8)
    }

    private fun fail(message: String) {
        close()
        failed = true // 等待用户重新开启，不按每块音频无限重试。
        transcript.clearLiveText()
        emit(AsrStatus.ERROR, redact(message))
    }

    private fun redact(message: String): String = credentials.redact(message)

    private fun emit(status: AsrStatus, error: String? = null) {
        val snapshot = AsrSnapshot(status, transcript.content, error, transcript.confirmedVersion, transcript.confirmedText)
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        onUpdate(requestGeneration, snapshot)
    }

    override fun close() {
        ++session // 使已在途的旧回调失效。
        val previous = engine
        engine = null
        inbox = Inbox() // 旧回调即使已通过 session 检查，也只能写入旧信箱。
        try {
            previous?.setListener(null)
        } catch (e: Exception) {
            Log.w("DoubaoAsr", "setListener(null): ${redact(e.toString())}")
        } catch (e: LinkageError) {
            Log.w("DoubaoAsr", "setListener(null): ${redact(e.toString())}")
        } finally {
            try {
                previous?.destroyEngine()
            } catch (e: Exception) {
                Log.w("DoubaoAsr", "destroyEngine(): ${redact(e.toString())}")
            } catch (e: LinkageError) {
                Log.w("DoubaoAsr", "destroyEngine(): ${redact(e.toString())}")
            }
        }
    }

    companion object {
        internal fun isReceiveTimeout(message: String): Boolean =
            runCatching { JSONObject(message).optInt("err_code") == Sdk.CODE_RECEIVE_TIMEOUT }.getOrDefault(false)
    }
}
