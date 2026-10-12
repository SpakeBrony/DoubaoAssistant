package com.spake.brony.doubaoassistant.viewmodel

import com.spake.brony.doubaoassistant.viewmodel.base.BaseViewModel
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionUiState(
    val granted: Boolean = false,
    val requestedBefore: Boolean = false,
    val showRationale: Boolean = false,
    val error: String? = null,
) {
    val openSettings: Boolean get() = !granted && requestedBefore && !showRationale
}

class PermissionViewModel : BaseViewModel() {
    internal var draft = ServiceCredentials()
    internal var formLoaded = false
    internal var submitted = false
    private val mutableUiState = MutableStateFlow(PermissionUiState())
    val uiState = mutableUiState.asStateFlow()

    fun onPermissionChanged(granted: Boolean, requestedBefore: Boolean, showRationale: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(granted = granted,
            requestedBefore = requestedBefore, showRationale = showRationale)
    }

    fun clearError() { mutableUiState.value = mutableUiState.value.copy(error = null) }

    fun onSettingsFailed(message: String) {
        mutableUiState.value = mutableUiState.value.copy(error = message)
    }
}
