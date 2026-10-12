package com.spake.brony.doubaoassistant.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionUiStateTest {
    @Test fun firstRequestDoesNotIncorrectlyGoToSettings() {
        assertFalse(PermissionUiState().openSettings)
    }
    @Test fun ordinaryDenialAllowsAnotherRequest() {
        assertFalse(PermissionUiState(requestedBefore = true, showRationale = true).openSettings)
    }
    @Test fun permanentDenialRequiresSystemSettings() {
        assertTrue(PermissionUiState(requestedBefore = true, showRationale = false).openSettings)
    }
    @Test fun grantedPermissionNeverShowsTheSettingsRecoveryAction() {
        assertFalse(PermissionUiState(granted = true, requestedBefore = true).openSettings)
    }
}
