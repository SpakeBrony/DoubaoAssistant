package com.spake.brony.doubaoassistant.speech.asr

import androidx.annotation.StringRes
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AsrCommand

internal object AsrCommands {
    data class Rule(val command: AsrCommand, val keywords: List<String>, @StringRes val reply: Int = R.string.voice_command_stopped)

    val rules = listOf(
        Rule(AsrCommand.CLOSE_MICROPHONE, listOf("关闭麦克风")),
        Rule(AsrCommand.STOP_REPLY, listOf("闭嘴", "别说话")),
        Rule(AsrCommand.RETURN_HOME, listOf("退下", "退出", "下去吧", "下去")),
    )

    fun match(text: String, name: String): Rule? {
        val normalized = normalize(text)
        if (name.isBlank() || !normalized.contains(name)) return null
        // shortcut: 包含匹配也会命中引用或否定句，需要语义区分时再收紧。
        return rules.firstOrNull { rule -> rule.keywords.any(normalized::contains) }
    }

    fun isMicrophoneCommandCandidate(text: String, name: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty() || name.isBlank()) return false
        // shortcut: 仅保护已配置口令的候选前缀；需支持前缀误识别或任意前导句时再调整打断策略。
        return rules.first { it.command == AsrCommand.CLOSE_MICROPHONE }.keywords.any { keyword ->
            normalized.contains(keyword) || (keyword + name).startsWith(normalized) ||
                (name + keyword).startsWith(normalized)
        }
    }

    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }
}
