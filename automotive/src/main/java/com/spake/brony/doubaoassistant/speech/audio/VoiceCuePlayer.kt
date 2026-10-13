package com.spake.brony.doubaoassistant.speech.audio


import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RawRes
import com.spake.brony.doubaoassistant.R

@MainThread
class VoiceCuePlayer(context: Context) : AutoCloseable {
    private var pool: SoundPool? = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()
    private val samples = mutableMapOf<Int, Int>()
    private val loaded = mutableSetOf<Int>()
    private var pending: Int? = null
    private var stream = 0

    init {
        val player = checkNotNull(pool)
        player.setOnLoadCompleteListener { _, sample, status ->
            if (pool == null) return@setOnLoadCompleteListener
            if (status == 0) {
                loaded.add(sample)
                playPending()
            } else {
                Log.e("VoiceCuePlayer", "SoundPool load failed: sample=$sample status=$status")
                if (pending == sample) pending = null
            }
        }
        for (resource in listOf(R.raw.haode, R.raw.wozai)) {
            val sample = player.load(context.applicationContext, resource, 1)
            if (sample != 0) samples[resource] = sample
            else Log.e("VoiceCuePlayer", "SoundPool load returned 0: resource=$resource")
        }
    }

    fun play(@RawRes resource: Int = R.raw.haode) {
        if (pool == null) return
        stop()
        // 首次命中早于异步加载完成时，只保留最新口令，不排队播报旧口令。
        pending = samples[resource]
        playPending()
    }

    private fun playPending() {
        val player = pool ?: return
        val sample = pending?.takeIf { it in loaded } ?: return
        pending = null
        // 短提示不另抢音频焦点，避免取消当前 AI 播报。
        stream = player.play(sample, 1.0f, 1.0f, 1, 0, 1f)
        if (stream == 0) Log.e("VoiceCuePlayer", "SoundPool play returned 0: sample=$sample")
    }

    fun stop() {
        pending = null
        if (stream != 0) pool?.stop(stream)
        stream = 0
    }

    override fun close() {
        stop()
        pool?.setOnLoadCompleteListener(null)
        pool?.release()
        pool = null
        samples.clear()
        loaded.clear()
    }
}
