package com.spake.brony.doubaoassistant.speech.kws

import com.spake.brony.doubaoassistant.data.model.AssistantName
import com.spake.brony.doubaoassistant.data.model.KwsCommand
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class KwsKeywordsTest {
    private val assets = File("src/main/assets/kws")
    private val templates = File(assets, "keywords.txt").readLines()
    private val tokens = File(assets, "tokens.txt").readLines().map { it.substringBeforeLast(' ') }.toSet()

    private fun keywords(name: String) = File(assets, "pinyin.txt").bufferedReader().useLines {
        KwsKeywords.create(name, templates, tokens, it)
    }

    @Test fun defaultNameKeepsAllExistingCommandsAndBaseKeywordsAreEmpty() {
        assertEquals(templates, keywords(AssistantName.DEFAULT)!!.split('/'))
        assertFalse(templates.any { it.contains("问你一个问题") })
        assertFalse(templates.any { it.contains("回到主页") || it.contains("回到首页") })
        assertEquals("", File(assets, "base_keywords.txt").readText())
    }

    @Test fun customNamesGenerateOnlyTheirOwnCommandsUsingKnownModelTokens() {
        for (name in listOf("小白", "小小助手", "小绿", "重庆")) {
            val lines = keywords(name)!!.split('/')
            val commands = lines.map { line ->
                val parts = line.split(" @")
                assertTrue(parts[1].contains(name))
                assertFalse(parts[1].contains("豆包"))
                assertNull(KwsCommand.fromKeyword(parts[1]))
                parts[0].split(' ').forEach { assertTrue("Unknown token: $it", it in tokens) }
                KwsCommand.fromKeyword(parts[1], name).also { assertNotNull(it) }
            }
            assertEquals(KwsCommand.entries.toSet(), commands.toSet())
            assertEquals(7, lines.map { it.substringAfter(" @") }.toSet().size)
            assertFalse(lines.any { it.contains("问你一个问题") })
            assertFalse(lines.any { it.contains("回到主页") || it.contains("回到首页") })
            assertNull(KwsCommand.fromKeyword("你好豆包", name))
        }
        assertTrue(keywords("小白")!!.contains("n ǐ h ǎo x iǎo b ái @你好小白"))
        assertTrue(keywords("小白")!!.contains("x iǎo b ái n ǐ h ǎo @小白你好"))
        assertTrue(keywords("小白")!!.contains("z ài m a x iǎo b ái @在吗小白"))
        assertTrue(keywords("小白")!!.contains("x iǎo b ái z ài m a @小白在吗"))
        assertFalse(keywords("小白")!!.contains("退出"))
        assertFalse(keywords("小白")!!.contains("闭嘴"))
        assertFalse(keywords("小白")!!.contains("关闭麦克风"))
        assertTrue(keywords("重庆")!!.contains("ch óng q ìng sh uō h uà @重庆说话"))
        assertTrue(keywords("重庆")!!.contains("zh òng q ìng sh uō h uà @重庆说话"))
    }

    @Test fun invalidOrUnsupportedNamesCannotCreateKeywordStreams() {
        for (name in listOf("", "白", "小小小小白", "AI助手", "小 白", "小白/", "小白1", "😀小白")) {
            assertFalse(AssistantName.isValid(name))
            assertNull(keywords(name))
        }
        assertNull(KwsKeywords.create("小白", templates, tokens, emptySequence()))
        assertNull(KwsKeywords.create("小白", templates, emptySet(), sequenceOf("U+5C0F: xiǎo", "U+767D: bái")))
        assertNull(keywords("重重重重")) // 3^4 个读音组合超过上限，不能无界扩展关键词图。
    }
}
