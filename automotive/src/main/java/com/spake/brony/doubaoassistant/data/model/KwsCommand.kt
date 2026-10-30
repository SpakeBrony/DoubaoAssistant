package com.spake.brony.doubaoassistant.data.model

enum class KwsCommand(val controlCommand: AsrCommand? = null) {
    RESUME,
    STOP_REPLY(AsrCommand.STOP_REPLY),
    RETURN_HOME(AsrCommand.RETURN_HOME),
    CLOSE_MICROPHONE(AsrCommand.CLOSE_MICROPHONE);

    companion object {
        private fun greetingKeywords(name: String) = listOf("你好$name")

        fun isGreeting(keyword: String?, name: String = AssistantName.DEFAULT): Boolean =
            keyword in greetingKeywords(name)

        fun fromKeyword(keyword: String, name: String = AssistantName.DEFAULT): KwsCommand? = when (keyword) {
            in greetingKeywords(name) -> RESUME
            "可以说话了$name", "说话$name", "${name}说话" -> RESUME
            "闭嘴$name", "${name}闭嘴", "别说话$name", "${name}别说话" -> STOP_REPLY
            "关闭麦克风$name", "${name}关闭麦克风" -> CLOSE_MICROPHONE
            "退下$name", "${name}退下", "退下吧$name",
            "退出$name", "${name}退出", "退出吧$name" -> RETURN_HOME
            else -> null
        }
    }
}
