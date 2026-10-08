package com.izumiapps.cameramute.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.izumiapps.cameramute.assist.AssistNotifications
import com.izumiapps.cameramute.assist.AssistSafetyStore
import com.izumiapps.cameramute.assist.ManualAssistController
import com.izumiapps.cameramute.audiopolicy.AudioPolicyConstants
import com.izumiapps.cameramute.audiopolicy.AudioPolicyPort
import com.izumiapps.cameramute.audiopolicy.AudioPolicyRepository
import com.izumiapps.cameramute.audiopolicy.MemoryAudioRecoveryStore
import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.device.VerifiedTestDevice
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import com.izumiapps.cameramute.shizuku.ShizukuGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers as KotlinDispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeShizukuGateway(initial: ShizukuConnectionState) : ShizukuGateway {
    val mutableConnectionState = MutableStateFlow(initial)
    override val connectionState: StateFlow<ShizukuConnectionState> = mutableConnectionState

    override fun requestPermission(requestCode: Int) = Unit

    override suspend fun obtainAudioPolicyPort(): AudioPolicyPort =
        throw UnsupportedOperationException("FakePortを直接使うテストでは呼ばれない")
}

private class FakePort(var value: Int) : AudioPolicyPort {
    var failRead = false
    var failReadAfterWrite = false
    var reads = 0
    var onRead: (() -> Unit)? = null

    override fun getForceUse(usage: Int): Int {
        reads += 1
        onRead?.invoke()
        check(!failRead) { "simulated read failure" }
        return value
    }

    override fun setForceUse(usage: Int, config: Int) {
        value = config
        if (failReadAfterWrite) failRead = true
    }
}

private class InMemorySafetyStore : AssistSafetyStore {
    override var consentAccepted = true
}

private class FakeNotifications : AssistNotifications {
    var available = true
    var manualResult = true

    override fun canShowStatus(): Boolean = available
    override fun showManualActive(): Boolean = available && manualResult
    override fun cancelAll() = Unit
    override fun showRestoreFailed() = Unit
}

private data class Fixture(
    val viewModel: MainViewModel,
    val controller: ManualAssistController,
    val port: FakePort,
    val gateway: FakeShizukuGateway,
)

/** Controller移行後も、未検証の音設定を一瞬でも確定値として表示しないための回帰テスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        KotlinDispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    private fun createFixture(
        initialValue: Int = AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
        initialConnection: ShizukuConnectionState = ShizukuConnectionState.READY,
        repositoryDispatcher: CoroutineDispatcher = testDispatcher,
        notifications: FakeNotifications = FakeNotifications(),
    ): Fixture {
        val port = FakePort(initialValue)
        val gateway = FakeShizukuGateway(initialConnection)
        val guard = VerifiedTestDevice.guard()
        val repository = AudioPolicyRepository(
            shizukuGateway = gateway,
            deviceGuard = guard,
            recoveryStore = MemoryAudioRecoveryStore(),
            ioDispatcher = repositoryDispatcher,
        ) { port }
        val controller = ManualAssistController(
            repository = repository,
            safety = InMemorySafetyStore(),
            notifications = notifications,
            isSilentOrVibrate = { true },
            resources = ApplicationProvider.getApplicationContext<Context>().resources,
        )
        return Fixture(
            viewModel = MainViewModel(controller, gateway, guard),
            controller = controller,
            port = port,
            gateway = gateway,
        )
    }

    @Test
    fun `refresh failure clears a previously verified value`() = runTest {
        val fixture = createFixture()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ForceUseState.GenuineEnforced, fixture.viewModel.uiState.value.forceUseState)

        fixture.port.failRead = true
        fixture.viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertEquals(PrimaryStatus.UNKNOWN, fixture.viewModel.uiState.value.primaryStatus())
        assertTrue(fixture.viewModel.uiState.value.lastMessage != null)
        assertFalse(fixture.viewModel.uiState.value.isBusy)
    }

    @Test
    fun `post-write verification failure never retains the old genuine label`() = runTest {
        val fixture = createFixture()
        testDispatcher.scheduler.advanceUntilIdle()
        fixture.port.failReadAfterWrite = true

        fixture.viewModel.enableAssist()
        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertTrue(fixture.viewModel.uiState.value.isBusy)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fixture.port.value)
        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertEquals(PrimaryStatus.UNKNOWN, fixture.viewModel.uiState.value.primaryStatus())
        assertFalse(fixture.viewModel.uiState.value.isBusy)
    }

    @Test
    fun `refresh synchronously invalidates the previous value before reading`() = runTest {
        val fixture = createFixture()
        testDispatcher.scheduler.advanceUntilIdle()
        fixture.port.value = AudioPolicyConstants.CONFIG_FORCE_NONE

        fixture.viewModel.refresh()

        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertTrue(fixture.viewModel.uiState.value.isBusy)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ForceUseState.MuteAssistActive, fixture.viewModel.uiState.value.forceUseState)
        assertFalse(fixture.viewModel.uiState.value.isBusy)
    }

    @Test
    fun `queued refreshes never expose the first result while the newest request is pending`() = runTest {
        val fixture = createFixture()
        testDispatcher.scheduler.advanceUntilIdle()
        var requestedReads = 0
        fixture.port.onRead = {
            requestedReads += 1
            assertNull(fixture.viewModel.uiState.value.forceUseState)
            assertTrue(fixture.viewModel.uiState.value.isBusy)
            if (requestedReads == 2) fixture.port.value = AudioPolicyConstants.CONFIG_FORCE_NONE
        }

        fixture.viewModel.refresh()
        fixture.viewModel.refresh()
        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertTrue(fixture.viewModel.uiState.value.isBusy)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, requestedReads)
        assertEquals(ForceUseState.MuteAssistActive, fixture.viewModel.uiState.value.forceUseState)
        assertFalse(fixture.viewModel.uiState.value.isBusy)
    }

    @Test
    fun `disconnect during read does not publish a successful current value`() = runTest {
        val fixture = createFixture()
        testDispatcher.scheduler.advanceUntilIdle()
        fixture.port.onRead = {
            fixture.gateway.mutableConnectionState.value = ShizukuConnectionState.NOT_AVAILABLE
        }

        fixture.viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertEquals(PrimaryStatus.SHIZUKU_DISCONNECTED, fixture.viewModel.uiState.value.primaryStatus())
        assertFalse(fixture.viewModel.uiState.value.isBusy)
    }

    @Test
    fun `becoming ready automatically refreshes the unknown state`() = runTest {
        val fixture = createFixture(initialConnection = ShizukuConnectionState.PERMISSION_DENIED)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(fixture.viewModel.uiState.value.forceUseState)

        fixture.gateway.mutableConnectionState.value = ShizukuConnectionState.READY
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ForceUseState.GenuineEnforced, fixture.viewModel.uiState.value.forceUseState)
        assertFalse(fixture.viewModel.uiState.value.isBusy)
    }

    @Test
    fun `losing ready clears a possibly stale state`() = runTest {
        val fixture = createFixture()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ForceUseState.GenuineEnforced, fixture.viewModel.uiState.value.forceUseState)

        fixture.gateway.mutableConnectionState.value = ShizukuConnectionState.NOT_AVAILABLE
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(fixture.viewModel.uiState.value.forceUseState)
        assertEquals(PrimaryStatus.SHIZUKU_DISCONNECTED, fixture.viewModel.uiState.value.primaryStatus())
    }

    @Test
    fun `controller operation outside the view model never exposes a rollback intermediate value`() = runTest {
        val notifications = FakeNotifications().apply { manualResult = false }
        val repositoryDispatcher = StandardTestDispatcher(testDispatcher.scheduler)
        val fixture = createFixture(
            repositoryDispatcher = repositoryDispatcher,
            notifications = notifications,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        val observed = mutableListOf<MainUiState>()
        val observer = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            fixture.viewModel.uiState.collect { observed += it }
        }

        val result = async(testDispatcher) { fixture.controller.enable() }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(result.await())
        assertTrue(observed.any { it.isBusy })
        assertTrue(observed.none { it.isBusy && it.forceUseState != null })
        assertTrue(observed.none { it.isBusy && it.lastMessage != null })
        assertEquals(ForceUseState.GenuineEnforced, fixture.viewModel.uiState.value.forceUseState)
        observer.cancel()
    }
}
