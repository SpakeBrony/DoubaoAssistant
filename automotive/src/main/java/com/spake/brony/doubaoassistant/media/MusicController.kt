package com.spake.brony.doubaoassistant.media

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.conversation.AiOperationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 使用 QQ 音乐自身的入口控制播放、搜索歌曲和播放账户收藏。 */
internal class MusicController(context: Context) {
    private val appContext = context.applicationContext

    // 歌名只作为 JSON 参数编码，不能拼成 URI 或猜测播放器内部歌曲 ID。
    suspend fun playSong(song: String): Boolean {
        val title = song.trim().takeIf { it.isNotEmpty() && it.length <= 256 } ?: return false
        // shortcut: QQ 音乐此入口只搜索歌名；自动播放需要另行接入获授权的歌曲查询接口。
        return openQqMusic("ui/search", JSONObject().put("type", "song").put("query", title))
    }

    // 使用 QQ 音乐当前登录账户的收藏，direct_play 请求打开后直接播放。
    suspend fun playFavorites(): Boolean = openQqMusic(
        "ui/myTab", JSONObject().put("tab", "fav").put("direct_play", true),
    )

    suspend fun control(action: String): Boolean {
        val path = when (action) {
            "next" -> "media/nextSong" // QQ 音乐下一首。
            "previous" -> "media/prevSong" // QQ 音乐上一首。
            "play" -> "media/resumeSong" // 恢复 QQ 音乐当前播放。
            "pause" -> "media/pauseSong" // 暂停 QQ 音乐，不使用播放/暂停切换键。
            else -> return false
        }
        return openQqMusic(path)
    }

    private suspend fun openQqMusic(path: String, params: JSONObject? = null): Boolean = withContext(Dispatchers.Main.immediate) {
        val uri = Uri.Builder().scheme("qqmusic").authority("qq.com").path(path).apply {
            params?.let { appendQueryParameter("p", it.toString()) }
        }.build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.tencent.qqmusic")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (intent.resolveActivity(appContext.packageManager) == null)
                throw AiOperationFailure(R.string.music_search_unavailable)
            appContext.startActivity(intent)
            // shortcut: QQ 音乐唤起接口没有播放结果回调，true 仅表示请求已发送。
            true
        } catch (error: ActivityNotFoundException) {
            Log.w("MusicController", "QQ Music is unavailable", error)
            throw AiOperationFailure(R.string.music_search_unavailable)
        } catch (error: SecurityException) {
            Log.w("MusicController", "QQ Music cannot be opened", error)
            false
        }
    }
}
