package com.izumiapps.cameramute.shizuku

import com.izumiapps.cameramute.audiopolicy.AudioPolicyConstants
import com.izumiapps.cameramute.audiopolicy.ForSystemAudioPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioPolicyUserServiceTest {

    private class FakeNamedApi : ForSystemAudioPolicy {
        var current = AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED
        var getCallCount = 0
        var setCallCount = 0

        override fun getForSystem(): Int {
            getCallCount += 1
            return current
        }

        override fun setForSystem(config: Int) {
            setCallCount += 1
            current = config
        }
    }

    @Test
    fun `construction alone never changes audio policy`() {
        val api = FakeNamedApi()

        AudioPolicyUserService(api)

        assertEquals(0, api.getCallCount)
        assertEquals(0, api.setCallCount)
    }

    @Test
    fun `public binder surface reads only FOR_SYSTEM`() {
        val api = FakeNamedApi()
        val service = AudioPolicyUserService(api)

        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, service.getForSystem())
        assertEquals(1, api.getCallCount)
    }

    @Test
    fun `public binder surface accepts only zero and eleven`() {
        val api = FakeNamedApi()
        val service = AudioPolicyUserService(api)

        service.setForSystem(AudioPolicyConstants.CONFIG_FORCE_NONE)
        service.setForSystem(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED)

        assertEquals(2, api.setCallCount)
        assertThrows(IllegalArgumentException::class.java) {
            service.setForSystem(12)
        }
        assertEquals(2, api.setCallCount)
    }
}
