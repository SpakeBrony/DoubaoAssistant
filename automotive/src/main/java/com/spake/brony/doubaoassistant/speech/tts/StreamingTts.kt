package com.spake.brony.doubaoassistant.speech.tts

internal interface StreamingTts {
    fun start(version: Long, speed: Int)
    fun append(delta: String)
    fun finish()
    fun cancel()
}
