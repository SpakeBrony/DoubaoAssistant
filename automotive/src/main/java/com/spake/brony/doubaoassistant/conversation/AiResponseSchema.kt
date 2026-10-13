package com.spake.brony.doubaoassistant.conversation

import org.json.JSONArray
import org.json.JSONObject

/** 仅用于独立判断为操作的正式请求，不包含聊天分支。 */
internal object AiResponseSchema {
    private fun value(type: String) = JSONObject().put("type", type)
    private fun choice(vararg values: Any) = value(if (values.first() is Int) "integer" else "string")
        .put("enum", JSONArray(values.toList()))
    private fun objectOf(fields: Map<String, JSONObject>) = value("object")
        .put("properties", JSONObject(fields)).put("required", JSONArray(fields.keys.toList()))
        .put("additionalProperties", false)

    // 可选参数使用有/无字段分支，保持执行器的默认值，不要求模型传 null。
    private fun slots(required: Map<String, JSONObject>, optional: Map<String, JSONObject> = emptyMap()): JSONObject {
        if (optional.isEmpty()) return objectOf(required)
        val options = optional.entries.toList()
        return JSONObject().put("anyOf", JSONArray((0 until (1 shl options.size)).map { mask ->
            val fields = LinkedHashMap(required)
            options.forEachIndexed { index, field -> if (mask and (1 shl index) != 0) fields[field.key] = field.value }
            objectOf(fields)
        }))
    }

    private val seat = choice("driver", "passenger").put("description", "未指定位置默认主驾driver。")
    private val direction = choice("increase", "decrease")
    private val channel = choice("media", "navigation", "call")
    private val definitions = mapOf(
        "switch" to slots(mapOf("enabled" to value("boolean"))),
        "temperature" to slots(mapOf("temperature" to value("number")), mapOf("seat" to seat)),
        "temperature_adjustment" to slots(mapOf("direction" to direction), mapOf("amount" to value("number"), "seat" to seat)),
        "fan" to slots(mapOf("level" to choice(1, 2, 3, 4, 5, 6, 7, 8, 9))),
        "fan_adjustment" to slots(mapOf("direction" to direction), mapOf("amount" to value("integer"))),
        "seat" to slots(mapOf("enabled" to value("boolean")), mapOf("seat" to seat,
            "level" to choice(0, 1, 2, 3).put("description", "未指定档位时开启默认2，关闭默认0。"))),
        "circulation" to slots(mapOf("mode" to choice("internal", "external"))),
        "media" to slots(mapOf("action" to choice("next", "previous", "play", "pause"))),
        "song" to slots(mapOf("song" to value("string"))),
        "volume" to slots(mapOf("percent" to value("integer")), mapOf("channel" to channel)),
        "volume_adjustment" to slots(mapOf("direction" to direction), mapOf("channel" to channel, "amount" to value("integer"))),
        "app" to slots(mapOf("app" to value("string"))),
        "navigation" to slots(mapOf("destination" to value("string"))),
        "phone" to JSONObject().put("anyOf", JSONArray(listOf(
            slots(mapOf("phone_number" to value("string"))), slots(mapOf("contact_name" to value("string"))),
        ))),
        "empty" to slots(emptyMap()),
        "weather" to slots(emptyMap(), mapOf("location" to value("string").put("description", "未指定地点时省略，由本机定位。"),
            "region" to value("string"), "country" to value("string"), "day_offset" to choice(0, 1, 2, 3, 4, 5, 6))),
    )
    private val intentSlots = mapOf(
        "set_ac_power" to "switch", "set_ac_cooling" to "switch", "set_ac_auto" to "switch",
        "set_rear_window_heating" to "switch", "set_tv_power" to "switch",
        "set_temperature" to "temperature", "adjust_temperature" to "temperature_adjustment",
        "set_fan_speed" to "fan", "adjust_fan_speed" to "fan_adjustment",
        "set_seat_ventilation" to "seat", "set_seat_heating" to "seat", "set_circulation" to "circulation",
        "media_control" to "media", "play_song" to "song", "play_favorites" to "empty",
        "set_volume" to "volume", "adjust_volume" to "volume_adjustment",
        "open_app" to "app", "navigate" to "navigation", "call_phone" to "phone", "unknown" to "empty",
        "get_weather" to "weather", "get_time" to "time",
    )

    fun create(enabledIntents: Set<String>, timeFormats: Set<String>): JSONObject {
        val usedDefinitions = JSONObject()
        val actions = JSONArray()
        for (intent in enabledIntents + "unknown") {
            val definition = intentSlots.getValue(intent)
            val parameters = if (definition == "time") {
                val format = mapOf("format" to choice(*timeFormats.toTypedArray()))
                val location = mapOf("location" to value("string"), "region" to value("string"), "country" to value("string"))
                if (timeFormats == setOf("time")) slots(emptyMap(), format + location) else slots(format, location)
            } else definitions.getValue(definition)
            usedDefinitions.put(definition, parameters)
            actions.put(objectOf(mapOf(
                "intent" to choice(intent), "slots" to JSONObject().put("\$ref", "#/\$defs/$definition"),
                "success_reply" to value("string"), "failure_reply" to value("string"),
            )))
        }
        return objectOf(mapOf("actions" to value("array").put("items", JSONObject().put("anyOf", actions))))
            .put("\$defs", usedDefinitions)
    }
}
