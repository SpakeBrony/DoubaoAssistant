package com.spake.brony.doubaoassistant.data.model

object AssistantName {
    const val DEFAULT = "豆包"
    private val pattern = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]{2,4}")

    fun isValid(value: String): Boolean = pattern.matches(value)
}
