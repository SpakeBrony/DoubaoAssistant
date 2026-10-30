package com.spake.brony.doubaoassistant.conversation

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class AiIntentClassification(val isOperation: Boolean, val intents: Set<String>, val json: String) {
    companion object {
        fun schema(candidates: Set<String>): JSONObject {
            require(candidates.isNotEmpty())
            return JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("is_operation", JSONObject().put("type", "boolean"))
                    .put("intents", JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string").put("enum", JSONArray(candidates.toList())))))
                .put("required", JSONArray(listOf("is_operation", "intents")))
                .put("additionalProperties", false)
        }

        fun parse(text: String, candidates: Set<String>): AiIntentClassification {
            val tokens = JSONTokener(text)
            val result = tokens.nextValue() as? JSONObject ?: error("Classification must be an object")
            check(tokens.nextClean() == '\u0000' && result.length() == 2)
            val isOperation = result.opt("is_operation") as? Boolean ?: error("Invalid operation flag")
            val values = result.opt("intents") as? JSONArray ?: error("Invalid intents array")
            check(values.length() <= candidates.size)
            val intents = linkedSetOf<String>()
            for (index in 0 until values.length()) {
                val intent = values.opt(index) as? String ?: error("Invalid intent")
                check(intent in candidates && intents.add(intent))
            }
            // 操作必须有候选意图（或 unknown）；闲聊必须为空，矛盾结果不能继续执行。
            check(isOperation == intents.isNotEmpty())
            return AiIntentClassification(isOperation, intents.toSet(), text.trim())
        }
    }
}
