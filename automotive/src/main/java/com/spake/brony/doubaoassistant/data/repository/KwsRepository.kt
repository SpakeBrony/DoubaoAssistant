package com.spake.brony.doubaoassistant.data.repository

import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// 仅保存进程内最新状态，不持有页面、服务或回调，也不缓存音频。
object KwsRepository {
    private val mutableSnapshot = MutableStateFlow(KwsSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableAudioLevel = MutableStateFlow(0f)
    val audioLevel = mutableAudioLevel.asStateFlow()
    internal fun publishAudioLevel(level: Float) { mutableAudioLevel.value = level.coerceIn(0f, 1f) }

    private val mutableUiVisible = MutableStateFlow(false)
    val isUiVisible = mutableUiVisible.asStateFlow()

    fun setUiVisible(visible: Boolean) {
        mutableUiVisible.value = visible
    }

    internal fun publish(snapshot: KwsSnapshot) {
        mutableSnapshot.value = snapshot
    }
}
