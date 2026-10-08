package com.izumiapps.cameramute.ui

import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import org.junit.Assert.*
import org.junit.Test

class EnablePreparationTest {
    private val ready = MainUiState(
        shizukuState = ShizukuConnectionState.READY, forceUseState = ForceUseState.GenuineEnforced,
        consentAccepted = true, notificationPermissionGranted = true, silentOrVibrate = true)

    @Test fun `normal ringer mode explains disabled enable even after refresh`() {
        val state = ready.copy(silentOrVibrate = false)
        assertEquals(EnableStep.SILENT_MODE, state.enableStep())
        assertFalse(state.canEnable())
        assertTrue(state.copy(silentOrVibrate = true).canEnable())
    }

    @Test fun `preparation advances through consent notification ringer and ready`() {
        val start = ready.copy(consentAccepted = false, notificationPermissionGranted = false, silentOrVibrate = false)
        assertEquals(EnableStep.CONSENT, start.enableStep())
        val consent = start.copy(consentAccepted = true)
        assertEquals(EnableStep.NOTIFICATIONS, consent.enableStep())
        val notified = consent.copy(notificationPermissionGranted = true)
        assertEquals(EnableStep.SILENT_MODE, notified.enableStep())
        assertEquals(EnableStep.READY, notified.copy(silentOrVibrate = true).enableStep())
    }

    @Test fun `connection recovery and safety blockers are explained without enabling`() {
        val cases = listOf(
            ready.copy(isBusy = true) to EnableStep.CHECKING,
            ready.copy(shizukuState = ShizukuConnectionState.NOT_AVAILABLE) to EnableStep.CONNECT_SHIZUKU,
            ready.copy(shizukuState = ShizukuConnectionState.PERMISSION_DENIED) to EnableStep.ALLOW_SHIZUKU,
            ready.copy(forceUseState = null) to EnableStep.READ_STATE,
            ready.copy(forceUseState = ForceUseState.UnmanagedUnforced) to EnableStep.UNMANAGED,
            ready.copy(forceUseState = ForceUseState.Unexpected(7)) to EnableStep.UNEXPECTED,
        )
        cases.forEach { (state, step) ->
            assertEquals(step, state.enableStep())
            assertFalse(state.canEnable())
        }
    }
}
