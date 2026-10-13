package com.spake.brony.doubaoassistant.conversation

import androidx.annotation.StringRes

/** 操作端已确认的失败原因，交给对话流程统一显示、保存和播报。 */
internal class AiOperationFailure(@StringRes val reply: Int) : Exception()
