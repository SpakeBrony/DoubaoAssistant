package com.spake.brony.doubaoassistant.data.model

enum class KwsCommand {
    RESUME;

    companion object {
        fun fromKeyword(keyword: String, name: String = AssistantName.DEFAULT): KwsCommand? = when (keyword) {
            "你好$name", "${name}你好", "在吗$name", "${name}在吗",
            "可以说话了$name", "说话$name", "${name}说话" -> RESUME
            else -> null
        }
    }
}
