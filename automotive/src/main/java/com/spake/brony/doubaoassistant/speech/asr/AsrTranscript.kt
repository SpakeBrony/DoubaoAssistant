package com.spake.brony.doubaoassistant.speech.asr

import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import org.json.JSONObject

// 服务返回 full 结果；确定分句按时间去重，中间结果替换，不逐字追加。
internal class AsrTranscript(initial: ConversationContent = ConversationContent()) {
    var content = initial.copy(liveText = "", phase = ConversationPhase.READY)
        private set
    private var committedEnd = -1L
    private var finalCommitted = false
    var confirmedVersion = 0L
        private set
    var confirmedText = ""
        private set

    fun beginRequest() { committedEnd = -1L; finalCommitted = false }

    fun accept(json: String, final: Boolean): ConversationContent {
        val root = JSONObject(json)
        val result = root.optJSONObject("result") ?: root.optJSONArray("result")?.optJSONObject(0)
            ?: error("识别结果缺少 result 字段")
        val utterances = result.optJSONArray("utterances")
        val newMessages = mutableListOf<ConversationMessage>()
        val live = StringBuilder()
        if (utterances != null && utterances.length() > 0) {
            for (index in 0 until utterances.length()) {
                val utterance = utterances.getJSONObject(index)
                val text = utterance.optString("text").trim()
                if (text.isEmpty()) continue
                if (utterance.optBoolean("definite") || final) {
                    val end = utterance.getLong("end_time")
                    if (end > committedEnd) {
                        newMessages += ConversationMessage(text, isAssistant = false)
                        committedEnd = end
                    }
                } else live.append(text)
            }
        } else {
            val text = result.optString("text").trim()
            if (final && !finalCommitted && text.isNotEmpty()) {
                newMessages += ConversationMessage(text, isAssistant = false)
                finalCommitted = true
            } else if (!final) live.append(text)
        }
        content = ConversationContent(
            phase = if (live.isEmpty()) ConversationPhase.READY else ConversationPhase.LISTENING,
            messages = (content.messages + newMessages).takeLast(MAX_MESSAGES),
            liveText = live.toString(),
        )
        if (newMessages.isNotEmpty()) {
            ++confirmedVersion
            confirmedText = newMessages.joinToString("") { it.text }
        }
        return content
    }

    fun clearLiveText() { content = content.copy(phase = ConversationPhase.READY, liveText = "") }

    companion object { private const val MAX_MESSAGES = 40 }
}
