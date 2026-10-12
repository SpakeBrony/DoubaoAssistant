package com.spake.brony.doubaoassistant.conversation

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal data class AiOperation(
    val intent: String,
    val slots: JSONObject,
    val successReply: String,
    val failureReply: String,
) {
    companion object {
        // null 表示流式前缀还不足以判断，暂不送入 TTS。
        fun isJsonCandidate(text: String): Boolean? {
            val prefix = text.trimStart()
            if (prefix.isEmpty()) return null
            if (prefix.startsWith("{") || prefix.startsWith("[")) return true
            if ("```".startsWith(prefix)) return null
            if (!prefix.startsWith("```")) return false
            val content = prefix.substring(3).trimStart()
            if (content.isEmpty() || "json".startsWith(content, ignoreCase = true)) return null
            return content.startsWith("json", ignoreCase = true) || content.startsWith("{") || content.startsWith("[")
        }

        fun parse(text: String): AiOperation? {
            if (text.length > 32768) return null
            var json = text.trim()
            if (json.startsWith("```")) {
                if (json.length < 6 || !json.endsWith("```")) return null
                json = json.substring(3, json.length - 3).trim()
                if (json.startsWith("json", ignoreCase = true)) json = json.substring(4).trimStart()
            }
            return try {
                val tokens = JSONTokener(json)
                val root = tokens.nextValue() as? JSONObject ?: return null
                if (tokens.nextClean() != '\u0000') return null
                val intent = (root.opt("intent") as? String)?.takeIf { it.isNotBlank() } ?: return null
                val slots = root.opt("slots") as? JSONObject ?: return null
                val success = root.opt("success_reply") as? String ?: return null
                val failure = (root.opt("failure_reply") as? String)?.takeIf { it.isNotBlank() } ?: return null
                if (intent != "unknown" && success.isBlank()) return null
                AiOperation(intent, slots, success, failure)
            } catch (_: JSONException) {
                null
            }
        }
    }
}
