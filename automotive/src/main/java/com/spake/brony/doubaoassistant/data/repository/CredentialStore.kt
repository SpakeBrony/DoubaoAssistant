package com.spake.brony.doubaoassistant.data.repository

import android.content.Context
import android.annotation.SuppressLint
import com.spake.brony.doubaoassistant.BuildConfig
import org.json.JSONObject

internal data class ServiceCredentials(
    val apiKey: String = "",
    val appId: String = "",
    val accessToken: String = "",
    val arkApiKey: String = "",
) {
    private fun valid(value: String) = value.length in 1..4096 && value.all { it.code in 33..126 }
    val speechReady: Boolean get() = if (apiKey.isNotEmpty()) valid(apiKey) else valid(appId) && valid(accessToken)
    val ready: Boolean get() = speechReady && valid(arkApiKey)

    fun redact(message: String): String = listOf(apiKey, appId, accessToken, arkApiKey)
        .filter { it.isNotBlank() }.sortedByDescending { it.length }
        .fold(message) { text, key -> text.replace(key, "[已隐藏]") }

    override fun toString() = "ServiceCredentials([已隐藏])"
}

// 两种构建共用运行时配置；通用版的 BuildConfig 默认值全部为空。
internal class CredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun load(): ServiceCredentials {
        val payload = preferences.getString("payload", null)
            ?: return ServiceCredentials(BuildConfig.DOUBAO_ASR_API_KEY, BuildConfig.DOUBAO_ASR_APP_ID,
                BuildConfig.DOUBAO_ASR_ACCESS_TOKEN, BuildConfig.DOUBAO_ARK_API_KEY)
        return try {
            val value = JSONObject(CredentialEncryption.decrypt(payload))
            ServiceCredentials(value.getString("apiKey"), value.getString("appId"),
                value.getString("accessToken"), value.getString("arkApiKey"))
        } catch (_: Exception) {
            // 密钥失效或数据损坏时回到配置页，不能偷偷改用 APK 内的默认凭证。
            ServiceCredentials()
        }
    }

    val isConfigured: Boolean get() = preferences.contains("payload") && load().ready

    @SuppressLint("ApplySharedPref", "UseKtx") // 必须取得 commit 的落盘结果，失败时不能进入聊天。
    fun save(value: ServiceCredentials): Boolean {
        require(value.ready) { "服务凭证不完整或格式无效" }
        return try {
            val json = JSONObject().put("apiKey", value.apiKey).put("appId", value.appId)
                .put("accessToken", value.accessToken).put("arkApiKey", value.arkApiKey).toString()
            val encrypted = CredentialEncryption.encrypt(json)
            val previous = preferences.getString("payload", null)
            if (preferences.edit().putString("payload", encrypted).commit()) true
            else {
                // commit 失败也会更新内存值，恢复它以免下次权限回调误判已配置。
                preferences.edit().putString("payload", previous).commit()
                false
            }
        } catch (_: Exception) { false }
    }
}
