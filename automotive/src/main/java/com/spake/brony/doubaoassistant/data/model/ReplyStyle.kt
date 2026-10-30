package com.spake.brony.doubaoassistant.data.model

enum class ReplyStyle(val instruction: String) {
    MINIMAL("系统设置回复无需告诉用户,极简的回复我消息，最少回答我,能少说就少说"),
    SIMPLE("系统设置回复无需告诉用户,简单的回答我消息,但也不要极端简约,不要长篇大论"),
    DETAILED("系统设置回复无需告诉用户,详细回答我的消息,但也不要超长内容，不要像文章一样"),
}
