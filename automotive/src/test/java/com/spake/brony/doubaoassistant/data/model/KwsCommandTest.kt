package com.spake.brony.doubaoassistant.data.model

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class KwsCommandTest {
    @Test fun allSevenKeywordsResumeSpeechWithValidTokens() {
        val assets = File("src/main/assets/kws")
        val tokens = File(assets, "tokens.txt").readLines().map { it.substringBeforeLast(' ') }.toSet()
        val lines = File(assets, "keywords.txt").readLines().filter(String::isNotBlank)
        assertEquals(7, lines.size)
        assertEquals(setOf(KwsCommand.RESUME), KwsCommand.entries.toSet())
        lines.forEach { line ->
            val parts = line.split(" @")
            assertEquals(KwsCommand.RESUME, KwsCommand.fromKeyword(parts[1]))
            parts[0].split(' ').forEach { assertTrue(it in tokens) }
        }
        for (name in listOf("豆包", "小白")) {
            for (keyword in listOf("你好$name", "${name}你好", "在吗$name", "${name}在吗",
                "可以说话了$name", "说话$name", "${name}说话")) {
                assertEquals(KwsCommand.RESUME, KwsCommand.fromKeyword(keyword, name))
            }
            for (text in listOf("闭嘴$name", "退下$name", "退出$name", "关闭麦克风$name")) {
                assertNull(KwsCommand.fromKeyword(text, name))
            }
        }
    }
}
