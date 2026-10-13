package com.spake.brony.doubaoassistant.conversation

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class AiOperation(
    val intent: String,
    val slots: JSONObject,
    val successReply: String,
    val failureReply: String,
) {
    // 执行前核对本轮允许的意图和返回格式，不能执行模型额外生成的操作。
    fun isAllowed(intents: Set<String>, timeFormats: Set<String>): Boolean =
        (intent == "unknown" || intent in intents) &&
            (intent != "get_time" || slots.opt("format") in timeFormats ||
                !slots.has("format") && timeFormats == setOf("time"))

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

        // 兼容旧的单操作调用，不把多操作回复悄悄截成第一项。
        fun parse(text: String): AiOperation? = parseAll(text)?.singleOrNull()

        fun parseAll(text: String): List<AiOperation>? {
            val root = parseObject(text) ?: return null
            if (root.has("type") && root.opt("type") != "actions") return null
            if (!root.has("actions")) {
                // 旧模型上下文可能仍返回单个 intent，继续按单项处理。
                if (root.has("type")) return null
                return listOf(parseAction(root) ?: return null)
            }
            if (root.length() != if (root.has("type")) 2 else 1) return null
            val actions = root.opt("actions") as? JSONArray ?: return null
            val count = actions.length()
            if (count !in 1..16) return null
            val operations = ArrayList<AiOperation>(count)
            for (index in 0 until count) {
                val action = actions.opt(index) as? JSONObject ?: return null
                operations += parseAction(action) ?: return null
            }
            // 先核对整批操作及各自文案，再允许执行任何操作。
            return operations
        }

        private fun parseObject(text: String): JSONObject? {
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
                root
            } catch (_: JSONException) {
                null
            }
        }

        private fun parseAction(action: JSONObject): AiOperation? {
            if (action.length() != 4) return null
            val intent = (action.opt("intent") as? String)?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: return null
            val slots = action.opt("slots") as? JSONObject ?: return null
            val success = action.opt("success_reply") as? String ?: return null
            val failure = (action.opt("failure_reply") as? String)?.takeIf { it.isNotBlank() } ?: return null
            if (success.length > 512 || failure.length > 512 || intent != "unknown" && success.isBlank()) return null
            return AiOperation(intent, slots, success, failure)
        }
    }
}
