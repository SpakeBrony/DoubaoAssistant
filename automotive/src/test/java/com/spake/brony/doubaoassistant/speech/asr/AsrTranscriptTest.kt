package com.spake.brony.doubaoassistant.speech.asr

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AsrTranscriptTest {
    @Test fun partialsReplaceTextAndConfirmedSentencesAreNotDuplicated() {
        val transcript = AsrTranscript()
        transcript.beginRequest()
        assertEquals("你好", transcript.accept("""{"result":{"text":"你好"}}""", false).liveText)
        assertEquals("你好世界", transcript.accept("""{"result":{"text":"你好世界"}}""", false).liveText)
        val full = """{"result":{"text":"你好世界。第二句","utterances":[
            {"text":"你好世界。","end_time":1000,"definite":true},
            {"text":"第二句","end_time":2000,"definite":false}]}}"""
        val first = transcript.accept(full, false)
        assertEquals(1L, transcript.confirmedVersion)
        assertEquals("你好世界。", transcript.confirmedText)
        val repeated = transcript.accept(full, false)
        assertEquals(1L, transcript.confirmedVersion)
        assertEquals(first, repeated)
        assertEquals(listOf("你好世界。"), repeated.messages.map { it.text })
        assertEquals("第二句", repeated.liveText)
        val final = transcript.accept(full, true)
        assertEquals(2L, transcript.confirmedVersion)
        assertEquals("第二句", transcript.confirmedText)
        assertEquals(listOf("你好世界。", "第二句"), final.messages.map { it.text })
        assertEquals("", final.liveText)
        assertEquals(final, transcript.accept(full, true))
    }

    @Test fun newRequestsResetTimestampsButKeepOnlyFortyMessages() {
        val transcript = AsrTranscript()
        repeat(45) { index ->
            transcript.beginRequest()
            transcript.accept("""{"result":[{"text":"第${index}句","utterances":[
                {"text":"第${index}句","end_time":1000,"definite":true}]}]}""", false)
        }
        assertEquals(40, transcript.content.messages.size)
        assertEquals("第5句", transcript.content.messages.first().text)
        assertEquals("第44句", transcript.content.messages.last().text)
        assertEquals(45L, transcript.confirmedVersion)
    }

    @Test fun finalTextWithoutUtterancesIsCommittedOnce() {
        val transcript = AsrTranscript()
        transcript.beginRequest()
        val json = """{"result":{"text":"导航到上海。"}}"""
        transcript.accept(json, true)
        transcript.accept(json, true)
        assertEquals(1, transcript.content.messages.size)
        assertEquals("导航到上海。", transcript.content.messages.single().text)
    }

    @Test(expected = IllegalStateException::class)
    fun invalidResultCannotAppearAsSuccessfulRecognition() {
        AsrTranscript().accept("""{"message":"unexpected"}""", false)
    }
}
