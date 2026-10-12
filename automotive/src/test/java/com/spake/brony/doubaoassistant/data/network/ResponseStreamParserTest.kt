package com.spake.brony.doubaoassistant.data.network

import java.io.IOException
import com.spake.brony.doubaoassistant.R
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ResponseStreamParserTest {
    private val getString: (Int) -> String = { "localized:$it" }
    @Test fun streamingTextIsAccumulatedAndIdOnlyArrivesAtCompletion() {
        val parser = ResponseStreamParser(getString)
        val source = Buffer().writeUtf8(": heartbeat\r\n\r\n" +
            "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"秘密\"}\r\n\r\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\r\n\r\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"世界\"}\r\n\r\n" +
            "data: {\"type\":\"response.completed\",\n" +
            "data: \"response\":{\"status\":\"completed\",\"id\":\"response_1\",\"output\":[]}}\n\n")
        val updates = mutableListOf<com.spake.brony.doubaoassistant.data.model.ResponseProgress>()
        parser.read(source) { updates += it }
        assertEquals(listOf("你好", "你好世界", "你好世界"), updates.map { it.text })
        assertTrue(updates.dropLast(1).all { it.completedId == null })
        assertEquals("response_1", updates.last().completedId)
    }

    @Test(expected = IOException::class)
    fun brokenStreamIsNotACompletedResponse() {
        ResponseStreamParser(getString).read(Buffer().writeUtf8(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分回复\"}\n\n")) {}
    }

    @Test fun failedResponseDoesNotAdvanceId() {
        val parser = ResponseStreamParser(getString)
        parser.accept("""{"type":"response.incomplete"}""")
        assertEquals(getString(R.string.chat_response_incomplete), parser.progress.error)
        assertNull(parser.progress.completedId)
    }

    @Test fun requestUsesTextAndPreviousResponseNotExampleImage() {
        val body = DoubaoResponsesClient.requestBody("附近有什么", "previous_1")
        assertEquals("doubao-seed-2-0-mini-260428", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getBoolean("store"))
        assertEquals("previous_1", body.getString("previous_response_id"))
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("附近有什么", content.getJSONObject(0).getString("text"))
        assertFalse(DoubaoResponsesClient.requestBody("第一轮", null).has("previous_response_id"))
    }
}
