package com.izumiapps.cameramute.ui

import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    private val ready = MainUiState(
        shizukuState = ShizukuConnectionState.READY,
        forceUseState = ForceUseState.GenuineEnforced,
        consentAccepted = true,
        notificationPermissionGranted = true,
        silentOrVibrate = true,
    )

    @Test fun unsupportedBackend_overridesEveryCachedAudioState_andBlocksOperations() {
        val audioStates = listOf<ForceUseState?>(
            null,
            ForceUseState.GenuineEnforced,
            ForceUseState.MuteAssistActive,
            ForceUseState.UnmanagedUnforced,
            ForceUseState.Unexpected(7),
        )
        audioStates.forEach { audioState ->
            val state = ready.copy(
                shizukuState = ShizukuConnectionState.UNSUPPORTED_BACKEND,
                forceUseState = audioState,
            )
            assertEquals(PrimaryStatus.SHIZUKU_UNSUPPORTED_BACKEND, state.primaryStatus())
            assertEquals(EnableStep.CHANGE_SHIZUKU_BACKEND, state.enableStep())
            assertFalse(state.canEnable())
            assertFalse(state.canRestore())
            assertFalse(state.canRequestShizukuPermission())
        }
    }

    @Test fun busyUnsupportedBackend_keepsEveryOperationBlocked() {
        val state = ready.copy(
            shizukuState = ShizukuConnectionState.UNSUPPORTED_BACKEND,
            forceUseState = ForceUseState.MuteAssistActive,
            isBusy = true,
        )
        assertEquals(PrimaryStatus.SHIZUKU_UNSUPPORTED_BACKEND, state.primaryStatus())
        assertEquals(EnableStep.CHECKING, state.enableStep())
        assertFalse(state.canEnable())
        assertFalse(state.canRestore())
        assertFalse(state.canRequestShizukuPermission())
    }

    @Test fun permissionRequest_isAvailableOnlyForAnIdleSupportedConnection() {
        ShizukuConnectionState.entries.forEach { connection ->
            val state = ready.copy(shizukuState = connection)
            assertEquals(connection == ShizukuConnectionState.PERMISSION_DENIED, state.canRequestShizukuPermission())
            assertFalse(state.copy(isBusy = true).canRequestShizukuPermission())
        }
    }

    @Test fun changingToSupportedBackend_rechecksPermissionAndAudioBeforeEnabling() {
        val root = ready.copy(shizukuState = ShizukuConnectionState.UNSUPPORTED_BACKEND)
        val awaitingPermission = root.copy(shizukuState = ShizukuConnectionState.PERMISSION_DENIED)
        assertEquals(EnableStep.ALLOW_SHIZUKU, awaitingPermission.enableStep())
        assertTrue(awaitingPermission.canRequestShizukuPermission())
        assertFalse(awaitingPermission.canEnable())
        val awaitingAudio = awaitingPermission.copy(shizukuState = ShizukuConnectionState.READY, forceUseState = null)
        assertEquals(EnableStep.READ_STATE, awaitingAudio.enableStep())
        assertFalse(awaitingAudio.canEnable())
        assertTrue(awaitingAudio.copy(forceUseState = ForceUseState.GenuineEnforced).canEnable())
    }
}
