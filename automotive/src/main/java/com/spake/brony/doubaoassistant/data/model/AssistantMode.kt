package com.spake.brony.doubaoassistant.data.model

enum class AssistantMode {
    WAITING, AWAKE, MUTED;

    fun after(command: AsrCommand): AssistantMode = when (command) {
        AsrCommand.STOP_REPLY -> MUTED
        AsrCommand.RETURN_HOME -> if (this == MUTED) MUTED else WAITING
        AsrCommand.CLOSE_MICROPHONE -> WAITING
    }
}
