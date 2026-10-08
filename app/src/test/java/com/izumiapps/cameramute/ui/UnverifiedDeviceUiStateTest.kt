package com.izumiapps.cameramute.ui

import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnverifiedDeviceUiStateTest {
    private val ready = MainUiState(
        deviceUnverified = true,
        shizukuState = ShizukuConnectionState.READY,
        forceUseState = ForceUseState.GenuineEnforced,
        consentAccepted = true, notificationPermissionGranted = true, silentOrVibrate = true,
    )

    @Test fun `unverified devices use generic consent`() {
        assertTrue(ready.canEnable())
        assertFalse(ready.copy(consentAccepted = false).canEnable())
    }

    @Test fun `recovery remains available without safety consent`() {
        assertTrue(ready.copy(forceUseState = ForceUseState.MuteAssistActive, consentAccepted = false).canRestore())
        assertTrue(ready.copy(forceUseState = null, consentAccepted = false).canRestore())
    }

    @Test fun `unowned zero is never usable or restorable`() {
        val state = ready.copy(forceUseState = ForceUseState.UnmanagedUnforced)
        assertFalse(state.canEnable())
        assertFalse(state.canRestore())
    }
}
