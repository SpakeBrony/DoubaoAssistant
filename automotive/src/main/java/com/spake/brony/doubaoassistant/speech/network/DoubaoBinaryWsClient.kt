package com.spake.brony.doubaoassistant.speech.network

import com.bytedance.speech.speechengine.net.ws.IWsClient
import com.bytedance.speech.speechengine.net.ws.IWsListener
import com.bytedance.speech.speechengine.net.ws.WsConnectionConfig
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

// ASR / 双向 TTS 是二进制协议，不能用 SDK 默认客户端的 JSON 文本帧猜测逻辑。
internal class DoubaoBinaryWsClient : IWsClient {
    private var listener: IWsListener? = null
    private val generation = AtomicLong()
    @Volatile private var socket: WebSocket? = null
    private var client: OkHttpClient? = null

    override fun init(listener: IWsListener): Boolean { this.listener = listener; return true }

    @Synchronized override fun startConnection(config: WsConnectionConfig): Boolean {
        stopConnection()
        val callbacks = listener ?: return false
        val id = generation.incrementAndGet()
        val http = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.toLong(), TimeUnit.MILLISECONDS).build()
        client = http
        val request = Request.Builder().url(config.url).apply {
            config.headers?.forEach { (key, value) -> addHeader(key, value) }
        }.build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation.get() != id) return
                response.header("X-Tt-Logid")?.let(callbacks::onLogid)
                callbacks.onConnected(response.message)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (generation.get() == id) callbacks.onMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() == id) callbacks.onMessage(text.toByteArray(Charsets.UTF_8))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (generation.get() == id) callbacks.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                if (generation.get() == id) callbacks.onError(response?.code ?: 4003,
                    error.message ?: response?.message ?: error.toString())
            }
        })
        return true
    }

    override fun send(data: ByteArray): Boolean = socket?.send(data.toByteString()) ?: false

    @Synchronized override fun stopConnection(): Boolean {
        generation.incrementAndGet()
        socket?.cancel()
        socket = null
        client?.let {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        client = null
        return true
    }
}
