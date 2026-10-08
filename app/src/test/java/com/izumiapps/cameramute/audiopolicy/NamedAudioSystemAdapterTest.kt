package com.izumiapps.cameramute.audiopolicy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NamedAudioSystemAdapterTest {

    class ValidAudioSystem {
        companion object {
            @JvmField
            val FOR_SYSTEM = 4

            @JvmField
            val FORCE_NONE = 0

            @JvmField
            val FORCE_SYSTEM_ENFORCED = 11

            var current = FORCE_SYSTEM_ENFORCED
            var lastUsage: Int? = null
            var lastConfig: Int? = null
            var setResult = 0

            @JvmStatic
            fun getForceUse(usage: Int): Int {
                lastUsage = usage
                return current
            }

            @JvmStatic
            fun setForceUse(usage: Int, config: Int): Int {
                lastUsage = usage
                lastConfig = config
                if (setResult == 0) current = config
                return setResult
            }
        }
    }

    class WrongConstantsAudioSystem {
        companion object {
            @JvmField val FOR_SYSTEM = 5
            @JvmField val FORCE_NONE = 0
            @JvmField val FORCE_SYSTEM_ENFORCED = 11

            @JvmStatic fun getForceUse(usage: Int): Int = usage
            @JvmStatic fun setForceUse(usage: Int, config: Int): Int = usage + config
        }
    }

    class InstanceMethodsAudioSystem {
        companion object {
            @JvmField val FOR_SYSTEM = 4
            @JvmField val FORCE_NONE = 0
            @JvmField val FORCE_SYSTEM_ENFORCED = 11
        }

        fun getForceUse(usage: Int): Int = usage
        fun setForceUse(usage: Int, config: Int): Int = usage + config
    }

    class WrongReturnTypeAudioSystem {
        companion object {
            @JvmField val FOR_SYSTEM = 4
            @JvmField val FORCE_NONE = 0
            @JvmField val FORCE_SYSTEM_ENFORCED = 11

            @JvmStatic fun getForceUse(usage: Int): Int = usage
            @JvmStatic fun setForceUse(usage: Int, config: Int): Long = (usage + config).toLong()
        }
    }

    class JniFailureAudioSystem {
        companion object {
            @JvmField val FOR_SYSTEM = 4
            @JvmField val FORCE_NONE = 0
            @JvmField val FORCE_SYSTEM_ENFORCED = 11

            @JvmStatic
            fun getForceUse(@Suppress("UNUSED_PARAMETER") usage: Int): Int {
                throw UnsatisfiedLinkError("simulated JNI mismatch")
            }

            @JvmStatic fun setForceUse(usage: Int, config: Int): Int = usage + config
        }
    }

    @Test
    fun `resolves exact names and uses the verified device constants`() {
        ValidAudioSystem.current = 11
        ValidAudioSystem.lastUsage = null
        ValidAudioSystem.lastConfig = null
        ValidAudioSystem.setResult = 0
        val adapter = NamedAudioSystemAdapter(ValidAudioSystem::class.java)

        assertEquals(11, adapter.getForSystem())
        adapter.setForSystem(0)

        assertEquals(4, ValidAudioSystem.lastUsage)
        assertEquals(0, ValidAudioSystem.lastConfig)
        assertEquals(0, ValidAudioSystem.current)
    }

    @Test
    fun `rejects a device constant mismatch before invoking a method`() {
        assertThrows(UnsupportedOperationException::class.java) {
            NamedAudioSystemAdapter(WrongConstantsAudioSystem::class.java)
        }
    }

    @Test
    fun `rejects non static methods`() {
        assertThrows(UnsupportedOperationException::class.java) {
            NamedAudioSystemAdapter(InstanceMethodsAudioSystem::class.java)
        }
    }

    @Test
    fun `rejects a setter with a different return signature`() {
        assertThrows(UnsupportedOperationException::class.java) {
            NamedAudioSystemAdapter(WrongReturnTypeAudioSystem::class.java)
        }
    }

    @Test
    fun `rejects config outside the two reviewed FOR_SYSTEM values`() {
        val adapter = NamedAudioSystemAdapter(ValidAudioSystem::class.java)

        assertThrows(IllegalArgumentException::class.java) {
            adapter.setForSystem(12)
        }
    }

    @Test
    fun `nonzero platform setter result is a failure`() {
        ValidAudioSystem.setResult = -1
        val adapter = NamedAudioSystemAdapter(ValidAudioSystem::class.java)

        try {
            assertThrows(IllegalStateException::class.java) {
                adapter.setForSystem(0)
            }
        } finally {
            ValidAudioSystem.setResult = 0
        }
    }

    @Test
    fun `JNI invocation failure is wrapped as an unavailable named API`() {
        val adapter = NamedAudioSystemAdapter(JniFailureAudioSystem::class.java)

        val failure = assertThrows(IllegalStateException::class.java) {
            adapter.getForSystem()
        }

        assertEquals(UnsatisfiedLinkError::class.java, failure.cause?.javaClass)
    }
}
