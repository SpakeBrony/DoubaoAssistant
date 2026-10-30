package com.spake.brony.doubaoassistant.speech.tts

import android.app.Application
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines as Sdk
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.BuildConfig
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.TtsProgress
import com.spake.brony.doubaoassistant.speech.SpeechSdkEnvironment
import com.spake.brony.doubaoassistant.speech.network.DoubaoBinaryWsClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.spake.brony.doubaoassistant.speech.audio.TtsAudioTrackPlayer
import com.spake.brony.doubaoassistant.speech.audio.VoiceAudioPlayer
import org.json.JSONObject

// 单独的 TTS 工作线程；不阻塞录音，不在 SDK 回调线程执行停止/销毁。
internal class DoubaoTtsPlayer(
    private val application: Application,
    private val onUpdate: (Long, TtsProgress) -> Unit,
) : StreamingTts, AutoCloseable {
    private data class Input(val version: Long, val speed: Int, val text: String = "", val finished: Boolean = false)
    private class Inbox {
        val synthesized = AtomicBoolean(false)
        val hasAudio = AtomicBoolean(false)
        val playbackCompleted = AtomicBoolean(false)
        val failure = AtomicReference<String?>()
    }
    private class Run(val version: Long, val engine: SpeechEngine, val inbox: Inbox) {
        var sessionRequested = false
        var sent = 0
        var finishSent = false
        var focus: AudioFocusRequest? = null
        var playback: TtsAudioTrackPlayer? = null
        var deadline: Job? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val playbackAttributes = VoiceAudioPlayer.playbackAttributes()
    @Volatile private var activePlayback: TtsAudioTrackPlayer? = null
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var desired: Input? = null
    @Volatile private var closed = false
    private var run: Run? = null
    private var failedVersion = -1L

    init {
        scope.launch {
            try {
                for (signal in signals) {
                    try { reconcile() }
                    catch (e: Exception) {
                        Log.e("DoubaoTts", redact(e.toString()))
                        fail(application.getString(R.string.tts_failed))
                    }
                    catch (e: LinkageError) {
                        Log.e("DoubaoTts", redact(e.toString()))
                        fail(application.getString(R.string.tts_failed))
                    }
                }
            } finally {
                try { dispose() }
                finally { scope.cancel(); dispatcher.close() }
            }
        }
    }

    // 以下四个入口仅由服务主线程调用；累计文字只有一份，唤醒信号不积压 token/音频。
    override fun start(version: Long, speed: Int) {
        if (closed) return
        if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=$version start speed=$speed")
        activePlayback?.close()
        desired = Input(version, speed)
        signals.trySend(Unit)
    }

    override fun append(delta: String) {
        val input = desired ?: return
        if (closed || input.finished || delta.isEmpty()) return
        require(input.text.length + delta.length <= 32768)
        desired = input.copy(text = input.text + delta)
        signals.trySend(Unit)
    }

    override fun finish() {
        desired = desired?.copy(finished = true)
        signals.trySend(Unit)
    }

    override fun cancel() {
        if (BuildConfig.DEBUG) desired?.let { Log.i("DoubaoTts", "reply=${it.version} cancel textChars=${it.text.length}") }
        desired = null // 在处理停止指令前，旧回调及排队文字已经失效。
        activePlayback?.close()
        signals.trySend(Unit)
    }

    private fun reconcile() {
        val input = desired
        if (run?.version != input?.version) dispose()
        if (input == null || closed || failedVersion == input.version) return
        if (run == null) create(input)
        val current = run ?: return
        if (desired?.version != current.version) return
        val inbox = current.inbox
        inbox.failure.getAndSet(null)?.let { fail(it); return }
        // SDK 按指令顺序排队；不能等未返回的 ConnectionStarted 才提交 StartSession。
        if (!current.sessionRequested && input.text.isNotEmpty()) {
            current.sessionRequested = true
            SpeechSdkEnvironment.checkCode(current.engine.sendDirective(Sdk.DIRECTIVE_EVENT_START_SESSION, ""), "EVENT_START_SESSION")
            if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${current.version} sent START_SESSION")
        }
        if (current.sessionRequested) {
            if (input.text.length > current.sent) {
                if (current.focus == null) acquireFocus(current)
                val delta = input.text.substring(current.sent)
                SpeechSdkEnvironment.checkCode(current.engine.sendDirective(Sdk.DIRECTIVE_EVENT_TASK_REQUEST, textPayload(delta)), "EVENT_TASK_REQUEST")
                current.sent = input.text.length
                if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${current.version} sent textChars=${delta.length} totalChars=${current.sent}")
            }
            if (input.finished && !current.finishSent) {
                SpeechSdkEnvironment.checkCode(current.engine.sendDirective(Sdk.DIRECTIVE_EVENT_FINISH_SESSION, ""), "EVENT_FINISH_SESSION")
                current.finishSent = true
                if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${current.version} sent FINISH_SESSION")
            }
        }
        // SessionFinished 只表示音频收完，必须等 AudioTrack 真正播完才能释放。
        if (inbox.synthesized.get() && inbox.playbackCompleted.get()) {
            if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${current.version} AudioTrack completed")
            publish(current.version, TtsProgress(completed = true))
            failedVersion = current.version // 同一份累计输入不重新播放。
            dispose()
        }
    }

    private val credentialStore = CredentialStore(application)
    private var credentials = credentialStore.load()

    private fun create(input: Input) {
        credentials = credentialStore.load()
        check(credentials.speechReady) {
            application.getString(R.string.tts_credentials_missing)
        }
        SpeechSdkEnvironment.prepare(application)
        val engine = SpeechEngineGenerator.getInstance()
        val inbox = Inbox()
        val current = Run(input.version, engine, inbox)
        run = current
        SpeechSdkEnvironment.checkCreatedEngine(engine.createEngine())
        if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${input.version} sdk=${engine.getVersion()} engine=BITTS playback=AudioTrack usage=ASSISTANT")
        engine.setContext(application.applicationContext)
        engine.setWsClient(DoubaoBinaryWsClient())
        engine.setOptionString(Sdk.PARAMS_KEY_ENGINE_NAME_STRING, Sdk.BITTS_ENGINE)
        engine.setOptionString(Sdk.PARAMS_KEY_DEBUG_PATH_STRING, "")
        engine.setOptionString(Sdk.PARAMS_KEY_LOG_LEVEL_STRING, Sdk.LOG_LEVEL_WARN)
        engine.setOptionString(Sdk.PARAMS_KEY_APP_ID_STRING, credentials.appId)
        engine.setOptionString(Sdk.PARAMS_KEY_APP_TOKEN_STRING, credentials.accessToken)
        engine.setOptionString(Sdk.PARAMS_KEY_TTS_ADDRESS_STRING, "wss://openspeech.bytedance.com")
        engine.setOptionString(Sdk.PARAMS_KEY_TTS_URI_STRING, "/api/v3/tts/bidirection")
        engine.setOptionString(Sdk.PARAMS_KEY_RESOURCE_ID_STRING, BuildConfig.DOUBAO_TTS_RESOURCE_ID)
        val payload = sessionPayload(input.speed)
        engine.setOptionString(Sdk.PARAMS_KEY_START_ENGINE_PAYLOAD_STRING, payload)
        engine.setOptionInt(Sdk.PARAMS_KEY_TTS_CONN_TIMEOUT_INT, 10000)
        engine.setOptionInt(Sdk.PARAMS_KEY_TTS_RECV_TIMEOUT_INT, 15000)
        // SDK 只合成，网络返回 Ogg/Opus 由系统流式解码成 PCM，再交给 AudioTrack。
        engine.setOptionBoolean(Sdk.PARAMS_KEY_TTS_ENABLE_PLAYER_BOOL, false)
        engine.setOptionBoolean(Sdk.PARAMS_KEY_TTS_ENABLE_DUMP_BOOL, false)
        engine.setOptionBoolean(Sdk.PARAMS_KEY_ENABLE_PLAYER_AUDIO_CALLBACK_BOOL, false)
        engine.setOptionInt(Sdk.PARAMS_KEY_TTS_SAMPLE_RATE_INT, 48000)
        SpeechSdkEnvironment.checkCode(engine.initEngine(), "initEngine()")
        SpeechSdkEnvironment.checkCode(engine.sendDirective(Sdk.DIRECTIVE_SYNC_STOP_ENGINE, ""), "SYNC_STOP_ENGINE")
        current.playback = TtsAudioTrackPlayer(onComplete = {
            inbox.playbackCompleted.set(true)
            signals.trySend(Unit)
        }, onError = { error ->
            Log.e("DoubaoTts", "AudioTrack playback failed: ${redact(error.toString())}")
            inbox.failure.set(application.getString(R.string.tts_failed))
            signals.trySend(Unit)
        })
        activePlayback = current.playback
        if (closed || desired?.version != current.version) current.playback?.close()
        engine.setListener(object : SpeechEngine.SpeechListener {
            override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
                if (closed || desired?.version != current.version) return
                // 音频只记录首包长度；其余回调只记录编号，不输出音频、正文或凭证。
                if (BuildConfig.DEBUG && type != Sdk.MESSAGE_TYPE_PLAYER_AUDIO_DATA &&
                    type != Sdk.MESSAGE_TYPE_EVENT_TTS_RESPONSE)
                    Log.i("DoubaoTts", "reply=${current.version} callback=$type len=$len")
                when (type) {
                    Sdk.MESSAGE_TYPE_EVENT_TTS_RESPONSE -> {
                        try {
                            if (len == 0) return
                            current.playback?.append(checkNotNull(data), len)
                            if (!inbox.hasAudio.getAndSet(true) && BuildConfig.DEBUG)
                                Log.i("DoubaoTts", "reply=${current.version} first encoded audio len=$len")
                        } catch (error: Exception) {
                            Log.e("DoubaoTts", "Invalid audio callback: ${redact(error.toString())}")
                            current.playback?.close()
                            inbox.failure.set(application.getString(R.string.tts_failed))
                            signals.trySend(Unit)
                        }
                        return
                    }
                    Sdk.MESSAGE_TYPE_EVENT_SESSION_FINISHED -> {
                        inbox.synthesized.set(true)
                        current.playback?.finish()
                    }
                    Sdk.MESSAGE_TYPE_ENGINE_ERROR, Sdk.MESSAGE_TYPE_EVENT_CONNECTION_FAILED,
                    Sdk.MESSAGE_TYPE_EVENT_SESSION_FAILED -> {
                        val message = if (data != null && len in 0..data.size && len <= 65536)
                            String(data, 0, len, Charsets.UTF_8)
                        else "SDK 回调数据无效：type=$type len=$len"
                        Log.e("DoubaoTts", "SDK 错误回调：${redact(message)}")
                        inbox.failure.set(application.getString(R.string.tts_failed))
                    }
                    else -> return
                }
                signals.trySend(Unit)
            }
            override fun onSpeechLogid(logid: String?) {
                if (!closed && desired?.version == current.version)
                    Log.i("DoubaoTts", "reply=${current.version} logid=$logid")
            }
        })
        current.deadline = scope.launch {
            delay(300000)
            Log.e("DoubaoTts", "Local playback timeout: 300000 ms")
            inbox.failure.set(application.getString(R.string.tts_timeout))
            signals.trySend(Unit)
        }
        SpeechSdkEnvironment.checkCode(engine.sendDirective(Sdk.DIRECTIVE_START_ENGINE, payload), "START_ENGINE")
    }

    private fun acquireFocus(current: Run) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .setOnAudioFocusChangeListener({ focus ->
                if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    current.playback?.close()
                    current.inbox.failure.set(application.getString(R.string.tts_audio_focus_lost))
                    signals.trySend(Unit)
                }
            }, main).build()
        current.focus = request
        val result = audioManager.requestAudioFocus(request)
        if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${current.version} audioFocus=$result playbackUsage=ASSISTANT mode=${audioManager.mode}")
        check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Android requestAudioFocus() 返回值：$result" }
    }

    private fun fail(message: String) {
        val version = run?.version ?: desired?.version ?: return
        failedVersion = version
        publish(version, TtsProgress(error = redact(message)))
        dispose()
    }

    private fun publish(version: Long, progress: TtsProgress) {
        main.post { if (!closed && desired?.version == version) onUpdate(version, progress) }
    }

    private fun redact(message: String): String = credentials.redact(message)

    private fun dispose() {
        val previous = run ?: return
        run = null
        if (BuildConfig.DEBUG) Log.i("DoubaoTts", "reply=${previous.version} dispose synthesized=${previous.inbox.synthesized.get()} playbackCompleted=${previous.inbox.playbackCompleted.get()}")
        previous.deadline?.cancel()
        previous.playback?.close()
        if (activePlayback === previous.playback) activePlayback = null
        try {
            previous.engine.setListener(null)
            if (previous.sessionRequested && !previous.inbox.synthesized.get())
                previous.engine.sendDirective(Sdk.DIRECTIVE_EVENT_CANCLE_SESSION, "")
            SpeechSdkEnvironment.checkCode(previous.engine.sendDirective(Sdk.DIRECTIVE_SYNC_STOP_ENGINE, ""), "SYNC_STOP_ENGINE")
        } catch (e: Exception) { Log.w("DoubaoTts", "Stop playback: ${redact(e.toString())}") }
        catch (e: LinkageError) { Log.w("DoubaoTts", "Stop playback: ${redact(e.toString())}") }
        finally {
            try { previous.engine.destroyEngine() }
            catch (e: Exception) { Log.w("DoubaoTts", "destroyEngine(): ${redact(e.toString())}") }
            catch (e: LinkageError) { Log.w("DoubaoTts", "destroyEngine(): ${redact(e.toString())}") }
            finally { previous.focus?.let { audioManager.abandonAudioFocusRequest(it) } }
        }
    }

    override fun close() {
        closed = true
        desired = null
        activePlayback?.close()
        signals.close()
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        internal fun sessionPayload(speed: Int): String = JSONObject().apply {
            put("user", JSONObject().put("uid", "doubao-assistant"))
            put("req_params", JSONObject().put("speaker", BuildConfig.DOUBAO_TTS_SPEAKER)
                .put("audio_params", JSONObject().put("format", "ogg_opus").put("sample_rate", 48000)
                    .put("speech_rate", when (speed.coerceIn(0, 2)) { 0 -> -20; 2 -> 20; else -> 0 })))
        }.toString()

        internal fun textPayload(delta: String): String = JSONObject().put("req_params", JSONObject().put("text", delta)).toString()
    }
}
