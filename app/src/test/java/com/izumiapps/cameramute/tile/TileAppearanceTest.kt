package com.izumiapps.cameramute.tile

import android.service.quicksettings.Tile
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class TileAppearanceTest {
    private val audioStates = listOf(
        null,
        ForceUseState.GenuineEnforced,
        ForceUseState.MuteAssistActive,
        ForceUseState.UnmanagedUnforced,
        ForceUseState.Unexpected(7),
    )

    @Test
    fun unsupportedBackendIsShownInsteadOfAnyCachedAudioState() {
        for (audioState in audioStates) {
            for (busy in listOf(false, true)) {
                assertEquals(
                    TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_shizuku_unsupported_backend),
                    tileAppearance(ShizukuConnectionState.UNSUPPORTED_BACKEND, audioState, busy),
                )
            }
        }
    }

    @Test
    fun readyConnectionShowsTheReadAudioState() {
        val expected = listOf(
            ForceUseState.GenuineEnforced to TileAppearance(Tile.STATE_INACTIVE, R.string.state_genuine),
            ForceUseState.MuteAssistActive to TileAppearance(Tile.STATE_ACTIVE, R.string.state_mute_assist),
            ForceUseState.UnmanagedUnforced to
                TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unmanaged_unforced),
            ForceUseState.Unexpected(7) to TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unexpected_value),
            null to TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unknown),
        )
        for ((audioState, appearance) in expected) {
            assertEquals(appearance, tileAppearance(ShizukuConnectionState.READY, audioState, isBusy = false))
        }
    }

    @Test
    fun busyOrUnreadyConnectionNeverShowsACachedAudioState() {
        val unknown = TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unknown)
        for (audioState in audioStates) {
            assertEquals(unknown, tileAppearance(ShizukuConnectionState.READY, audioState, isBusy = true))
            for (connection in listOf(
                ShizukuConnectionState.NOT_AVAILABLE,
                ShizukuConnectionState.PERMISSION_DENIED,
            )) {
                assertEquals(unknown, tileAppearance(connection, audioState, isBusy = false))
            }
        }
    }
}
