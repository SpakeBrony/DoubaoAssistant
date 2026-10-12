package com.spake.brony.doubaoassistant.data.model

enum class ReplyStyle(val instruction: String) {
    MINIMAL("极简的回复我消息，最少回答我"),
    SIMPLE("简单的回答我消息,但也不要极端简约"),
    DETAILED("详细回答我的消息,但也不要超长内容，不要像文章一样"),
}
