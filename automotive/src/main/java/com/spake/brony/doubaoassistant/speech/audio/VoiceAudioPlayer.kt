package com.spake.brony.doubaoassistant.speech.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RawRes
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.BuildConfig

class VoiceAudioPlayer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private val playbacks = mutableSetOf<Playback>()
    private var cue: Playback? = null

    @MainThread
    fun play(@RawRes resource: Int = R.raw.haode) {
        if (closed) return
        stop()
        // 提示音不另抢焦点，保留关麦确认可与当前回答并存的行为。
        cue = createPlayback(source = { media ->
            media.setDataSource(appContext, Uri.parse("android.resource://${appContext.packageName}/$resource"))
        }, onComplete = { cue = null }, onError = {
            cue = null
            Log.w("VoiceAudioPlayer", "Local cue playback failed: resource=$resource")
        })
    }

    @MainThread
    fun stop() {
        cue?.cancel()
        cue = null
    }

    @MainThread
    override fun close() {
        closed = true
        synchronized(playbacks) { playbacks.toList() }.forEach { playback ->
            try { playback.close() }
            catch (error: RuntimeException) { Log.w("VoiceAudioPlayer", "MediaPlayer release failed", error) }
        }
        stop()
    }

    private fun createPlayback(
        source: (MediaPlayer) -> Unit,
        onComplete: () -> Unit,
        onError: () -> Unit,
    ): Playback = synchronized(playbacks) {
        check(!closed) { "Voice player is closed" }
        Playback(source, onComplete, onError).also {
            playbacks += it
            it.prepare()
        }
    }

    internal inner class Playback(
        private val source: (MediaPlayer) -> Unit,
        private val onComplete: () -> Unit,
        private val onError: () -> Unit,
    ) : AutoCloseable {
        @Volatile private var cancelled = false
        private var player: MediaPlayer? = null

        // MediaPlayer 的异步准备、回调、播放和释放都在主线程，避免跨线程操作状态机。
        fun prepare() {
            main.post {
                if (cancelled || closed) return@post
                try {
                    val media = MediaPlayer()
                    player = media
                    media.setAudioAttributes(playbackAttributes())
                    media.setVolume(PLAYBACK_GAIN, PLAYBACK_GAIN)
                    media.setOnPreparedListener {
                        if (cancelled || closed) return@setOnPreparedListener
                        try {
                            media.start()
                            if (BuildConfig.DEBUG) Log.i("VoiceAudioPlayer", "Local MP3 started usage=ASSISTANT gain=$PLAYBACK_GAIN durationMs=${media.duration}")
                        } catch (error: RuntimeException) {
                            Log.e("VoiceAudioPlayer", "MediaPlayer start failed", error)
                            fail()
                        }
                    }
                    media.setOnCompletionListener {
                        if (!cancelled) {
                            cancelled = true
                            release()
                            onComplete()
                        }
                    }
                    media.setOnErrorListener { _, what, extra ->
                        Log.e("VoiceAudioPlayer", "MediaPlayer error: what=$what extra=$extra")
                        fail()
                        true
                    }
                    source(media)
                    media.prepareAsync()
                } catch (error: Exception) {
                    Log.e("VoiceAudioPlayer", "MediaPlayer prepare failed", error)
                    fail()
                }
            }
        }

        private fun fail() {
            if (cancelled) return
            cancelled = true
            release()
            onError()
        }

        fun cancel() {
            cancelled = true
            if (Looper.myLooper() == main.looper) release() else main.post { release() }
        }

        @MainThread
        private fun release() {
            val media = player
            player = null
            try {
                media?.setOnPreparedListener(null)
                media?.setOnCompletionListener(null)
                media?.setOnErrorListener(null)
                media?.release()
            } catch (error: RuntimeException) {
                Log.w("VoiceAudioPlayer", "MediaPlayer release failed", error)
            } finally {
                synchronized(playbacks) { playbacks.remove(this) }
            }
        }

        override fun close() = cancel()
    }

    internal companion object {
        const val PLAYBACK_GAIN = 1.0f
        fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}
