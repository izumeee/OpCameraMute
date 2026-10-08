package com.izumiapps.cameramute.audiopolicy

import com.izumiapps.cameramute.device.DeviceGuard
import com.izumiapps.cameramute.device.VerifiedDevices
import com.izumiapps.cameramute.device.VerifiedTestDevice
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import com.izumiapps.cameramute.shizuku.ShizukuGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeShizukuGateway(
    initial: ShizukuConnectionState = ShizukuConnectionState.READY,
) : ShizukuGateway {
    val state = MutableStateFlow(initial)
    var connectionStateReadCount = 0
    var binderRequested = false

    override val connectionState: StateFlow<ShizukuConnectionState>
        get() {
            connectionStateReadCount += 1
            return state
        }
    var requestedCode: Int? = null

    override fun requestPermission(requestCode: Int) {
        requestedCode = requestCode
    }

    override suspend fun obtainAudioPolicyPort(): AudioPolicyPort {
        binderRequested = true
        throw UnsupportedOperationException("このテストではportProvider経由でFakePortを直接渡すため呼ばれない想定")
    }
}

private class FakePort(
    private var current: Int,
    private val throwOnGet: Throwable? = null,
    private val throwOnSet: Throwable? = null,
    private val ignoreSet: Boolean = false,
) : AudioPolicyPort {
    var setCalledWith: Int? = null
    var getCallCount = 0

    override fun getForceUse(usage: Int): Int {
        getCallCount += 1
        throwOnGet?.let { throw it }
        return current
    }

    override fun setForceUse(usage: Int, config: Int) {
        throwOnSet?.let { throw it }
        setCalledWith = config
        if (!ignoreSet) current = config
    }
}

private val verifiedGuard = VerifiedTestDevice.guard()
private val unverifiedGuard = DeviceGuard("Pixel_8", "AP4A.250105.002")

class AudioPolicyRepositoryTest {

    @Test
    fun `enableMuteAssist succeeds from the genuine state`() = runTest {
        val port = FakePort(current = 11)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { port }

        val result = repo.enableMuteAssist()

        assertTrue(result is RepositoryResult.Success)
        assertEquals(ForceUseState.MuteAssistActive, (result as RepositoryResult.Success).value)
        assertEquals(0, port.setCalledWith)
    }

    @Test
    fun `restoreGenuine succeeds from the mute-assist state`() = runTest {
        val port = FakePort(current = 0)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { port }

        val result = repo.restoreGenuine()

        assertTrue(result is RepositoryResult.Success)
        assertEquals(11, port.setCalledWith)
    }

    @Test
    fun `shizuku not available blocks the change and never touches the port`() = runTest {
        val gateway = FakeShizukuGateway(initial = ShizukuConnectionState.NOT_AVAILABLE)
        var portTouched = false
        val repo = AudioPolicyRepository(gateway, verifiedGuard, MemoryAudioRecoveryStore()) {
            portTouched = true
            FakePort(current = 11)
        }

        val result = repo.enableMuteAssist()

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(OperationFailure.ShizukuNotAvailable, (result as RepositoryResult.Failure).reason)
        assertFalse(portTouched)
    }

    @Test
    fun `shizuku permission denied blocks the change`() = runTest {
        val gateway = FakeShizukuGateway(initial = ShizukuConnectionState.PERMISSION_DENIED)
        val repo = AudioPolicyRepository(gateway, verifiedGuard, MemoryAudioRecoveryStore()) { FakePort(current = 11) }

        val result = repo.enableMuteAssist()

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(OperationFailure.ShizukuPermissionDenied, (result as RepositoryResult.Failure).reason)
    }

    @Test
    fun `unsupported Shizuku backend blocks all operations before opening the port`() = runTest {
        val gateway = FakeShizukuGateway(initial = ShizukuConnectionState.UNSUPPORTED_BACKEND)
        var portProviderCalls = 0
        val recovery = MemoryAudioRecoveryStore()
        val repo = AudioPolicyRepository(gateway, verifiedGuard, recovery) {
            portProviderCalls += 1
            FakePort(current = 11)
        }

        for (result in listOf(repo.refreshState(), repo.enableMuteAssist(), repo.restoreGenuine())) {
            assertTrue(result is RepositoryResult.Failure)
            assertEquals(OperationFailure.UnsupportedShizukuBackend, (result as RepositoryResult.Failure).reason)
        }
        assertEquals(0, portProviderCalls)
        assertNull(recovery.load())
    }

    @Test
    fun `unlisted device can enable and restore with a saved original value`() = runTest {
        val port = FakePort(current = 11)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, MemoryAudioRecoveryStore()) { port }
        assertTrue(repo.enableMuteAssist() is RepositoryResult.Success)
        assertTrue(repo.restoreGenuine() is RepositoryResult.Success)
    }

    @Test
    fun `unlisted model can read the named API`() = runTest {
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, MemoryAudioRecoveryStore()) { FakePort(current = 11) }
        assertTrue(repo.refreshState() is RepositoryResult.Success)
    }

    @Test
    fun `OPPO OTA may read named API but remains unverified`() = runTest {
        val gateway = FakeShizukuGateway()
        val port = FakePort(current = 11)
        var portProviderCallCount = 0
        val sameModelUnverifiedBuild = DeviceGuard(
            currentModel = VerifiedDevices.findX9Jp.model,
            currentDisplay = "CPH2797_16.0.11.000(EX01)",
            currentManufacturer = "OPPO",
            currentFingerprint = "test-ota",
        )
        val repo = AudioPolicyRepository(gateway, sameModelUnverifiedBuild, MemoryAudioRecoveryStore()) {
            portProviderCallCount += 1
            port
        }

        val result = repo.refreshState()

        assertEquals(ForceUseState.GenuineEnforced, (result as RepositoryResult.Success).value)
        assertFalse(sameModelUnverifiedBuild.isVerified)
        assertFalse(gateway.binderRequested)
        assertEquals(1, portProviderCallCount)
        assertEquals(1, port.getCallCount)
    }

    @Test
    fun `supported device reads mute assist state zero`() = runTest {
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { FakePort(current = 0) }

        val result = repo.refreshState()

        assertEquals(
            ForceUseState.MuteAssistActive,
            (result as RepositoryResult.Success).value,
        )
    }

    @Test
    fun `supported device reads genuine state eleven`() = runTest {
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { FakePort(current = 11) }

        val result = repo.refreshState()

        assertEquals(
            ForceUseState.GenuineEnforced,
            (result as RepositoryResult.Success).value,
        )
    }

    @Test
    fun `unexpected current value refuses the change (fail closed)`() = runTest {
        val port = FakePort(current = 7)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { port }

        val result = repo.enableMuteAssist()

        assertTrue(result is RepositoryResult.Failure)
        val reason = (result as RepositoryResult.Failure).reason
        assertTrue(reason is OperationFailure.UnexpectedCurrentValue)
        assertEquals(7, (reason as OperationFailure.UnexpectedCurrentValue).rawValue)
        assertNull(port.setCalledWith)
    }

    @Test
    fun `verification mismatch after set is reported as failure, not success`() = runTest {
        val port = FakePort(current = 11, ignoreSet = true)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { port }

        val result = repo.enableMuteAssist()

        assertTrue(result is RepositoryResult.Failure)
        assertTrue((result as RepositoryResult.Failure).reason is OperationFailure.VerificationMismatch)
    }

    @Test
    fun `remote exception during set is reported as failure, not success`() = runTest {
        val port = FakePort(current = 11, throwOnSet = RuntimeException("simulated binder death"))
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { port }

        val result = repo.enableMuteAssist()

        assertTrue(result is RepositoryResult.Failure)
        assertTrue((result as RepositoryResult.Failure).reason is OperationFailure.BinderError)
    }

    @Test
    fun `remote exception during get is reported as failure`() = runTest {
        val port = FakePort(current = 11, throwOnGet = RuntimeException("simulated binder death"))
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) { port }

        val result = repo.refreshState()

        assertTrue(result is RepositoryResult.Failure)
        assertTrue((result as RepositoryResult.Failure).reason is OperationFailure.BinderError)
    }

    @Test
    fun `portProvider throwing is treated as shizuku not available, not a crash`() = runTest {
        val repo = AudioPolicyRepository(FakeShizukuGateway(), verifiedGuard, MemoryAudioRecoveryStore()) {
            throw IllegalStateException("Shizuku is not ready")
        }

        val result = repo.refreshState()

        assertTrue(result is RepositoryResult.Failure)
    }

    private val unverifiedGuard = DeviceGuard("other-oppo", "build", currentManufacturer = "OPPO", currentFingerprint = "test-unverified")

    @Test fun `production verified Find X9 can restore its measured baseline without a journal`() = runTest {
        val record = VerifiedDevices.findX9Jp
        val guard = DeviceGuard(record.model, record.display, currentManufacturer = "OPPO",
            currentFingerprint = "OPPO/CPH2797/OP5E8BL1:16/BP2A.250605.015/B.R4T3_JP.d15960_182c05d_17ca7dc:user/release-keys")
        val port = FakePort(0)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), guard, MemoryAudioRecoveryStore()) { port }
        assertEquals(ForceUseState.MuteAssistActive, (repo.refreshState() as RepositoryResult.Success).value)
        assertEquals(ForceUseState.GenuineEnforced, (repo.restoreGenuine() as RepositoryResult.Success).value)
        assertEquals(11, port.setCalledWith)
    }

    @Test fun `same model and display with another fingerprint cannot use journal-free restoration`() = runTest {
        val record = VerifiedDevices.findX9Jp
        val guard = DeviceGuard(record.model, record.display, currentManufacturer = "OPPO",
            currentFingerprint = "another-fingerprint")
        val port = FakePort(0)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), guard, MemoryAudioRecoveryStore()) { port }
        assertEquals(ForceUseState.UnmanagedUnforced, (repo.refreshState() as RepositoryResult.Success).value)
        assertTrue(repo.restoreGenuine() is RepositoryResult.Failure)
        assertNull(port.setCalledWith)
    }

    @Test fun `historical Find X9 with no fingerprint record cannot guess restore of zero`() = runTest {
        val record = VerifiedDevices.findX9Jp.copy(fingerprint = null)
        val guard = DeviceGuard(record.model, record.display, verifiedDevices = listOf(record),
            currentManufacturer = "OPPO", currentFingerprint = "OPPO/CPH2797/OP5E8BL1:16/BP2A.250605.015/B.R4T3_JP.d15960_182c05d_17ca7dc:user/release-keys")
        val port = FakePort(0)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), guard, MemoryAudioRecoveryStore()) { port }
        assertEquals(ForceUseState.UnmanagedUnforced, (repo.refreshState() as RepositoryResult.Success).value)
        assertTrue(repo.restoreGenuine() is RepositoryResult.Failure)
        assertNull(port.setCalledWith)
    }

    @Test
    fun `reading eleven ends ownership so later external zero cannot be restored`() = runTest {
        val recovery = MemoryAudioRecoveryStore(11)
        var value = 11
        var writes = 0
        val port = object : AudioPolicyPort {
            override fun getForceUse(usage: Int) = value
            override fun setForceUse(usage: Int, config: Int) { writes++ }
        }
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertEquals(ForceUseState.GenuineEnforced, (repo.refreshState() as RepositoryResult.Success).value)
        assertNull(recovery.original)
        value = 0
        assertEquals(ForceUseState.UnmanagedUnforced, (repo.refreshState() as RepositoryResult.Success).value)
        assertTrue(repo.restoreGenuine() is RepositoryResult.Failure)
        assertEquals(0, writes)
    }

    @Test
    fun `unverified OPPO saves original before setting then restores across repository recreation`() = runTest {
        val recovery = MemoryAudioRecoveryStore()
        var current = 11
        val port = object : AudioPolicyPort {
            override fun getForceUse(usage: Int) = current
            override fun setForceUse(usage: Int, config: Int) {
                assertEquals(11, recovery.original)
                current = config
            }
        }
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertTrue(repo.enableMuteAssist() is RepositoryResult.Success)
        assertEquals(0, current)
        val recreated = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertEquals(ForceUseState.MuteAssistActive, (recreated.refreshState() as RepositoryResult.Success).value)
        assertTrue(recreated.restoreGenuine() is RepositoryResult.Success)
        assertEquals(11, current)
        assertNull(recovery.original)
    }

    @Test
    fun `unowned zero on unverified device is not called active and cannot be changed`() = runTest {
        val port = FakePort(0)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, MemoryAudioRecoveryStore()) { port }
        assertEquals(ForceUseState.UnmanagedUnforced, (repo.refreshState() as RepositoryResult.Success).value)
        assertTrue(repo.enableMuteAssist() is RepositoryResult.Failure)
        assertTrue(repo.restoreGenuine() is RepositoryResult.Failure)
        assertNull(port.setCalledWith)
    }

    @Test
    fun `failed original save prevents enabling mute assist`() = runTest {
        val port = FakePort(11)
        val recovery = MemoryAudioRecoveryStore().apply { failSave = true }
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertEquals(OperationFailure.RecoveryStateUnavailable, (repo.enableMuteAssist() as RepositoryResult.Failure).reason)
        assertNull(port.setCalledWith)
    }

    @Test
    fun `corrupt recovery value or unreadable record never allows unverified restore`() = runTest {
        for (recovery in listOf(MemoryAudioRecoveryStore(7), MemoryAudioRecoveryStore(11).apply { failLoad = true })) {
            val port = FakePort(0)
            val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
            assertTrue(repo.restoreGenuine() is RepositoryResult.Failure)
            assertNull(port.setCalledWith)
        }
    }

    @Test
    fun `failed setter keeps recovery journal for a later restore`() = runTest {
        val recovery = MemoryAudioRecoveryStore()
        val port = FakePort(11, throwOnSet = IllegalStateException("transport lost"))
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertTrue(repo.enableMuteAssist() is RepositoryResult.Failure)
        assertEquals(11, recovery.original)
    }

    @Test
    fun `failed readback retains recovery journal and never reports success`() = runTest {
        val recovery = MemoryAudioRecoveryStore()
        val port = FakePort(11, ignoreSet = true)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertTrue(repo.enableMuteAssist() is RepositoryResult.Failure)
        assertEquals(11, recovery.original)
    }

    @Test
    fun `restore already eleven is a verified no-op without setter`() = runTest {
        val recovery = MemoryAudioRecoveryStore(11)
        val port = FakePort(11)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertTrue(repo.restoreGenuine() is RepositoryResult.Success)
        assertNull(port.setCalledWith)
        assertNull(recovery.original)
    }

    @Test
    fun `record clear failure never falsely finishes recovery`() = runTest {
        val recovery = MemoryAudioRecoveryStore(11).apply { failClear = true }
        val port = FakePort(0)
        val repo = AudioPolicyRepository(FakeShizukuGateway(), unverifiedGuard, recovery) { port }
        assertTrue(repo.restoreGenuine() is RepositoryResult.Failure)
        assertEquals(11, recovery.original)
    }
}
