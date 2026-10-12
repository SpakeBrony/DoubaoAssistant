package com.spake.brony.doubaoassistant.data.network

import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import java.io.IOException
import okio.BufferedSource
import org.json.JSONObject

// SSE 按空行分隔事件，支持多行 data、注释和 CRLF；只展示 output_text，不展示思考内容。
internal class ResponseStreamParser(private val getString: (Int) -> String) {
    var progress = ResponseProgress()
        private set

    fun read(source: BufferedSource, onProgress: (ResponseProgress) -> Unit) {
        val data = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(256 * 1024L)
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val previous = progress
                    accept(data.toString())
                    if (previous != progress) onProgress(progress)
                    if (progress.completedId != null || progress.error != null) return
                    data.setLength(0)
                }
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                check(data.length <= 256 * 1024) { "豆包流式事件过大" }
            }
        }
        if (data.isNotEmpty()) {
            accept(data.toString())
            onProgress(progress)
            if (progress.completedId != null || progress.error != null) return
        }
        throw IOException("豆包连接提前断开，回复未完成")
    }

    internal fun accept(data: String) {
        if (data == "[DONE]") throw IOException("豆包流缺少完成事件")
        val event = JSONObject(data)
        when (event.optString("type")) {
            "response.output_text.delta" -> {
                val text = progress.text + event.getString("delta")
                check(text.length <= MAX_TEXT) { "豆包回复过长" }
                progress = progress.copy(text = text)
            }
            "response.completed" -> {
                val response = event.getJSONObject("response")
                check(response.getString("status") == "completed") { "豆包回复未完成" }
                val id = response.getString("id")
                check(id.isNotBlank() && id.length <= 512) { "豆包 Response ID 无效" }
                val output = response.optJSONArray("output")
                val text = StringBuilder()
                if (output != null) for (i in 0 until output.length()) {
                    val item = output.getJSONObject(i)
                    if (item.optString("type") != "message") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        when (part.optString("type")) {
                            "output_text" -> text.append(part.optString("text"))
                            "refusal" -> text.append(part.optString("refusal"))
                        }
                    }
                }
                val finalText = if (text.isEmpty()) progress.text else text.toString()
                check(finalText.isNotBlank() && finalText.length <= MAX_TEXT) { "豆包未返回有效回复文字" }
                progress = ResponseProgress(finalText, completedId = id)
            }
            "response.failed", "error" -> progress = progress.copy(error = getString(R.string.chat_response_failed))
            "response.incomplete" -> progress = progress.copy(error = getString(R.string.chat_response_incomplete))
        }
    }

    companion object { private const val MAX_TEXT = 32768 }
}
