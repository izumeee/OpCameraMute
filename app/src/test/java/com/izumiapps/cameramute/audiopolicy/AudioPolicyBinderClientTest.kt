package com.izumiapps.cameramute.audiopolicy

import com.izumiapps.cameramute.shizuku.IAudioPolicyUserService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioPolicyBinderClientTest {

    private class FakeUserService : IAudioPolicyUserService.Stub() {
        var current = AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED
        var getCallCount = 0
        var setCallCount = 0
        var lastConfig: Int? = null

        override fun getForSystem(): Int {
            getCallCount += 1
            return current
        }

        override fun setForSystem(config: Int) {
            setCallCount += 1
            lastConfig = config
            current = config
        }

        override fun destroy() = Unit
    }

    @Test
    fun `get delegates only FOR_SYSTEM to the named user service`() {
        val service = FakeUserService()
        val client = AudioPolicyBinderClient(service)

        assertEquals(
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
            client.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM),
        )
        assertEquals(1, service.getCallCount)
    }

    @Test
    fun `get rejects any usage other than FOR_SYSTEM before IPC`() {
        val service = FakeUserService()
        val client = AudioPolicyBinderClient(service)

        assertThrows(IllegalArgumentException::class.java) {
            client.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM - 1)
        }
        assertEquals(0, service.getCallCount)
    }

    @Test
    fun `set accepts only FORCE_NONE and FORCE_SYSTEM_ENFORCED`() {
        val service = FakeUserService()
        val client = AudioPolicyBinderClient(service)

        client.setForceUse(
            AudioPolicyConstants.USAGE_FOR_SYSTEM,
            AudioPolicyConstants.CONFIG_FORCE_NONE,
        )
        client.setForceUse(
            AudioPolicyConstants.USAGE_FOR_SYSTEM,
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
        )

        assertEquals(2, service.setCallCount)
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, service.lastConfig)
    }

    @Test
    fun `set rejects another usage or config before IPC`() {
        val service = FakeUserService()
        val client = AudioPolicyBinderClient(service)

        assertThrows(IllegalArgumentException::class.java) {
            client.setForceUse(
                AudioPolicyConstants.USAGE_FOR_SYSTEM - 1,
                AudioPolicyConstants.CONFIG_FORCE_NONE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.setForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM, 12)
        }
        assertEquals(0, service.setCallCount)
    }

    @Test
    fun `invalidated port refuses later IPC`() {
        val service = FakeUserService()
        var valid = true
        val client = AudioPolicyBinderClient(service, isValid = { valid })
        valid = false

        assertThrows(IllegalStateException::class.java) {
            client.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
        }
        assertEquals(0, service.getCallCount)
    }
}
