package com.spake.brony.doubaoassistant.data.network

import android.content.Context
import android.util.Log
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.BuildConfig
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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

    // 使用全新非流式请求，只返回判断结果；不关联历史、不保存响应或判断文案。
    suspend fun classify(text: String, instruction: String): Boolean {
        if (closed.get()) throw IOException(context.getString(R.string.chat_request_failed))
        val credentials = CredentialStore(context).load()
        if (!credentials.ready) throw IOException(context.getString(R.string.chat_credentials_missing))
        val request = Request.Builder()
            .url("https://ark.cn-beijing.volces.com/api/v3/responses")
            .header("Authorization", "Bearer ${credentials.arkApiKey}")
            .header("Accept", "application/json")
            .post(classificationBody(text, instruction).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            fun fail(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IOException(message))
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    fail(context.getString(R.string.chat_connection_failed))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            fail(httpError(it, credentials::redact))
                            return
                        }
                        // 在 OkHttp 回调线程读取，保持取消处理直到整个判断响应解析完成。
                        val decision = try {
                            val source = it.body?.source() ?: error("Empty classification response")
                            source.request(65_537)
                            check(source.buffer.size <= 65_536)
                            parseClassificationResponse(JSONObject(source.readUtf8()))
                        } catch (_: IOException) {
                            fail(context.getString(R.string.chat_connection_interrupted))
                            return
                        } catch (_: Exception) {
                            fail(context.getString(R.string.voice_intent_classification_invalid))
                            return
                        }
                        if (continuation.isActive) continuation.resume(decision)
                    }
                }
            })
        }
    }

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
                            trySend(parser.progress.copy(error = httpError(response, credentials::redact)))
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

    private fun httpError(response: Response, redact: (String) -> String): String {
        val fallback = context.getString(R.string.chat_http_failed, response.code)
        // 只读取有限的错误响应，不回显请求头、请求正文或整个服务端响应。
        val error = try { JSONObject(response.peekBody(16_384).string()).optJSONObject("error") }
        catch (_: Exception) { null }
        if (error == null) return fallback
        fun field(name: String, maxLength: Int): String? = (error.opt(name) as? String)?.let { value ->
            redact(value).filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()
                .take(maxLength).takeIf { it.isNotBlank() }
        }
        val code = field("code", 256)
        val message = field("message", 2_000)
        if (code == null && message == null) return fallback
        val missing = context.getString(R.string.chat_error_not_provided)
        val detail = context.getString(R.string.chat_http_error_detail, response.code, code ?: missing, message ?: missing)
        if (BuildConfig.DEBUG) Log.w("DoubaoAi", detail)
        return detail
    }

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
        internal fun classificationBody(text: String, instruction: String): JSONObject {
            require(instruction.isNotBlank() && instruction.length <= 2048)
            val body = requestBody(text, null)
            val user = body.getJSONArray("input").getJSONObject(0)
            return body.put("stream", false).put("store", false).put("max_output_tokens", 16)
                .put("input", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instruction))
                    .put(user))
        }

        internal fun parseClassificationResponse(response: JSONObject): Boolean {
            check(response.optString("status") == "completed")
            val output = response.getJSONArray("output")
            val text = StringBuilder()
            for (index in 0 until output.length()) {
                val item = output.getJSONObject(index)
                if (item.optString("type") != "message" || item.optString("role") != "assistant") continue
                val content = item.getJSONArray("content")
                for (partIndex in 0 until content.length()) {
                    val part = content.getJSONObject(partIndex)
                    if (part.optString("type") == "output_text") {
                        text.append(part.opt("text") as? String ?: error("Invalid classification text"))
                    }
                }
            }
            return when (text.toString().trim()) {
                "true" -> true
                "false" -> false
                else -> error("Classification must be true or false")
            }
        }

        internal fun requestBody(text: String, previousId: String?): JSONObject {
            require(text.isNotBlank() && text.length <= 8192) { "问题为空或过长" }
            return JSONObject().apply {
                put("model", BuildConfig.DOUBAO_CHAT_MODEL)
                put("stream", true)
                put("store", true) // previous_response_id 需要平台保存对应上下文。
                put("thinking", JSONObject().put("type", "disabled"))
                put("max_output_tokens", 2048)
                // 显式使用普通文本输出，JSON 由提示词约定，不沿用旧轮的结构化格式。
                put("text", JSONObject().put("format", JSONObject().put("type", "text")))
                put("input", JSONArray().put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))))
                if (!previousId.isNullOrBlank()) put("previous_response_id", previousId)
            }
        }
    }
}
