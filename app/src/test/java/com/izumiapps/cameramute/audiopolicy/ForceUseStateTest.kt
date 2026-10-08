package com.izumiapps.cameramute.audiopolicy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceUseStateTest {

    @Test
    fun `0 is decoded as MuteAssistActive`() {
        assertEquals(ForceUseState.MuteAssistActive, ForceUseState.fromRaw(0))
    }

    @Test
    fun `11 is decoded as GenuineEnforced`() {
        assertEquals(ForceUseState.GenuineEnforced, ForceUseState.fromRaw(11))
    }

    @Test
    fun `other values are decoded as Unexpected with the raw value preserved`() {
        val state = ForceUseState.fromRaw(3)
        assertTrue(state is ForceUseState.Unexpected)
        assertEquals(3, (state as ForceUseState.Unexpected).rawValue)
    }

    @Test
    fun `negative values are also Unexpected`() {
        assertTrue(ForceUseState.fromRaw(-1) is ForceUseState.Unexpected)
    }
}
