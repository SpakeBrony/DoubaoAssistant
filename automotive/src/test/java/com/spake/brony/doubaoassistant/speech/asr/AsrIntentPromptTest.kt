package com.spake.brony.doubaoassistant.speech.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrIntentPromptTest {
    @Test fun eachFeatureSelectsOnlyItsOwnPromptAndOrdinaryChatHasNone() {
        val airConditioner = AsrIntentPrompt.configs[0]
        val seat = AsrIntentPrompt.configs[1]
        assertEquals(airConditioner.prompt, AsrIntentPrompt.instructionFor("把空调调到28度"))
        assertEquals(seat.prompt, AsrIntentPrompt.instructionFor("主驾座椅通风开到三档"))
        assertEquals(seat.prompt, AsrIntentPrompt.instructionFor("主驾座，椅通风开到三档"))
        assertEquals("", AsrIntentPrompt.instructionFor("讲个故事"))
        assertEquals("", AsrIntentPrompt.instructionFor(""))
        assertEquals(airConditioner.prompt, AsrIntentPrompt.instructionFor("打开空调和座椅通风"))
    }
}
