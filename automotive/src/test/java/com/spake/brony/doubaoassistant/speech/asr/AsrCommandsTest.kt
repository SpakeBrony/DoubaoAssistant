package com.spake.brony.doubaoassistant.speech.asr

import com.spake.brony.doubaoassistant.data.model.AsrCommand
import com.spake.brony.doubaoassistant.R
import org.junit.Assert.*
import org.junit.Test

class AsrCommandsTest {
    @Test fun microphoneCandidatesKeepBothOrdersAndDynamicNamesWithoutExecutingPartialCommands() {
        assertTrue(AsrCommands.rules.all { it.reply == R.string.voice_command_stopped })
        for (name in listOf("豆包", "小白", "小小助手")) {
            for (text in listOf("关闭麦克风$name", "${name}关闭麦克风")) {
                for (length in 1..text.length) {
                    val partial = text.take(length)
                    assertTrue(partial, AsrCommands.isMicrophoneCommandCandidate(partial, name))
                    if (length < text.length) assertNull(AsrCommands.match(partial, name))
                }
            }
            assertTrue(AsrCommands.isMicrophoneCommandCandidate("关闭麦克风，$name。", name))
            assertFalse(AsrCommands.isMicrophoneCommandCandidate("${name}关闭空调", name))
            assertFalse(AsrCommands.isMicrophoneCommandCandidate("讲一个故事", name))
            assertFalse(AsrCommands.isMicrophoneCommandCandidate("${name}闭嘴", name))
        }
        assertFalse(AsrCommands.isMicrophoneCommandCandidate("", "豆包"))
        assertFalse(AsrCommands.isMicrophoneCommandCandidate("关闭麦克风", ""))
    }

    @Test fun rulesHaveNoHardcodedNameAndMatchEitherOrderWithPunctuation() {
        assertEquals(AsrCommand.entries.toSet(), AsrCommands.rules.map { it.command }.toSet())
        for (rule in AsrCommands.rules) for (keyword in rule.keywords) {
            assertFalse(keyword.contains("豆包"))
            for (name in listOf("豆包", "小白", "小小助手")) {
                for (text in listOf("$keyword，$name。", "$name,$keyword!", "请$name 现在$keyword",
                    "${keyword.take(1)}，${keyword.drop(1)} $name")) {
                    assertEquals(text, rule, AsrCommands.match(text, name))
                }
                assertNull(AsrCommands.match(keyword, name))
                assertNull(AsrCommands.match("${keyword}其他助手", name))
            }
        }
        for (text in listOf("你好豆包", "豆包你好", "在吗豆包", "可以说话了豆包", "说话豆包", "豆包说话")) {
            assertNull(AsrCommands.match(text, "豆包"))
        }
        assertNull(AsrCommands.match("闭嘴豆包", "小白"))
        assertNull(AsrCommands.match("闭嘴", ""))
        assertEquals(AsrCommand.CLOSE_MICROPHONE, AsrCommands.match("豆包闭嘴并关闭麦克风", "豆包")?.command)
    }
}
