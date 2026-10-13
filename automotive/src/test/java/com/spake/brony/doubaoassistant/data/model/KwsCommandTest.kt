package com.spake.brony.doubaoassistant.data.model

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class KwsCommandTest {
    @Test fun allKeywordsMapToResumeOrControlActionsWithValidTokens() {
        val assets = File("src/main/assets/kws")
        val tokens = File(assets, "tokens.txt").readLines().map { it.substringBeforeLast(' ') }.toSet()
        val lines = File(assets, "keywords.txt").readLines().filter(String::isNotBlank)
        assertEquals(20, lines.size)
        assertEquals(setOf(KwsCommand.RESUME, KwsCommand.STOP_REPLY,
            KwsCommand.RETURN_HOME, KwsCommand.CLOSE_MICROPHONE), KwsCommand.entries.toSet())
        val stopWords = setOf("闭嘴豆包", "豆包闭嘴", "别说话豆包", "豆包别说话")
        val homeWords = setOf("退下豆包", "豆包退下", "退下吧豆包", "退出豆包", "豆包退出",
            "退出吧豆包")
        val micWords = setOf("关闭麦克风豆包", "豆包关闭麦克风")
        assertTrue(lines.map { it.substringAfter(" @") }.containsAll(stopWords + homeWords + micWords))
        lines.forEach { line ->
            val parts = line.split(" @")
            val expected = when (parts[1]) {
                in stopWords -> KwsCommand.STOP_REPLY
                in homeWords -> KwsCommand.RETURN_HOME
                in micWords -> KwsCommand.CLOSE_MICROPHONE
                else -> KwsCommand.RESUME
            }
            assertEquals(expected, KwsCommand.fromKeyword(parts[1]))
            parts[0].split(' ').forEach { assertTrue(it in tokens) }
        }
        for (name in listOf("豆包", "小白")) {
            for (keyword in listOf("你好$name", "$name$name", "${name}你好", "在吗$name", "${name}在吗",
                "可以说话了$name", "说话$name", "${name}说话")) {
                assertEquals(KwsCommand.RESUME, KwsCommand.fromKeyword(keyword, name))
            }
            for (keyword in listOf("闭嘴$name", "${name}闭嘴", "别说话$name", "${name}别说话")) {
                assertEquals(KwsCommand.STOP_REPLY, KwsCommand.fromKeyword(keyword, name))
            }
            for (keyword in homeWords) {
                assertEquals(KwsCommand.RETURN_HOME, KwsCommand.fromKeyword(keyword.replace("豆包", name), name))
            }
            for (keyword in micWords) {
                assertEquals(KwsCommand.CLOSE_MICROPHONE, KwsCommand.fromKeyword(keyword.replace("豆包", name), name))
            }
            for (text in listOf("退下", "退出", "关闭麦克风", "闭嘴", "别说话", "打开麦克风$name",
                "下去吧$name", "${name}下去吧", "下去$name", "${name}下去")) {
                assertNull(KwsCommand.fromKeyword(text, name))
            }
        }
        assertNull(KwsCommand.RESUME.controlCommand)
        assertEquals(AsrCommand.entries.toSet(), KwsCommand.entries.mapNotNull { it.controlCommand }.toSet())
    }
}
