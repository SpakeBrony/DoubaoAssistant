package com.spake.brony.doubaoassistant.data.repository

import android.annotation.SuppressLint
import android.content.Context
import com.spake.brony.doubaoassistant.BuildConfig
import org.json.JSONObject

internal data class ServiceCredentials(
    val appId: String = "",
    val accessToken: String = "",
    val arkApiKey: String = "",
) {
    private fun valid(value: String) = value.length in 1..4096 && value.all { it.code in 33..126 }

    val speechReady: Boolean get() = valid(appId) && valid(accessToken)
    val ready: Boolean get() = speechReady && valid(arkApiKey)

    fun redact(message: String): String = listOf(appId, accessToken, arkApiKey)
        .filter { it.isNotBlank() }
        .sortedByDescending { it.length }
        .fold(message) { text, key -> text.replace(key, "[已隐藏]") }

    override fun toString() = "ServiceCredentials([已隐藏])"
}

/** 两种构建共用的凭证存储；通用版的 BuildConfig 默认值为空。 */
internal class CredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ServiceCredentials {
        val payload = preferences.getString(KEY_PAYLOAD, null)
            ?: return ServiceCredentials(
                appId = BuildConfig.DOUBAO_ASR_APP_ID,
                accessToken = BuildConfig.DOUBAO_ASR_ACCESS_TOKEN,
                arkApiKey = BuildConfig.DOUBAO_ARK_API_KEY,
            )
        return try {
            val value = JSONObject(CredentialEncryption.decrypt(payload))
            ServiceCredentials(
                appId = value.getString(KEY_APP_ID),
                accessToken = value.getString(KEY_ACCESS_TOKEN),
                arkApiKey = value.getString(KEY_ARK_API_KEY),
            )
        } catch (_: Exception) {
            // 密钥失效或数据损坏时回到配置页，不能偷偷改用 APK 内的默认凭证。
            ServiceCredentials()
        }
    }

    val isConfigured: Boolean get() = preferences.contains(KEY_PAYLOAD) && load().ready

    @SuppressLint("ApplySharedPref", "UseKtx") // 必须取得 commit 的落盘结果，失败时不能进入聊天。
    fun save(value: ServiceCredentials): Boolean {
        require(value.ready) { "服务凭证不完整或格式无效" }
        return try {
            val json = JSONObject()
                .put(KEY_APP_ID, value.appId)
                .put(KEY_ACCESS_TOKEN, value.accessToken)
                .put(KEY_ARK_API_KEY, value.arkApiKey)
                .toString()
            val encrypted = CredentialEncryption.encrypt(json)
            val previous = preferences.getString(KEY_PAYLOAD, null)
            if (preferences.edit().putString(KEY_PAYLOAD, encrypted).commit()) {
                true
            } else {
                // commit 失败也会更新内存值，恢复它以免下次权限回调误判已配置。
                preferences.edit().putString(KEY_PAYLOAD, previous).commit()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "credentials"
        const val KEY_PAYLOAD = "payload"
        const val KEY_APP_ID = "appId"
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_ARK_API_KEY = "arkApiKey"
    }
}
