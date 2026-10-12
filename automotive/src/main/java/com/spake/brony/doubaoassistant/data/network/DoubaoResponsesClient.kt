package com.spake.brony.doubaoassistant.data.network

import android.content.Context
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.BuildConfig
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal class DoubaoResponsesClient(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val closed = AtomicBoolean()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false) // 付费生成请求不自动重发。
        .build()

    fun stream(text: String, previousId: String?): Flow<ResponseProgress> = callbackFlow {
        val credentials = CredentialStore(context).load()
        if (!credentials.ready) {
            trySend(ResponseProgress(error = context.getString(R.string.chat_credentials_missing)))
            close()
            return@callbackFlow
        }
        val request = Request.Builder()
            .url("https://ark.cn-beijing.volces.com/api/v3/responses")
            .header("Authorization", "Bearer ${credentials.arkApiKey}")
            .header("Accept", "text/event-stream")
            .post(requestBody(text, previousId).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) trySend(ResponseProgress(error = context.getString(R.string.chat_connection_failed)))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                val parser = ResponseStreamParser(context::getString)
                try {
                    response.use {
                        if (!response.isSuccessful) {
                            trySend(parser.progress.copy(error = context.getString(R.string.chat_http_failed, response.code)))
                            return
                        }
                        val body = response.body ?: error("豆包返回空响应")
                        parser.read(body.source()) { trySend(it) }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled()) {
                        // 不回显服务端原始响应或解析异常，避免凭证/用户内容进入错误文案。
                        val message = context.getString(R.string.chat_connection_interrupted)
                        trySend(parser.progress.copy(completedId = null, error = message))
                    }
                } finally { close() }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.CONFLATED) // 每次发送累计全文，可合并 UI 更新而不丢 token。

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val executor = client.dispatcher.executorService
        // 关闭池中的 SSL 连接可能写网络数据，不能在 Service.onDestroy 的主线程执行。
        executor.execute {
            try {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            } finally { executor.shutdown() }
        }
    }

    companion object {
        internal fun requestBody(text: String, previousId: String?): JSONObject {
            require(text.isNotBlank() && text.length <= 8192) { "问题为空或过长" }
            return JSONObject().apply {
                put("model", BuildConfig.DOUBAO_CHAT_MODEL)
                put("stream", true)
                put("store", true) // previous_response_id 需要平台保存对应上下文。
                put("thinking", JSONObject().put("type", "disabled"))
                put("max_output_tokens", 2048)
                put("input", JSONArray().put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))))
                if (!previousId.isNullOrBlank()) put("previous_response_id", previousId)
            }
        }
    }
}
