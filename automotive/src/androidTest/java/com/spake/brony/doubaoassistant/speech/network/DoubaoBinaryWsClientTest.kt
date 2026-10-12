package com.spake.brony.doubaoassistant.speech.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bytedance.speech.speechengine.net.ws.IWsClient
import java.nio.ByteBuffer
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoubaoBinaryWsClientTest {
    private class CapturingSocket : WebSocket {
        var text: String? = null
        var binary: ByteString? = null
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { this.text = text; return true }
        override fun send(bytes: ByteString): Boolean { binary = bytes; return true }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    @Test fun sdkDefaultClientDropsBinaryHeaderForCertainJsonPayloadLengths() {
        val payload = """{"text":"${"x".repeat(245)}"}""".toByteArray(Charsets.UTF_8)
        assertEquals(256, payload.size)
        val packet = byteArrayOf(0x11, 0x10, 0x10, 0) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
        val captured = CapturingSocket()
        // 官方 0.0.15.0 AAR 将 OkHttpWsClient / mWebSocket 混淆为 c / c；仅测试访问。
        val original = Class.forName("com.bytedance.speech.c").getDeclaredConstructor().newInstance() as IWsClient
        original.javaClass.getDeclaredField("c").apply { isAccessible = true }.set(original, captured)
        assertTrue(original.send(packet))
        assertNull(captured.binary)
        assertEquals(String(payload, Charsets.UTF_8), captured.text)
        assertEquals(7, captured.text!!.first().code shr 4)
    }

    @Test fun fixedClientPreservesAsrAndTtsBinaryFramesWithoutTextConversion() {
        val payload = """{"text":"${"x".repeat(245)}"}""".toByteArray(Charsets.UTF_8)
        val asr = byteArrayOf(0x11, 0x10, 0x10, 0) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
        val tts = byteArrayOf(0x11, 0x14, 0x10, 0) + ByteBuffer.allocate(4).putInt(1).array() +
            ByteBuffer.allocate(4).putInt(2).array() + "{}".toByteArray()
        val captured = CapturingSocket()
        val fixed = DoubaoBinaryWsClient()
        fixed.javaClass.getDeclaredField("socket").apply { isAccessible = true }.set(fixed, captured)
        for (packet in listOf(asr, tts)) {
            assertTrue(fixed.send(packet))
            assertNull(captured.text)
            assertArrayEquals(packet, captured.binary!!.toByteArray())
        }
        fixed.stopConnection()
        assertFalse(fixed.send(asr))
    }
}
