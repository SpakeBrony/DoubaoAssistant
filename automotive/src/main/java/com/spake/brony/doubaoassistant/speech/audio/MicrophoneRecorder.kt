package com.spake.brony.doubaoassistant.speech.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.spake.brony.doubaoassistant.R

// start/read/close 属于录音线程；主线程只调用 stop，唤醒阻塞读取，不负责释放。
internal class MicrophoneRecorder(
    private val getString: (Int) -> String,
    private val sampleRate: Int,
) : AutoCloseable {
    private val lock = Any()
    private var stopped = false
    private val effects = mutableListOf<AudioEffect>()
    @Volatile private var recorder: AudioRecord? = null

    val isRecording: Boolean get() = recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    var echoCancellationEnabled = false
        private set

    @SuppressLint("MissingPermission") // Service 在启动前检查 RECORD_AUDIO，权限撤销由录音异常处理。
    fun start() {
        check(recorder == null)
        if (synchronized(lock) { stopped }) return
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { getString(R.string.microphone_unsupported) }
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 2, 6400))
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) { getString(R.string.microphone_unavailable) }
        echoCancellationEnabled = enableEffects(audio.audioSessionId)
        synchronized(lock) {
            // 关麦可能早于初始化完成，旧录音任务不能在 stop 之后重新开麦。
            if (!stopped) {
                audio.startRecording()
                check(isRecording) { getString(R.string.microphone_unavailable) }
            }
        }
    }

    fun read(pcm: ShortArray): Int {
        val audio = checkNotNull(recorder)
        return audio.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
    }

    fun stop(): Boolean = synchronized(lock) {
        stopped = true
        val audio = recorder ?: return@synchronized true
        try {
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to stop microphone", e)
            audio.recordingState != AudioRecord.RECORDSTATE_RECORDING
        }
    }

    private fun enableEffects(sessionId: Int): Boolean {
        var echoCancellation = false
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { effect ->
                    effects += effect
                    echoCancellation = effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { effect ->
                    effects += effect
                    effect.setEnabled(true)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Device audio effects unavailable", e)
        }
        return echoCancellation
    }

    override fun close() {
        try { stop() }
        finally {
            val audio = synchronized(lock) { recorder.also { recorder = null } }
            try {
                for (effect in effects) {
                    try { effect.release() }
                    catch (e: RuntimeException) { Log.w(TAG, "Failed to release audio effect", e) }
                }
            } finally {
                effects.clear()
                echoCancellationEnabled = false
                try { audio?.release() }
                catch (e: RuntimeException) { Log.w(TAG, "Failed to release microphone", e) }
            }
        }
    }

    companion object { private const val TAG = "DoubaoMicrophone" }
}
