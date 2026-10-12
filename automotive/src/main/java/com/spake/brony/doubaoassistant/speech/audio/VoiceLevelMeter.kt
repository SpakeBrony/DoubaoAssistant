package com.spake.brony.doubaoassistant.speech.audio

import kotlin.math.log10
import kotlin.math.sqrt

// 数字音量 dBFS，不是经过麦克风校准的环境 dB SPL；人声由 ASR 确认后才驱动 UI。
internal object VoiceLevelMeter {
    fun measure(pcm: ShortArray, count: Int): Float {
        require(count in 1..pcm.size)
        var energy = 0.0
        for (index in 0 until count) {
            val sample = pcm[index].toDouble() / 32768.0
            energy += sample * sample
        }
        val rms = sqrt(energy / count)
        val db = 20.0 * log10(rms.coerceAtLeast(0.000001))
        return ((db + 50.0) / 38.0).coerceIn(0.0, 1.0).toFloat()
    }
}
