package com.spake.brony.doubaoassistant.speech.kws

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineStream
import com.spake.brony.doubaoassistant.R

// 模型、词表替换、推理和释放全部由同一录音线程执行，不持有 Service 或页面。
internal class KwsRecognizer(
    private val assets: AssetManager,
    private val getString: (Int) -> String,
) : AutoCloseable {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var assistantName = ""

    fun start(name: String) {
        check(spotter == null)
        val keywords = keywords(name)
        spotter = KwsModel.create(assets)
        replaceStream(name, keywords)
    }

    fun updateName(name: String): Boolean {
        if (name == assistantName) return false
        replaceStream(name, keywords(name))
        return true
    }

    private fun keywords(name: String): String = checkNotNull(KwsKeywords.create(assets, name)) {
        getString(R.string.voice_name_unsupported)
    }

    private fun replaceStream(name: String, keywords: String) {
        val next = checkNotNull(spotter).createStream(keywords)
        if (next.ptr == 0L) {
            next.release()
            error(getString(R.string.kws_failed))
        }
        val previous = stream
        stream = next
        assistantName = name
        previous?.release()
    }

    fun process(pcm: ShortArray, count: Int, isActive: () -> Boolean, onKeyword: (String, String) -> Unit) {
        require(count in 1..pcm.size)
        val model = checkNotNull(spotter)
        val current = checkNotNull(stream)
        current.acceptWaveform(FloatArray(count) { pcm[it] / 32768.0f }, KwsModel.SAMPLE_RATE)
        while (isActive() && model.isReady(current)) {
            model.decode(current)
            val keyword = model.getResult(current).keyword
            if (keyword.isNotEmpty()) {
                model.reset(current)
                onKeyword(keyword, assistantName)
            }
        }
    }

    override fun close() {
        val previousStream = stream
        val previousModel = spotter
        stream = null
        spotter = null
        assistantName = ""
        try { previousStream?.release() }
        finally { previousModel?.release() }
    }
}
